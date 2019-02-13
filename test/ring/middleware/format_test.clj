(ns ring.middleware.format-test
  (:use [clojure.test]
        [ring.middleware.format])
  (:require [cheshire.core :as json]
            [clj-yaml.core :as yaml])
  (:import [java.io ByteArrayInputStream]))

(defn stream [s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(def restful-echo
  (wrap-restful-format (fn [req] (assoc req :body (vals (:body-params req))))))

(def restful-echo-opts-map
  (wrap-restful-format (fn [req] (assoc req :body (vals (:body-params req))))))

(def restful-echo-json
  (wrap-restful-format (fn [req] (assoc req :body (vals (:body-params req))))
                       :formats [:json-kw]))

(def restful-echo-yaml
  (wrap-restful-format (fn [req] (assoc req :body (vals (:body-params req))))
                       :formats [:yaml-kw]))

(deftest format-json-prettily-from-wrap-restful-format
  ;; like format-response-test/format-json-prettily but starting from higher in the call stack
  (let [body {:foo "bar"}
        req {:body body}
        resp ((wrap-restful-format identity :formats [:json-kw]
                                            :response-options {:json-kw {:pretty true}})
               req)]
    (is (.contains (-> resp :body slurp) "\n "))))

(deftest test-restful-round-trip
  (let [ok-accept "application/edn"
        msg {:test :ok}
        ok-req {:headers {"accept" ok-accept}
                :content-type ok-accept
                :body (stream (pr-str msg))}
        r-trip (restful-echo ok-req)]
    (is (= (get-in r-trip [:headers "Content-Type"])
           "application/edn; charset=utf-8"))
    (is (= (read-string (slurp (:body r-trip))) (vals msg)))
    (is (= (:params r-trip) msg))
    (is (.contains (get-in (restful-echo {:headers {"accept" "foo/bar"}})
                           [:headers "Content-Type"])
                   "application/json"))
    (is (restful-echo {:headers {"accept" "foo/bar"}})))
  (let [ok-accept "application/json"
        msg {"test" "ok"}
        ok-req {:headers {"accept" ok-accept}
                :content-type ok-accept
                :body (stream (json/encode msg))}
        r-trip (restful-echo-json ok-req)]
    (is (= (get-in r-trip [:headers "Content-Type"])
           "application/json; charset=utf-8"))
    (is (= (json/decode (slurp (:body r-trip))) (vals msg)))
    (is (= (:params r-trip) {:test "ok"}))
    (is (.contains (get-in (restful-echo-json
                            {:headers {"accept" "application/edn"}
                             :content-type "application/edn"})
                           [:headers "Content-Type"])
                   "application/json")))
  (let [ok-accept "application/x-yaml"
        msg {"test" "ok"}
        ok-req {:headers {"accept" ok-accept}
                :content-type ok-accept
                :body (stream (yaml/generate-string msg))}
        r-trip (restful-echo-yaml ok-req)]
    (is (= (:params r-trip) {:test "ok"})))
  (let [ok-accept "application/edn"
        msg {:test :ok}
        ok-req {:headers {"accept" ok-accept}
                :content-type ok-accept
                :body (stream (pr-str msg))}
        r-trip (restful-echo-opts-map ok-req)]
    (is (= (get-in r-trip [:headers "Content-Type"])
           "application/edn; charset=utf-8"))
    (is (= (read-string (slurp (:body r-trip))) (vals msg)))
    (is (= (:params r-trip) msg))
    (is (.contains (get-in (restful-echo-opts-map {:headers {"accept" "foo/bar"}})
                           [:headers "Content-Type"])
                   "application/json"))
    (is (restful-echo-opts-map {:headers {"accept" "foo/bar"}}))))
