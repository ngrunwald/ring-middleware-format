(ns ring.middleware.format-response-test
  (:refer-clojure :exclude [contains?])
  (:use [clojure.test]
        [ring.middleware.format-response])
  (:require [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.walk :refer [stringify-keys keywordize-keys]]
            [cognitect.transit :as transit]
            [msgpack.core :as msgpack]
            [clojure.string :as string])
  (:import [java.io ByteArrayInputStream InputStream ByteArrayOutputStream]))

(set! *warn-on-reflection* true)

(defn stream [^String s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(defn contains? [^String s h]
  (.contains s h))

(def json-echo
  (wrap-json-response identity))

(deftest noop-with-nil
  (let [body nil
        req {:body body}
        resp (json-echo req)]
    (is (= nil (:body resp)))))

(deftest noop-with-missing-body
  (let [req {:status 200}
        resp (json-echo req)]
    (is (= nil (:body resp)))))

(deftest noop-with-string
  (let [body "<xml></xml>"
        req {:body body}
        resp (json-echo req)]
    (is (= body (:body resp)))))

(deftest noop-with-stream
  (let [body "<xml></xml>"
        req {:body (stream body)}
        resp (json-echo req)]
    (is (= body (slurp (:body resp))))))

(deftest format-json-hashmap
  (let [body {:foo "bar"}
        req {:body body}
        resp (json-echo req)]
    (is (= (json/generate-string body) (slurp (:body resp))))
    (is (contains? (get-in resp [:headers "Content-Type"]) "application/json"))
    (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(deftest format-json-prettily
  (let [body {:foo "bar"}
        req {:body body}
        resp ((wrap-json-response identity :pretty true) req)]
    (is (contains? (slurp (:body resp)) "\n "))))

(deftest returns-correct-charset
  (let [body {:foo "bârçï"}
        req {:body body :headers {"accept-charset" "utf-8; q=0.8 , utf-16"}}
        resp ((wrap-json-response identity) req)]
    (is (contains? (get-in resp [:headers "Content-Type"]) "utf-16"))
    (is (= 32 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(deftest returns-utf8-by-default
  (let [body {:foo "bârçï"}
        req {:body body :headers {"accept-charset" "foo"}}
        resp ((wrap-json-response identity) req)]
    (is (contains? (get-in resp [:headers "Content-Type"]) "utf-8"))
    (is (= 18 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(deftest format-json-options
  (let [body {:foo-bar "bar"}
        req {:body body}
        resp ((wrap-json-response identity {:options {:key-fn (comp string/upper-case name)}}) req)
        resp2 ((wrap-restful-response identity {:format-options {:json {:key-fn (comp string/upper-case name)}}}) req)]
    (is (= "{\"FOO-BAR\":\"bar\"}"
           (slurp (:body resp))))
    (is (= "{\"FOO-BAR\":\"bar\"}"
           (slurp (:body resp2))))))

(def msgpack-echo
  (wrap-msgpack-response identity))

(defn ^:no-doc slurp-to-bytes
  #^bytes
  [#^InputStream in]
  (if in
    (let [buf (byte-array 4096)
          out (ByteArrayOutputStream.)]
      (loop []
        (let [r (.read in buf)]
          (when (not= r -1)
            (.write out buf 0 r)
            (recur))))
      (.toByteArray out))))

(deftest format-msgpack-hashmap
  (let [body {:foo "bar"}
        req {:body body}
        resp (msgpack-echo req)]
    (is (= body (keywordize-keys (msgpack/unpack (slurp-to-bytes (:body resp))))))
    (is (contains? (get-in resp [:headers "Content-Type"]) "application/msgpack"))
    (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(def clojure-echo
  (wrap-clojure-response identity))

(deftest format-clojure-hashmap
  (let [body {:foo "bar"}
        req {:body body}
        resp (clojure-echo req)]
    (is (= body (read-string (slurp (:body resp)))))
    (is (contains? (get-in resp [:headers "Content-Type"]) "application/edn"))
    (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(def yaml-echo
  (wrap-yaml-response identity))

(deftest format-yaml-hashmap
  (let [body {:foo "bar"}
        req {:body body}
        resp (yaml-echo req)]
    (is (= (yaml/generate-string body) (slurp (:body resp))))
    (is (contains? (get-in resp [:headers "Content-Type"]) "application/x-yaml"))
    (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(deftest html-escape-yaml-in-html
  (let [req {:body {:foo "<bar>"}}
        resp ((wrap-yaml-in-html-response identity) req)
        body (slurp (:body resp))]
    (is (= "<html>\n<head></head>\n<body><div><pre>\n{foo: &lt;bar&gt;}\n</pre></div></body></html>" body))))

;;;;;;;;;;;;;
;; Transit ;;
;;;;;;;;;;;;;

(defn read-transit
  [fmt in]
  (let [rdr (transit/reader in fmt)]
    (transit/read rdr)))

(def transit-json-echo
  (wrap-transit-json-response identity))

(deftest format-transit-json-hashmap
  (let [body {:foo "bar"}
        req {:body body}
        resp (transit-json-echo req)]
    (is (= body (read-transit :json (:body resp))))
    (is (contains? (get-in resp [:headers "Content-Type"]) "application/transit+json"))
    (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(def transit-msgpack-echo
  (wrap-transit-msgpack-response identity))

(deftest format-transit-msgpack-hashmap
  (let [body {:foo "bar"}
        req {:body body}
        resp (transit-msgpack-echo req)]
    (is (= body (read-transit :msgpack (:body resp))))
    (is (contains? (get-in resp [:headers "Content-Type"]) "application/transit+msgpack"))
    (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Content-Type parsing ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest can-encode?-accept-any-type
  (is (can-encode? {:enc-type {:type "foo" :sub-type "bar"}}
                   {:type "*" :sub-type "*"})))

(deftest can-encode?-accept-any-sub-type
  (let [encoder {:enc-type {:type "foo" :sub-type "bar"}}]
    (is (can-encode? encoder
                     {:type "foo" :sub-type "*"}))
    (is (not (can-encode? encoder
                          {:type "foo" :sub-type "buzz"})))))

(deftest can-encode?-accept-specific-type
  (let [encoder {:enc-type {:type "foo" :sub-type "bar"}}]
    (is (can-encode? encoder
                     {:type "foo" :sub-type "bar"}))
    (is (not (can-encode? encoder
                          {:type "foo" :sub-type "buzz"})))))

(deftest parse-accept-header-test
  (testing "Accept header media-ranges can contain parameters"
    (is (= [{:type "multipart"
             :sub-type "form-data"
             :boundary "x"
             :charset "US-ASCII"
             :q 1.0}]
           (parse-accept-header* "multipart/form-data; boundary=x; charset=US-ASCII"))))

  (testing "Non-numberic q value is ignored"
    (is (= [{:type "text"
             :sub-type "*"
             :q 1.0}]
           (parse-accept-header* "text/*;q=x") )))

  (testing "Separators in parameter values (e.g. =) are forbidden"
    (is (= [{:type "text"
             :sub-type "*"
             :q 1.0}]
           (parse-accept-header* "text/*;x=0.0=x") ))

    (testing "except if quoted"
      (is (= [{:type "text"
               :sub-type "*"
               :q 1.0
               :x "\"0.0=x\""}]
             (parse-accept-header* "text/*;x=\"0.0=x\""))))))

(deftest orders-values-correctly
  (let [accept "text/plain,*/*,text/plain;level=1, text/*, text/*;q=0.1"]
    (is (= (parse-accept-header accept)
           (list {:type "text"
                  :sub-type "plain"
                  :q 1.0}
                 {:type "text"
                  :sub-type "plain"
                  :level "1"
                  :q 1.0}
                 {:type "text"
                  :sub-type "*"
                  :q 1.0}
                 {:type "*"
                  :sub-type "*"
                  :q 1.0}
                 {:type "text"
                  :sub-type "*"
                  :q 0.1})))))

(deftest gives-preferred-encoder
  (let [accept [{:type "text"
                 :sub-type "*"}
                {:type "application"
                 :sub-type "json"
                 :q 0.5}]
        req {:headers {"accept" accept}}
        html-encoder {:enc-type {:type "text" :sub-type "html"}}
        json-encoder {:enc-type {:type "application" :sub-type "json"}}]
    (is (= (preferred-encoder [json-encoder html-encoder] req)
           html-encoder))
    (is (= (preferred-encoder [json-encoder html-encoder] {})
           json-encoder))
    (is (nil? (preferred-encoder [{:enc-type {:type "application"
                                              :sub-type "edn"}}]
                                 req)))))

(defn echo-with-default-body [req] (assoc req :body (get req :body {})))

(def restful-echo
  (wrap-restful-response echo-with-default-body))

(def safe-restful-echo
  (wrap-restful-response echo-with-default-body
                         :handle-error (fn [_ _ _] {:status 500})
                         :formats
                         [(make-encoder (fn [_] (throw (RuntimeException. "Memento mori")))
                                        "foo/bar")]))

(def safe-restful-echo-opts-map
  (wrap-restful-response identity
                         {:handle-error (fn [_ _ _] {:status 500})
                          :formats
                          [(make-encoder (fn [_] (throw (RuntimeException. "Memento mori")))
                                         "foo/bar")]}))

(deftest format-hashmap-to-preferred
  (let [ok-accept "application/edn, application/json;q=0.5"
        ok-req {:headers {"accept" ok-accept}}]
    (is (= (get-in (restful-echo ok-req) [:headers "Content-Type"])
           "application/edn; charset=utf-8"))
    (is (contains? (get-in (restful-echo {:headers {"accept" "foo/bar"}})
                           [:headers "Content-Type"])
                   "application/json"))
    (is (= 500 (get (safe-restful-echo {:status 200
                                        :headers {"accept" "foo/bar"}
                                        ; a non serializable, non-nil body is required, otherwise
                                        ; the response is passed through unchanged
                                        :body {}}) :status)))
    (is (= 500 (get (safe-restful-echo-opts-map {:status 200
                                                 :headers {"accept" "foo/bar"}
                                                 :body {}}) :status)))))

(deftest format-restful-hashmap
  (let [body {:foo "bar"}]
    (doseq [accept ["application/edn"
                    "application/json"
                    "application/msgpack"
                    "application/x-yaml"
                    "application/transit+json"
                    "application/transit+msgpack"
                    "text/html"]]
      (let [req {:body body :headers {"accept" accept}}
            resp (restful-echo req)]
        (is (contains? (get-in resp [:headers "Content-Type"]) accept))
        (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))
    (let [req {:body body}
          resp (restful-echo req)]
      (is (contains? (get-in resp [:headers "Content-Type"]) "application/json"))
      (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"])))))))

(def custom-restful-echo
  (wrap-restful-response identity
                         :formats [{:encoder (constantly "foobar")
                                    :enc-type {:type "text"
                                               :sub-type "foo"}}]))

(deftest format-custom-restful-hashmap
  (let [req {:body {:foo "bar"} :headers {"accept" "text/foo"}}
        resp (custom-restful-echo req)]
    (is (contains? (get-in resp [:headers "Content-Type"]) "text/foo"))
    (is (< 2 (Integer/parseInt (get-in resp [:headers "Content-Length"]))))))

(deftest nil-body-handling
  (let [req {:body {:headers {"accept" "application/json"}}}
        handler (-> (constantly {:status 200
                              :headers {}})
                 wrap-restful-response)
        resp (handler req)]
    (is (= "application/json; charset=utf-8" (get-in resp [:headers "Content-Type"])))
    (is (= "0" (get-in resp [:headers "Content-Length"])))
    (is (nil? (:body resp)))))

(def restful-echo-pred
  (wrap-restful-response identity :predicate (fn [_ resp]
                                               (::serializable? resp))))

(deftest custom-predicate
  (let [req {:body {:foo "bar"}}
        resp-non-serialized (restful-echo-pred (assoc req ::serializable? false))
        resp-serialized     (restful-echo-pred (assoc req ::serializable? true))]
    (is (map? (:body resp-non-serialized)))
    (is (instance? java.io.BufferedInputStream (:body resp-serialized)))))

(def custom-encoder (make-encoder (make-json-encoder false nil) "application/vnd.mixradio.something+json"))

(def custom-content-type
  (wrap-restful-response (fn [req]
                           {:status 200
                            :body {:foo "bar"}})
                         {:formats [custom-encoder :json-kw]}))

(deftest custom-content-type-test
  (let [resp (custom-content-type {:body {:foo "bar"} :headers {"accept" "application/vnd.mixradio.something+json"}})]
    (is (= "application/vnd.mixradio.something+json; charset=utf-8" (get-in resp [:headers "Content-Type"])))))

;;
;; Transit options
;;

(defrecord Point [x y])

(def writers
  {Point (transit/write-handler (constantly "Point") (fn [p] [(:x p) (:y p)]))})

(def custom-transit-echo
  (wrap-transit-json-response identity :options {:handlers writers}))

(def custom-restful-transit-echo
  (wrap-restful-response identity :format-options {:transit-json {:handlers writers}}))

(def transit-resp {:body (Point. 1 2)})

(deftest write-custom-transit
  (is (= "[\"~#Point\",[1,2]]"
         (slurp (:body (custom-transit-echo transit-resp)))))
  (is (= "[\"~#Point\",[1,2]]"
         (slurp (:body (custom-restful-transit-echo (assoc transit-resp :headers {"accept" "application/transit+json"})))))))
