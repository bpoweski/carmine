(ns taoensso.carmine.tundra
  "Semi-automatic datastore layer for Carmine. It's like the magix.
  Use multiple Redis instances (recommended) or Redis databases for local key
  namespacing."
  {:author "Peter Taoussanis"}
  (:require [taoensso.carmine       :as car :refer (wcar)]
            [taoensso.carmine.message-queue :as mq]
            [taoensso.carmine.utils :as utils]
            [taoensso.nippy         :as nippy]
            [taoensso.nippy.tools   :as nippy-tools]
            [taoensso.timbre        :as timbre]))

;; TODO Redis 2.8+ http://redis.io/topics/notifications

;;;; Public interfaces

(defprotocol IDataStore "Extension point for additional datastores."
  (put-key   [dstore k v] "(put-key dstore \"key\" \"val\") => e/o #{true <ex>}")
  (fetch-key [dstore k]   "(fetch-key dstore \"key\") => e/o #{<frozen-val> <ex>}\""))

(defprotocol IFreezer "Extension point for compressors, encryptors, etc."
  (freeze [freezer x] "Returns datastore-ready key val.")
  (thaw   [freezer x] "Returns Redis-ready key val."))

(defprotocol ITundraStore
  (ensure-ks* [tstore ks])
  (dirty*     [tstore ks])
  (worker     [tstore conn wopts]
    "Alpha - subject to change.
    Returns a threaded [message queue] worker to routinely freeze Redis keys
    marked as dirty to datastore and mark successfully frozen keys as clean.
    Logs any errors. THESE ERRORS ARE **IMPORTANT**: an email or other
    appropriate notification mechanism is HIGHLY RECOMMENDED. If a worker shuts
    down and your keys are set to expire YOU WILL IRREVOCABLY **LOSE DATA**.

    Options:
      :nattempts        - Number of times worker will attempt to freeze a key to
                          datastore before failing permanently. >=1.
      :retry-backoff-ms - Amount of time (msecs) to backoff before retrying a
                          failed key freeze. >=0. Can be a (fn [attempt]) -> ms.

      :montior, :eoq-backoff-ms, :nthreads, :throttle-ms, :auto-start?
      - Standard `taoensso.carmine.message-queue/worker` opts."))

(defn ensure-ks
  "BLOCKS to ensure given keys (previously created) are available in Redis,
  fetching them from datastore as necessary. Throws an exception if any keys
  couldn't be made available. Acts as a Redis command: call within a `wcar`
  context."
  [tstore & ks] (ensure-ks* tstore ks))

(defn dirty
  "Queues given keys for freezing to datastore. Throws an exception if any keys
  don't exist. Acts as a Redis command: call within a `wcar` context.

  If TundraStore has a :redis-ttl-ms option, **MARKS GIVEN KEYS FOR EXPIRY**!!
  ** Worker MUST be running AND FUNCTIONING CORRECTLY or DATA WILL BE LOST! **"
  [tstore & ks] (dirty* tstore ks))

;;;; Default implementations

(defrecord NippyFreezer [opts]
  IFreezer
  (freeze [_ x]  (nippy/freeze x  opts))
  (thaw   [_ ba] (nippy/thaw   ba opts)))

(def nippy-freezer "Default Nippy Freezer." (->NippyFreezer {}))

;;;;

(defn- extend-exists
  "Returns 0/1 for each key that doesn't/exist, extending any preexisting TTLs."
  ;; Cluster: no between-key atomicity requirements, can pipeline per shard
  [ttl-ms keys]
  (car/lua
    "local result = {}
     local ttl = tonumber(ARGV[1])
     for i,k in pairs(KEYS) do
       if ttl > 0 and redis.call('ttl', k) > 0 then
         result[i] = redis.call('pexpire', k, ttl)
       else
         result[i] = redis.call('exists', k)
       end
     end
     return result"
    keys
    [(or ttl-ms 0)]))

(comment (wcar {} (car/ping) (extend-exists nil ["k1" "invalid" "k3"])))

(defn- extend-exists-missing-ks [ttl-ms ks]
  (let [existance-replies (->> (extend-exists ttl-ms ks)
                               (car/with-replies)
                               (car/parse nil))
        ks-missing        (->> (mapv #(when (zero? %2) %1) ks existance-replies)
                               (filterv identity))]
    ks-missing))

(defn- prep-ks [ks] (assert (utils/coll?* ks)) (vec (distinct (mapv name ks))))
(comment (prep-ks [nil]) ; ex
         (prep-ks [:a "a" :b :foo.bar/baz]))

(defmacro ^:private catcht [& body] `(try (do ~@body) (catch Throwable t# t#)))
(def ^:private tqname "carmine.tundra")

(defrecord TundraStore [datastore freezer opts]
  ITundraStore
  (ensure-ks* [tstore ks]
    (let [{:keys [redis-ttl-ms]} opts
          ks         (prep-ks ks)
          ks-missing (extend-exists-missing-ks redis-ttl-ms ks)]

      (when-not (empty? ks-missing)
        (timbre/tracef "Fetching missing keys: %s" ks-missing)

        (let [;;; [] e/o #{<dumpval> <throwable>}:
              throwable?    #(instance? Throwable %)
              dvals-missing (->> ks-missing (mapv #(catcht (fetch-key datastore %))))
              dvals-missing (if (nil? freezer) dvals-missing
                                (->> dvals-missing
                                     (mapv #(if (throwable? %) %
                                                (catcht (thaw freezer %))))))
              restore-replies ; [] e/o #{"OK" <throwable>}
              (->> dvals-missing
                   (mapv (fn [k dv]
                           (if (throwable? dv) (car/return dv)
                               (if-not (utils/bytes? dv)
                                 (car/return (Exception. "Malformed fetch data"))
                                 (car/restore k (or redis-ttl-ms 0) (car/raw dv)))))
                         ks-missing)
                   (car/with-replies :as-pipeline)
                   (car/parse nil))

              errors ; {<k> <throwable>}
              (->> (zipmap ks-missing restore-replies)
                   (reduce (fn [m [k v]]
                             (if-not (throwable? v) m
                               (if (and (instance? Exception v)
                                        (= (.getMessage ^Exception v)
                                           "ERR Target key name is busy."))
                                 m ; Already restored
                                 (assoc m k v))))
                           {}))]
          (when-not (empty? errors)
            (let [ex (ex-info "Failed to ensure some key(s)" errors)]
              (timbre/error ex) (throw ex)))
          nil))))

  (dirty* [tstore ks]
    (let [{:keys [redis-ttl-ms]} opts
          ks             (prep-ks ks)
          ks-missing     (extend-exists-missing-ks redis-ttl-ms ks)
          ks-not-missing (->> ks (filterv (complement (set ks-missing))))]

      (doseq [k ks-not-missing]
        (mq/enqueue tqname k k :allow-locked-dupe)) ; key as msg & mid (deduped)

      (when-not (empty? ks-missing)
        (let [ex (ex-info "Some dirty key(s) were missing" {:ks ks-missing})]
          (timbre/error ex) (throw ex)))
      nil))

  (worker [tstore conn wopts]
    (let [{:keys [nattempts retry-backoff-ms]
           :or   {nattempts 3
                  retry-backoff-ms mq/exp-backoff}} wopts]
      (mq/worker conn tqname
        (assoc wopts :handler
          (fn [{:keys [mid message attempt]}]
            (let [k message
                  put-reply ; #{true nil <throwable>}
                  (catcht (->> (wcar conn (car/parse-raw (car/dump k)))
                               (#(if (or (nil? %) ; Key doesn't exist
                                         (nil? freezer)) %
                                         (freeze freezer %)))
                               (#(if (nil? %) nil
                                     (put-key datastore k %)))))]

              (if (= put-reply true)
                {:status :success}
                (if (<= attempt nattempts)
                  {:status :retry
                   :backoff-ms
                   (cond (nil?     retry-backoff-ms) nil
                         (fn?      retry-backoff-ms) (retry-backoff-ms attempt)
                         (integer? retry-backoff-ms) retry-backoff-ms)}

                  {:status :error
                   :throwable
                   (cond
                    (nil? put-reply) (ex-info "Key doesn't exist" {:k k})
                    :else (ex-info "Bad put-reply" {:k k :put-reply put-reply}))})))))))))

;;;;

(defn tundra-store
  "Alpha - subject to change.
  Returns a TundraStore with options:
    datastore     - Storage for frozen key data. Default datastores:
                    `taoensso.carmine.tundra.faraday/faraday-datastore`
                    `taoensso.carmine.tundra.s3/s3-datastore`.
    :freezer      - Optional. Preps key data to/from datastore. May provide
                    services like compression and encryption, etc. Defaults to
                    Nippy with default options (Snappy compression and no
                    encryption).
    :redis-ttl-ms - Optional! Time after which frozen, inactive keys will be
                    EVICTED FROM REDIS (**DELETED!**). Minimum 10 hours. ONLY
                    use this if you have CONFIRMED that your worker is
                    successfully freezing the necessary keys to your datastore.
                    Otherwise YOU WILL IRREVOCABLY **LOSE DATA**.

  See `ensure-ks`, `dirty`, `worker` for TundraStore API."
  [datastore & [{:keys [freezer redis-ttl-ms ]
                 :or   {freezer nippy-freezer}}]]

  (assert (or (nil? freezer) (satisfies? IFreezer freezer)))
  (assert (satisfies? IDataStore datastore))
  (assert (or (nil? redis-ttl-ms) (>= redis-ttl-ms (* 1000 60 60 10)))
          (str "Bad TTL (< 10 hours): " redis-ttl-ms))

  (->TundraStore datastore freezer {:redis-ttl-ms redis-ttl-ms}))

(comment ; README

(require '[taoensso.carmine.tundra :as tundra :refer (ensure-ks dirty)])
(require '[taoensso.carmine.tundra.faraday :as tfar])
(defmacro wcar* [& body] `(wcar {} ~@body))
(timbre/set-level! :trace)

(def creds {:access-key "<AWS_DYNAMODB_ACCESS_KEY>"
            :secret-key "<AWS_DYNAMODB_SECRET_KEY>"}) ; AWS IAM credentials

;; Create a DynamoDB table for key data storage (this can take ~2m):
(tfar/ensure-table creds {:throughput {:read 1 :write 1} :block? true})

;; Create a TundraStore backed by the above table:
(def tstore
  (tundra-store
   (tfar/faraday-datastore creds
     {:key-ns :my-app.production             ; For multiple apps/envs per table
      :auto-write-units {0 1, 100 2, 200, 4} ; For automatic write-throughput scaling
      })

   {:freezer      nippy-freezer ; Use Nippy for compression/encryption
    :redis-ttl-ms (* 1000 60 60 24 31) ; Evict cold keys after one month
    }))

;; Create a threaded worker to freeze dirty keys every hour:
(def tundra-worker
  (worker tstore {:pool {} :spec {}} ; Redis connection
    {:frequency-ms (* 1000 60 60)}))

;; Let's create some new evictable keys:
(wcar* (car/mset :k1 0 :k2 0 :k3 0)
       (dirty tstore [:k1 :k2 :k3]))

;; Now imagine time passes and some keys get evicted:
(wcar* (car/del :k1 :k3))

;; And now we want to use our evictable keys:
(wcar*
 (ensure-ks tstore :k1 :k2 :k3) ; Ensures previously-created keys are available
 (car/mget :k1 :k2 :k3)         ; Gets their current value
 (mapv car/incr [:k1 :k3])      ; Modifies them
 (dirty tstore :k1 :k3)         ; Marks them for later refreezing by worker
 )

)
