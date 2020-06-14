(ns serenity.core-test
  (:require
   [cljs.test :as t :include-macros true]
   [serenity.core :as s]
   [clojure.string]))


;;
;; Utils
;;

(defn await
  ([pred desc]
   (await pred desc #()))
  ([pred desc done]
   (letfn [(cb [current-tries]
             (cond
               (or (pred) (zero? current-tries))
               (do (t/is (pred) desc)
                   (done))

               :else (js/setTimeout
                      #(cb (dec current-tries))
                      100)))]
     (cb 10))
   nil))


(defn awaitp
  [pred]
  (js/Promise.
   (fn [resolve reject]
     (letfn [(cb [current-tries]
               (cond
                 (pred)
                 (resolve)

                 (zero? current-tries)
                 (resolve)

                 :else (js/setTimeout
                        #(cb (dec current-tries))
                        100)))]
       (cb 10)))))


(defn queue
  [f]
  (js/setTimeout f 0))


(defn queue-send
  [src msg]
  (queue #(s/send src msg)))


(defn spy
  [f]
  (let [calls (atom 0)]
    [calls (fn [& args]
             (swap! calls inc)
             (apply f args))]))


;;
;; Tests
;;


(t/deftest basic-graph
  (t/async
   done
   (let [src (s/source (fn [_ x] x)
                       :initial 0)
         a (s/signal #(inc @src))

         b (s/signal #(inc @src))

         c (s/signal #(+ @a @b))

         values (atom [])]

     (s/sink! c (fn [_ _ n]
                  (swap! values conj n)))

     (queue-send src 1)
     (queue-send src 2)
     (queue-send src 3)
     (queue-send src 4)

     (-> (awaitp #(= @values [2 4 6 8 10]))
         (.then #(t/is (= @values [2 4 6 8 10])
                       "Expected values are equal"))
         (.then done)))))


(t/deftest simple-sync-disposal
  (t/testing
      "That a sink which is disposed in the same tick as messages are sent will not run"
    (t/async
     done
     (let [src (s/source (fn [_ x] x)
                         :initial 0)
           values (atom [])
           [sink!-calls sink!-f] (spy (fn [_ _ n]
                                        (swap! values conj n)))
           sink! (s/sink! src sink!-f)]

       (s/send src 1)
       (s/send src 2)
       (s/dispose! sink!)
       (s/send src 3)
       (s/send src 4)

       (-> (js/Promise.all
            #js [(awaitp #(= [0] @values))
                 (awaitp #(= 1 @sink!-calls))])
           (.then #(t/is (= [0] @values)))
           ;; `1` because it ran once on initial state
           (.then #(t/is (= 1 @sink!-calls)))
           (.then done))))))


(t/deftest simple-async-disposal
  (t/async
   done
   (let [src (s/source (fn [_ x] x)
                       :initial 0)
         values (atom [])
         [sink!-calls sink!-f] (spy (fn [_ _ n]
                                      (swap! values conj n)))
         sink! (s/sink! src sink!-f)]

     (queue-send src 1)
     (queue-send src 2)
     (queue #(s/dispose! sink!))
     (queue-send src 3)
     (queue-send src 4)

     (-> (js/Promise.all
          #js [(awaitp #(= [0 1 2] @values))
               (awaitp #(= 3 @sink!-calls))])
         (.then #(t/is (= [0 1 2] @values)))
         ;; `3` because it ran once on initial state
         (.then #(t/is (= 3 @sink!-calls)))
         (.then done)))))


(t/deftest complex-graph-disposal)


(t/deftest batching
  (t/async
   done
   (let [src (s/source (fn [_ x] x)
                       :initial 0)
         values (atom [])
         [sink!-calls sink!-f] (spy (fn [_ _ n]
                                      (swap! values conj n)))]
     (s/sink! src sink!-f)
     ;; send all in one batch
     (queue (fn []
              (s/send src 1)
              (s/send src 2)
              (s/send src 3)
              (s/send src 4)))
     (-> (js/Promise.all
          #js [(awaitp #(= [0 4] @values))
               (awaitp #(= 2 @sink!-calls))])
         (.then #(t/is (= [0 4] @values)))
         ;; `2` because it ran once on initial state
         (.then #(t/is (= 2 @sink!-calls)))
         (.then done)))))


(t/deftest memoizing-outputs
  (t/async
   done
   (let [[src-calls src-f] (spy (fn [_ _]
                                  {:asdf "jkl"}))
         src (s/source src-f :initial {:asdf "jkl"})
         a (s/signal #(deref src))
         b (s/signal #(+ @a))
         [sink!-calls sink!-f] (spy #())]
     (s/sink! b sink!-f)

     (queue-send src nil)
     (queue-send src nil)
     (queue-send src nil)

     (-> (awaitp #(= 4 @src-calls))
         (.then #(t/is (= 3 @src-calls)))
         (.then #(t/is (= 1 @sink!-calls)))
         (.then done)))))


(t/deftest nasty-diamond
  (t/testing "That each node is only fired once in the graph"
    (t/async
     done
     (let [src (s/source (fn [_ x] x)
                         :initial 0)

           [a0-calls a0-f] (spy #(inc @src))
           a0 (s/signal a0-f)


           [a-calls a-f] (spy #(inc @a0))
           a (s/signal a-f)

           [b-calls b-f] (spy #(dec @src))
           b (s/signal b-f)

           [c-calls c-f] (spy #(+ @a @b))
           c (s/signal c-f)]

       ;; make it happen
       (s/sink! c (fn [_ _ _]))

       (t/is (= [1 1 1 1]
                [@a0-calls @a-calls @b-calls @c-calls]))

       (s/send src 1)

       (await #(= [2 2 2 2]
                  [@a0-calls @a-calls @b-calls @c-calls])
              "Expected calls match"
              done)))))


(t/deftest simple-transducer)


(t/deftest filter-transducer)


(t/deftest stateful-transducer)


(t/deftest on-connect)


(t/deftest cycles)


#_(t/run-tests)
