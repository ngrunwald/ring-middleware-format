(ns ring.middleware.format-params-test
  (:use [clojure.test]
        [ring.middleware.format-params])
  (:require [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [cognitect.transit :as transit]
            [clojure.java.io :as io]
            [clojure.walk :refer [stringify-keys keywordize-keys]]
            [msgpack.core :as msgpack]
            [clojure.string :as string])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(set! *warn-on-reflection* true)

(defn stream [^String s]
  (ByteArrayInputStream. (.getBytes s "UTF-8")))

(def json-echo
  (wrap-json-params identity))

;; stolen from ring-json-params to confirm compatibility

(deftest noop-with-other-content-type
  (let [req {:content-type "application/xml"
             :body (stream "<xml></xml>")
             :params {"id" 3}}
        resp (json-echo req)]
    (is (= "<xml></xml>" (slurp (:body resp))))
    (is (= {"id" 3} (:params resp)))
    (is (nil? (:json-params resp)))))

(deftest augments-with-json-content-type
  (let [req {:content-type "application/json; charset=UTF-8"
             :body (stream "{\"foo\": \"bar\"}")
             :params {"id" 3}}
        resp (json-echo req)]
    (is (= {"id" 3 "foo" "bar"} (:params resp)))
    (is (= {"foo" "bar"} (:body-params resp)))))

(deftest augments-with-vnd-json-content-type
  (let [req {:content-type "application/vnd.foobar+json; charset=UTF-8"
             :body (stream "{\"foo\": \"bar\"}")
             :params {"id" 3}}
        resp (json-echo req)]
    (is (= {"id" 3 "foo" "bar"} (:params resp)))
    (is (= {"foo" "bar"} (:body-params resp)))))

(defn key-fn [s]
  (-> s (string/replace #"_" "-") keyword))

(deftest json-options-test
  (is (= {:foo-bar "bar"}
         (:body-params ((wrap-json-params identity {:options {:key-fn key-fn}})
                        {:content-type "application/json"
                         :body (stream "{\"foo_bar\":\"bar\"}")}))))
  (is (= {:foo-bar "bar"}
         (:body-params ((wrap-json-kw-params identity {:options {:key-fn key-fn}})
                        {:content-type "application/json"
                         :body (stream "{\"foo_bar\":\"bar\"}")}))))
  (is (= {:foo-bar "bar"}
         (:body-params ((wrap-restful-params identity {:format-options {:json {:key-fn key-fn}}})
                        {:content-type "application/json"
                         :body (stream "{\"foo_bar\":\"bar\"}")})))))

(def yaml-echo
  (wrap-yaml-params identity))

(deftest augments-with-yaml-content-type
  (let [req {:content-type "application/x-yaml; charset=UTF-8"
             :body (stream "foo: bar")
             :params {"id" 3}}
             resp (yaml-echo req)]
    (is (= {"id" 3 :foo "bar"} (:params resp)))
    (is (= {:foo "bar"} (:body-params resp)))))

(def msgpack-echo
  (wrap-msgpack-params identity))

(deftest augments-with-msgpack-content-type
  (let [req {:content-type "application/msgpack"
             :body (ByteArrayInputStream. (msgpack/pack (stringify-keys {:foo "bar"})))
             :params {"id" 3}}
             resp (msgpack-echo req)]
    (is (= {"id" 3 "foo" "bar"} (:params resp)))
    (is (= {"foo" "bar"} (:body-params resp)))))

(def msgpack-kw-echo
  (wrap-msgpack-kw-params identity))

(deftest augments-with-msgpack-kw-content-type
  (let [req {:content-type "application/msgpack"
             :body (ByteArrayInputStream. (msgpack/pack (stringify-keys {:foo "bar"})))
             :params {"id" 3}}
             resp (msgpack-kw-echo req)]
    (is (= {"id" 3 :foo "bar"} (:params resp)))
    (is (= {:foo "bar"} (:body-params resp)))))

(def clojure-echo
  (wrap-clojure-params identity))

(deftest augments-with-clojure-content-type
  (let [req {:content-type "application/clojure; charset=UTF-8"
             :body (stream "{:foo \"bar\"}")
             :params {"id" 3}}
             resp (clojure-echo req)]
    (is (= {"id" 3 :foo "bar"} (:params resp)))
    (is (= {:foo "bar"} (:body-params resp)))))


(deftest augments-with-clojure-content-prohibit-eval-in-reader
  (let [req {:content-type "application/clojure; charset=UTF-8"
             :body (stream "{:foo #=(java.util.Date.)}")
             :params {"id" 3}}]
    (try
      (let [resp (clojure-echo req)]
        (is false "Eval in reader permits arbitrary code execution."))
      (catch Exception ignored))))

(deftest no-body-with-clojure-content-type
  (let [req {:content-type "application/clojure; charset=UTF-8"
             :body (stream "")
             :params {"id" 3}}
             resp (clojure-echo req)]
    (is (= {"id" 3} (:params resp)))
    (is (= nil (:body-params resp)))))

(deftest whitespace-body-with-clojure-content-type
  (let [req {:content-type "application/clojure; charset=UTF-8"
             :body (stream "\t  ")
             :params {"id" 3}}
             resp (clojure-echo req)]
    (is (= {"id" 3} (:params resp)))
    (is (= nil (:body-params resp)))))

;;;;;;;;;;;;;
;; Transit ;;
;;;;;;;;;;;;;

(defn stream-transit
  [fmt data]
  (let [out (ByteArrayOutputStream.)
        wrt (transit/writer out fmt)]
    (transit/write wrt data)
    (io/input-stream (.toByteArray out))))

(def transit-json-echo
  (wrap-transit-json-params identity))

(deftest augments-with-transit-json-content-type
  (let [req {:content-type "application/transit+json"
             :body (stream-transit :json {:foo "bar"})
             :params {"id" 3}}
             resp (transit-json-echo req)]
    (is (= {"id" 3 :foo "bar"} (:params resp)))
    (is (= {:foo "bar"} (:body-params resp)))))

(def transit-msgpack-echo
  (wrap-transit-msgpack-params identity))

(deftest augments-with-transit-msgpack-content-type
  (let [req {:content-type "application/transit+msgpack"
             :body (stream-transit :msgpack {:foo "bar"})
             :params {"id" 3}}
             resp (transit-msgpack-echo req)]
    (is (= {"id" 3 :foo "bar"} (:params resp)))
    (is (= {:foo "bar"} (:body-params resp)))))

;;;;;;;;;;;;;;;;;;;;
;; Restful Params ;;
;;;;;;;;;;;;;;;;;;;;

(def restful-echo
  (wrap-restful-params identity))

(def safe-restful-echo
  (wrap-restful-params identity
                       :handle-error (fn [_ _ _] {:status 500})))

(def safe-restful-echo-opts-map
  (wrap-restful-params identity {:handle-error (fn [_ _ _] {:status 500})}))

(deftest test-restful-params-wrapper
  (let [req {:content-type "application/clojure; charset=UTF-8"
             :body (stream "{:foo \"bar\"}")
             :params {"id" 3}}
             resp (restful-echo req)]
    (is (= {"id" 3 :foo "bar"} (:params resp)))
    (is (= {:foo "bar"} (:body-params resp)))
    (is (= 500 (get (safe-restful-echo (assoc req :body (stream "{:foo \"bar}"))) :status)))
    (is (= 500 (get (safe-restful-echo-opts-map (assoc req :body (stream "{:foo \"bar}"))) :status)))))

(defn stream-iso [^String s]
  (ByteArrayInputStream. (.getBytes s "ISO-8859-1")))

(deftest test-different-params-charset
  (let [req {:content-type "application/clojure; charset=ISO-8859-1"
             :body (stream-iso "{:fée \"böz\"}")
             :params {"id" 3}}
        resp (restful-echo req)]
    (is (= {"id" 3 :fée "böz"} (:params resp)))
    (is (= {:fée "böz"} (:body-params resp)))))

(deftest test-list-body-request
  (let [req {:content-type "application/json"
             :body (ByteArrayInputStream.
                    (.getBytes "[\"gregor\", \"samsa\"]"))}]
    ((wrap-json-params
      (fn [{:keys [body-params]}] (is (= ["gregor" "samsa"] body-params))))
     req)))

(deftest test-optional-body
  ((wrap-json-params
    (fn [request]
      (is (nil? (:body request)))))
   {:body nil}))

(deftest test-custom-handle-error
  (are [format content-type body]
    (let [req {:body body
               :content-type content-type}
          resp ((wrap-restful-params identity
                                     :formats [format]
                                     :handle-error (constantly {:status 999}))
                req)]
      (= 999 (:status resp)))
    :json "application/json" "{:a 1}"
    :edn "application/edn" "{\"a\": 1}"))

;;
;; Transit options
;;

(defrecord Point [x y])

(def readers
  {"Point" (transit/read-handler (fn [[x y]] (Point. x y)))})

(def custom-transit-json-echo
  (wrap-transit-json-params identity :options {:handlers readers}))

(def custom-restful-transit-json-echo
  (wrap-restful-params identity :format-options {:transit-json {:handlers readers}}))

(def transit-body "[\"^ \", \"~:p\", [\"~#Point\",[1,2]]]")

(deftest read-custom-transit
  (testing "wrap-transit-json-params, transit options"
    (let [parsed-req (custom-transit-json-echo {:content-type "application/transit+json"
                                                :body (stream transit-body)})]
      (is (= {:p (Point. 1 2)}
             (:params parsed-req)
             (:body-params parsed-req)))))
  (testing "wrap-restful-params, transit options"
    (let [req (custom-restful-transit-json-echo {:content-type "application/transit+json"
                                                 :body (stream transit-body)})]
      (is (= {:p (Point. 1 2)}
             (:params req)
             (:body-params req))))))

;;
;; Guess charset
;;

(def icu-available? (try (Class/forName "com.ibm.icu.text.CharsetDetector")
                         true
                         (catch ClassNotFoundException _
                           false)))

(when icu-available?
  (deftest guess-charset-test
    (println "Testing charset detection")
    (require 'ring.middleware.format-params.guess-charset)
    (let [json-guess-charset (wrap-json-params identity {:charset (resolve 'ring.middleware.format-params.guess-charset/get-or-guess-charset)})
          ba (byte-array (concat (.getBytes "{\"foo\":\"")
                                 ;; föäo in ISO encoding
                                 [98 246 228 114]
                                 (.getBytes "\"}")))
          req {:content-type "application/json"
               :body (ByteArrayInputStream. ba)}
          resp (json-guess-charset req)]
      (is (= "ISO-8859-1" ((resolve 'ring.middleware.format-params.guess-charset/guess-charset) {:body ba})))
      ;; decoded to current Java charset
      (is (= {"foo" "böär"} (:body-params resp))))))
