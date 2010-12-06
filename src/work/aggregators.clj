(ns work.aggregators
  (:use [plumbing.core]
	[work.core :only [work available-processors map-work queue-work]]
	[work.queue :only [local-queue]]))

(defn- channel-as-lazy-seq
  "ch is a fn you call to get an item (no built-in timeout, use with-timeout).
   When the ch is exhausted it returns :eof.

   This fn returns a lazy sequence from the channel."
  [ch]
  (lazy-seq
   (let [x (ch)]
     (when (not= x :eof)
       (cons x (channel-as-lazy-seq ch))))))

(defn channel-from-seq
  [xs]
  (let [q (local-queue xs)]
    (fn []
      (if (.isEmpty q)
	:eof
	((with-ex (constantly :eof) #(.remove q)))))))

(defn ordered-agg
  "returns an aggregator which in parallel executes
   fetch on elements of a channel (see above) and
   performs reduce on the resulting lazy sequence.

   Essentially, the map-reduce function on data
   with the sequence ordering constraint."
  ([fetch agg &
    {:keys [num-threads]
     :or {num-threads (available-processors)}}]
     (fn [ch]
       (->> (channel-as-lazy-seq ch)
	    (map-work fetch num-threads)
	    (reduce agg)))))

(defn- abelian-worker
  [ch fetch agg pingback]
  (fn []
    (loop [v nil]
      (let [elem (-->> [] ch
		       (with-timeout 10)
		       (with-ex (constantly :eof)))]
	(if (= elem :eof)
	  (pingback v)
	  (recur (agg v (fetch elem))))))))

(defn abelian-agg
  "returns an aggregator which in parallel executes
   fetches and aggs results. The order of agg is not
   guranteed so operation should be commutativie + associative."
  [fetch agg &
   {:keys [num-threads]
    :or {num-threads (available-processors)}}]
  (fn [ch]
    (let [res (atom nil)
	  pingback (fn [v] (swap! res agg v))]
      (work (repeatedly num-threads #(abelian-worker ch fetch agg pingback))
	    num-threads)
      @res)))