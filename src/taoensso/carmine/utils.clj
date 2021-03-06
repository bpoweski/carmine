(ns taoensso.carmine.utils
  {:author "Peter Taoussanis"}
  (:require [clojure.string      :as str]
            [clojure.tools.macro :as macro]))

(defmacro declare-remote
  "Declares the given ns-qualified names, preserving symbol metadata. Useful for
  circular dependencies."
  [& names]
  (let [original-ns (str *ns*)]
    `(do ~@(map (fn [n]
                  (let [ns (namespace n)
                        v  (name n)
                        m  (meta n)]
                    `(do (in-ns  '~(symbol ns))
                         (declare ~(with-meta (symbol v) m))))) names)
         (in-ns '~(symbol original-ns)))))

(defmacro defonce*
  "Like `clojure.core/defonce` but supports optional docstring and attributes
  map for name symbol."
  {:arglists '([name expr])}
  [name & sigs]
  (let [[name [expr]] (macro/name-with-attributes name sigs)]
    `(clojure.core/defonce ~name ~expr)))

(defmacro defalias
  "Defines an alias for a var, preserving metadata. Adapted from
  clojure.contrib/def.clj, Ref. http://goo.gl/xpjeH"
  [name target & [doc]]
  `(let [^clojure.lang.Var v# (var ~target)]
     (alter-meta! (def ~name (.getRawRoot v#))
                  #(merge % (apply dissoc (meta v#) [:column :line :file :test :name])
                            (when-let [doc# ~doc] {:doc doc#})))
     (var ~name)))

(defmacro time-ns "Returns number of nanoseconds it takes to execute body."
  [& body] `(let [t0# (System/nanoTime)] ~@body (- (System/nanoTime) t0#)))

(defmacro bench
  "Repeatedly executes body and returns time taken to complete execution."
  [nlaps {:keys [nlaps-warmup nthreads as-ns?]
          :or   {nlaps-warmup 0
                 nthreads     1}} & body]
  `(let [nlaps#        ~nlaps
         nlaps-warmup# ~nlaps-warmup
         nthreads#     ~nthreads]
     (try (dotimes [_# nlaps-warmup#] ~@body)
          (let [nanosecs#
                (if (= nthreads# 1)
                  (time-ns (dotimes [_# nlaps#] ~@body))
                  (let [nlaps-per-thread# (int (/ nlaps# nthreads#))]
                    (time-ns
                     (->> (fn [] (future (dotimes [_# nlaps-per-thread#] ~@body)))
                          (repeatedly nthreads#)
                          (doall)
                          (map deref)
                          (dorun)))))]
            (if ~as-ns? nanosecs# (Math/round (/ nanosecs# 1000000.0))))
          (catch Exception e# (format "DNF: %s" (.getMessage e#))))))

(defn fq-name "Like `name` but includes namespace in string when present."
  [x] (if (string? x) x
          (let [n (name x)]
            (if-let [ns (namespace x)] (str ns "/" n) n))))

(comment (map fq-name ["foo" :foo :foo.bar/baz]))

(defn comp-maybe [f g] (cond (and f g) (comp f g) f f g g :else nil))
(comment ((comp-maybe nil identity) :x))

(defmacro repeatedly* "Like `repeatedly` but faster and returns a vector."
  [n & body]
  `(let [n# ~n]
     (loop [v# (transient []) idx# 0]
       (if (>= idx# n#)
         (persistent! v#)
         (recur (conj! v# (do ~@body)) (inc idx#))))))

(def ^:const bytes-class (Class/forName "[B"))
(defn bytes? [x] (instance? bytes-class x))
(defn ba= [^bytes x ^bytes y] (java.util.Arrays/equals x y))

(defn memoize-ttl "Low-overhead, common-case `memoize*`."
  [ttl-ms f]
  (let [cache (atom {})]
    (fn [& args]
      (when (<= (rand) 0.001) ; GC
        (let [instant (System/currentTimeMillis)]
          (swap! cache
            (fn [m] (reduce-kv (fn [m* k [dv udt :as cv]]
                                (if (> (- instant udt) ttl-ms) m*
                                    (assoc m* k cv))) {} m)))))
      (let [[dv udt] (@cache args)]
        (if (and dv (< (- (System/currentTimeMillis) udt) ttl-ms)) @dv
          (locking cache ; For thread racing
            (let [[dv udt] (@cache args)] ; Retry after lock acquisition!
              (if (and dv (< (- (System/currentTimeMillis) udt) ttl-ms)) @dv
                (let [dv (delay (apply f args))
                      cv [dv (System/currentTimeMillis)]]
                  (swap! cache assoc args cv)
                  @dv)))))))))
