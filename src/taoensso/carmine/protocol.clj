(ns taoensso.carmine.protocol
  "Facilities for actually communicating with Redis server using its
  request/response protocol. Originally adapted from Accession.

  Ref: http://redis.io/topics/protocol"
  {:author "Peter Taoussanis"}
  (:require [clojure.string       :as str]
            [taoensso.encore      :as encore]
            [taoensso.nippy       :as nippy]
            [taoensso.nippy.tools :as nippy-tools])
  (:import  [java.io DataInputStream BufferedOutputStream]
            [clojure.lang Keyword]))

;;; Outline (Carmine v3+)
;; * Dynamic context is established with `carmine/wcar`.
;; * Commands executed w/in this context push their requests (vectors) into
;;   context's request queue. Requests may have metadata for Cluster keyslots &
;;   parsers. Parsers may have metadata as a convenient+composable way of
;;   communicating special request requirements (:raw-bulk?, :thaw-opts, etc.).
;; * On `with-reply`, nested `wcar`, or `execute-requests` - queued requests
;;   will actually be sent to server as pipeline.
;; * For non-listener modes, corresponding replies will then immediately be
;;   received, parsed, + returned.

;;;; Dynamic context stuff

(encore/declare-remote taoensso.carmine.cluster/execute-requests)

(defrecord Context [conn      ; active Connection
                    req-queue ; [<pulled-reqs> [<queued-req> ...]] atom
                    ])
(def ^:dynamic *context* nil) ; Context
(def ^:dynamic *parser*  nil) ; ifn (with optional meta) or nil
(def no-context-ex
  (Exception.
   (str "Redis commands must be called within the context of a"
        " connection to Redis server. See `wcar`.")))

;;;;

(defn- bytestring
  "Redis communicates with clients using a (binary-safe) byte string protocol.
  This is the equivalent of the byte array representation of a Java String."
  ^bytes [^String s] (.getBytes s "UTF-8"))

;;; Request delimiters
(def ^bytes bs-crlf (bytestring "\r\n"))
(def ^:const ^Integer bs-* (int (first (bytestring "*"))))
(def ^:const ^Integer bs-$ (int (first (bytestring "$"))))

;; Carmine-only markers that'll be used _within_ bulk data to indicate that
;; the data requires special reply handling
(def ^bytes bs-bin (bytestring "\u0000<")) ; Binary data marker
(def ^bytes bs-clj (bytestring "\u0000>")) ; Frozen data marker

(defn- ensure-reserved-first-byte [^bytes ba]
  (when (= (first ba) 0)
    (throw (Exception. (str "Args can't begin with null terminator"))))
  ba)

(defrecord WrappedRaw [ba])
(defn raw "Forces byte[] argument to be sent to Redis as raw, unencoded bytes."
  [x] (if (encore/bytes? x) (->WrappedRaw x)
        (throw (Exception. "Raw arg must be byte[]"))))

(defprotocol IRedisArg
  (coerce-bs [x] "Coerces arbitrary Clojure value to RESP arg, by type."))

(extend-protocol IRedisArg
  String  (coerce-bs [x] (-> (bytestring x)
                             (ensure-reserved-first-byte)))
  Keyword (coerce-bs [x] (-> (bytestring ^String (encore/fq-name x))
                             (ensure-reserved-first-byte)))

  ;;; Simple number types (Redis understands these)
  Long    (coerce-bs [x] (bytestring (str x)))
  Double  (coerce-bs [x] (bytestring (str x)))
  Float   (coerce-bs [x] (bytestring (str x)))
  Integer (coerce-bs [x] (bytestring (str x)))

  ;;;
  WrappedRaw (coerce-bs [x] (:ba x))
  nil        (coerce-bs [x] (encore/ba-concat bs-clj (nippy-tools/freeze x)))
  Object     (coerce-bs [x] (encore/ba-concat bs-clj (nippy-tools/freeze x))))

(extend encore/bytes-class IRedisArg
  {:coerce-bs (fn [x] (encore/ba-concat bs-bin x))})

(defmacro ^:private send-*    [out] `(.write ~out bs-*))
(defmacro ^:private send-$    [out] `(.write ~out bs-$))
(defmacro ^:private send-crlf [out] `(.write ~out bs-crlf 0 2))

(defn- send-requests [^BufferedOutputStream out requests]
  "Sends requests to Redis server using its byte string protocol:
    *<no. of args>     crlf
    [$<size of arg N>  crlf
      <arg data>       crlf ...]"
  ;; {:pre [(vector? requests)]}
  (doseq [req-args requests]
    (when (pos? (count req-args)) ; [] req is dummy req for `return`
      (let [bs-args (:bytestring-req (meta req-args))]

        (send-* out)
        (.write out (bytestring (str (count bs-args))))
        (send-crlf out)

        (doseq [^bytes bs-arg bs-args]
          (let [payload-size (alength bs-arg)]
            (send-$ out)
            (.write out (bytestring (str payload-size)))
            (send-crlf out)
            ;;
            (.write out bs-arg 0 payload-size) ; Payload
            (send-crlf out)))

        (comment (.flush out)))))
  (.flush out))

(defn get-unparsed-reply
  "Implementation detail.
  BLOCKS to receive a single reply from Redis server and returns the result as
  [<type> <reply>]. Redis will reply to commands with different kinds of replies,
  identified by their first byte, Ref. http://redis.io/topics/protocol:

    * `+` for simple strings -> <string>
    * `:` for integers       -> <long>
    * `-` for error strings  -> <ex-info>
    * `$` for bulk strings   -> <clj>/<raw-bytes>    ; Marked as serialized
                             -> <bytes>/<raw-bytes>  ; Marked as binary
                             -> <string>/<raw-bytes> ; Unmarked
                             -> nil
    * `*` for arrays         -> <vector>
                             -> nil"
  [^DataInputStream in req-opts]
  (let [reply-type (char (.readByte in))]
    (case reply-type
      \+ (.readLine in)
      \: (Long/parseLong (.readLine in))
      \- (let [err-str    (.readLine in)
               err-prefix (re-find #"^\S+" err-str) ; "ERR", "WRONGTYPE", etc.
               err-prefix (when err-prefix
                            (-> err-prefix str/lower-case keyword))]
           (ex-info err-str (if-not err-prefix {} {:prefix err-prefix})))

      ;;; Bulk strings need checking for special in-data markers
      \$ (let [data-size (Integer/parseInt (.readLine in))]
           (if (== data-size -1) nil
             (if (:raw-bulk? req-opts)
               (let [data (byte-array data-size)]
                 (.readFully in data 0 data-size)
                 (.readFully in (byte-array 2) 0 2) ; Discard final crlf
                 data)

               (let [maybe-marked-type? (>= data-size 2)
                     type (if-not maybe-marked-type? :str
                            (let [h (byte-array 2)]
                              (.mark      in 2)
                              (.readFully in h 0 2)
                              (condp encore/ba= h
                                bs-clj :clj
                                bs-bin :bin
                                :str)))
                     marked-type? (not (identical? type :str))
                     payload-size (int (if marked-type? (- data-size 2) data-size))
                     payload      (byte-array payload-size)]

                 (when (and maybe-marked-type? (not marked-type?)) (.reset in))
                 (.readFully in payload 0 payload-size)
                 (.readFully in (byte-array 2) 0 2) ; Discard final crlf

                 (try
                   (case type
                     :str (String. payload 0 payload-size "UTF-8")
                     :clj (if-let [thaw-opts (:thaw-opts req-opts)]
                            (nippy/thaw payload thaw-opts)
                            (nippy/thaw payload))
                     :bin ; payload
                     ;; Workaround #81 (v2.6.0 may have written _serialized_ bins):
                     (if (= (take 3 payload) '(78 80 89)) ; Nippy header
                       (try (nippy/thaw payload) (catch Exception _ payload))
                       payload))
                   (catch Exception e
                     (Exception. (str "Bad reply data: " (.getMessage e)) e)))))))

      \* (let [bulk-count (Integer/parseInt (.readLine in))]
           (if (== bulk-count -1) nil ; Nb was [] with < Carmine v3
             (encore/repeatedly* bulk-count (get-unparsed-reply in req-opts))))

      (throw (Exception. (format "Server returned unknown reply type: %s"
                           (str reply-type)))))))

(defn get-parsed-reply "Implementation detail."
  [^DataInputStream in ?parser]
  (let [;; As an implementation detail, parser metadata is used as req-opts:
        ;; {:keys [raw-bulk? thaw-opts dummy-reply :parse-exceptions?]}. We
        ;; could instead choose to split parsers and req metadata but bundling
        ;; the two is efficient + quite convenient in practice. Note that we
        ;; choose to _merge_ parser metadata during parser comp.
        req-opts (meta ?parser)
        unparsed-reply (if (contains? req-opts :dummy-reply) ; May be nil!
                         (:dummy-reply req-opts)
                         (get-unparsed-reply in req-opts))]
    (if-not ?parser unparsed-reply ; Common case
      (if (and (instance? Exception unparsed-reply)
               ;; Nb :parse-exceptions? is rare & not normally used by lib
               ;; consumers. Such parsers need to be written to _not_ interfere
               ;; with our ability to interpret Cluster error messages.
               (not (:parse-exceptions? req-opts)))
        unparsed-reply ; Return unparsed
        (try (?parser unparsed-reply)
             (catch Exception e
               (Exception. (format "Parser error: %s" (.getMessage e)) e)))))))

;;;; Parsers

(defmacro parse
  "Wraps body so that replies to any wrapped Redis commands will be parsed with
  `(f reply)`. Replaces any current parser; removes parser when `f` is nil.
  See also `parser-comp`."
  [f & body] `(binding [*parser* ~f] ~@body))

(defn- comp-maybe [f g] (cond (and f g) (comp f g) f f g g :else nil))
(comment ((comp-maybe nil identity) :x))

(defn parser-comp "Composes parsers when f or g are nnil, preserving metadata."
  [f g] (let [m (merge (meta g) (meta f))]
          (with-meta (comp-maybe f g) m)))

;;; Special parsers used to communicate metadata to request enqueuer:
(defmacro parse-raw [& body] `(parse (with-meta identity {:raw-bulk? true}) ~@body))
(defmacro parse-nippy [thaw-opts & body]
  `(parse (with-meta identity {:thaw-opts ~thaw-opts}) ~@body))

(defn return
  "Takes value and returns it unchanged as part of next reply from Redis server.
  Unlike `echo`, does not actually send any data to Redis."
  [value]
  (swap! (:req-queue *context*)
    (fn [[_ q]]
      [nil (conj q (with-meta [] ; Dummy request
                     {:parser (parser-comp *parser* ; Nb keep context's parser
                                (with-meta identity {:dummy-reply value}))
                      :expected-keyslot nil ; Irrelevant
                      }))])))

;;;; Requests

(defn execute-requests
  "Implementation detail.
  Sends given/dynamic requests to given/dynamic Redis server and optionally
  blocks to receive the relevant queued (pipelined) parsed replies."

  ;; For use with standard dynamic bindings:
  ([get-replies? replies-as-pipeline?]
     (let [{:keys [conn req-queue]} *context*
           requests ; Atomically pull reqs from dynamic queue:
           (-> (swap! req-queue (fn [[_ q]] [q []]))
               (nth 0))]
       (if (get-in conn [:spec :cluster])
         (taoensso.carmine.cluster/execute-requests conn requests get-replies? replies-as-pipeline?)
         (execute-requests conn requests get-replies? replies-as-pipeline?))))


  ;; For use with Cluster, etc.:
  ([conn requests get-replies? replies-as-pipeline?]
     (let [nreqs (count requests)]
       (when (pos? nreqs)
         (let [{:keys [in out]} conn]
           (when-not (and in out) (throw no-context-ex))
           ;; (println "Sending requests: " requests)
           (send-requests out requests)
           (when get-replies?
             (if (or (> nreqs 1) replies-as-pipeline?)
               (mapv (fn [req] (get-parsed-reply in (:parser (meta req))))
                     requests)
               (let [req (nth requests 0)
                     one-reply (get-parsed-reply in (:parser (meta req)))]
                 (if (instance? Exception one-reply) (throw one-reply)
                   one-reply)))))))))

;;;; General-purpose macros

(defmacro with-replies*
  "Implementation detail.
  Light, fresh-context version of `with-replies`. Useful for consumers that'll
  be in complete control of the connection/context."
  {:arglists '([:as-pipeline & body] [& body])}
  [& [s1 & sn :as sigs]]
  (let [as-pipeline? (identical? s1 :as-pipeline)
        body         (if as-pipeline? sn sigs)]
    `(do ~@body (execute-requests :get-replies ~as-pipeline?))))

(defmacro with-replies
  "Alpha - subject to change.
  Evaluates body, immediately returning the server's response to any contained
  Redis commands (i.e. before enclosing context ends).

  As an implementation detail, stashes and then `return`s any replies already
  queued with Redis server: i.e. should be compatible with pipelining.

  Note on parsers: if you're writing a Redis command (e.g. a fn that is intended
  to execute w/in an implicit connection context) and you're using `with-replies`
  as an implementation detail (i.e. you're interpreting replies internally), you
  probably want `(parse nil (with-replies ...))` to keep external parsers from
  leaking into your internal logic."
  {:arglists '([:as-pipeline & body] [& body])}
  [& [s1 & sn :as sigs]]
  (let [as-pipeline? (identical? s1 :as-pipeline)
        body         (if as-pipeline? sn sigs)]
    `(let [?stashed-replies# (execute-requests :get-replies :as-pipeline)]
       (try
         ;; This'll be returned to `with-replies` caller:
         (do ~@body (execute-requests :get-replies ~as-pipeline?))

         ;; Then we'll restore any stashed replies to underlying context:
         (finally
           (when ?stashed-replies#
             (parse nil ; Already parsed on stashing
               (mapv return ?stashed-replies#))))))))

(defmacro with-context "Implementation detail."
  [conn & body]
  `(binding [*context* (->Context ~conn (atom [nil []]))
             *parser*  nil]
     ~@body))
