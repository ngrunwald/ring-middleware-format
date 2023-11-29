(ns ring.middleware.format-response
  (:require [ring.middleware.format.impl :as impl]
            [cheshire.core :as json]
            [ring.util.response :as res]
            [clojure.java.io :as io]
            [clj-yaml.core :as yaml]
            [clojure.string :as s]
            [clojure.walk :refer [stringify-keys]]
            [cognitect.transit :as transit]
            [msgpack.core :as msgpack])
  (:use [clojure.core.memoize :only [lu]])
  (:import [java.io File InputStream BufferedInputStream
            ByteArrayOutputStream]
           [java.nio.charset Charset]))

(set! *warn-on-reflection* true)

(def available-charsets
  "Set of recognised charsets by the current JVM"
  (into #{} (map s/lower-case (.keySet (Charset/availableCharsets)))))

(defn ^:no-doc serializable?
  "Predicate that returns true whenever the response body is not a
  String, File or InputStream."
  [_ {:keys [body] :as response}]
  (when response
    (not (or
          (string? body)
          (instance? File body)
          (instance? InputStream body)))))

(defn can-encode?
  "Check whether encoder can encode to accepted-type.
  Accepted-type should have keys *:type* and *:sub-type* with appropriate
  values."
  [{:keys [enc-type] :as encoder} {:keys [type sub-type] :as accepted-type}]
  (or (= "*" type)
      (and (= (:type enc-type) type)
           (or (= "*" sub-type)
               (= (enc-type :sub-type) sub-type)))))

(defn ^:no-doc sort-by-check
  [by check headers]
  (sort-by by (fn [a b]
                (cond (= (= a check) (= b check)) 0
                      (= a check) 1
                      :else -1))
           headers))

;; From Liberator: https://github.com/clojure-liberator/liberator/blob/master/src/liberator/conneg.clj#L13
(def ^:private accept-fragment-re
  #"^\s*(\*|[^()<>@,;:\"/\[\]?={}         ]+)/(\*|[^()<>@,;:\"/\[\]?={}         ]+)$")

(def ^:private accept-fragment-param-re
  #"([^()<>@,;:\"/\[\]?={} 	]+)=([^()<>@,;:\"/\[\]?={} 	]+|\"[^\"]*\")$")

(defn- parse-q [s]
  (try
    (->> (Double/parseDouble s)
         (min 1)
         (max 0))
    (catch NumberFormatException e
      nil)))

(defn parse-accept-header*
  "Parse Accept headers into a sorted sequence of maps.
  \"application/json;level=1;q=0.4\"
  => ({:type \"application\" :sub-type \"json\"
       :q 0.4 :level \"1\"})"
  [accept-header]
  (if accept-header
    (->> (map (fn [fragment]
                (let [[media-range & params-list] (s/split fragment #"\s*;\s*")
                      [type sub-type] (rest (re-matches accept-fragment-re media-range))]
                  (-> (reduce (fn [m s]
                                (if-let [[k v] (seq (rest (re-matches accept-fragment-param-re s)))]
                                  (if (= "q" k)
                                    (update-in m [:q] #(or % (parse-q v)))
                                    (assoc m (keyword k) v))
                                  m))
                              {:type type
                               :sub-type sub-type}
                              params-list)
                      (update-in [:q] #(or % 1.0)))))
              (s/split accept-header #"[\s\n\r]*,[\s\n\r]*"))
         (sort-by-check :type "*")
         (sort-by-check :sub-type "*")
         (sort-by :q >))))

(def parse-accept-header
  "Memoized form of [[parse-accept-header*]]"
  (lu parse-accept-header* {} :lu/threshold 500))

(defn preferred-encoder
  "Return the encoder that encodes to the most preferred type.
  If the *Accept* header of the request is a *String*, assume it is
  according to Ring spec. Else assume the header is a sequence of
  accepted types sorted by their preference. If no accepted encoder is
  found, return *nil*. If no *Accept* header is found, return the first
  encoder."
  [encoders req]
  (if-let [accept (get-in req [:headers "accept"])]
    (first (for [accepted-type (if (string? accept)
                                 (parse-accept-header accept)
                                 accept)
                 encoder encoders
                 :when (can-encode? encoder accepted-type)]
             encoder))
    (first encoders)))

(defn parse-charset-accepted
  "Parses an *accept-charset* string to a list of [*charset* *quality-score*]"
  [v]
  (let [segments (s/split v #",")
        choices (for [segment segments
                      :when (not (empty? segment))
                      :let [[_ charset qs] (re-find #"([^;]+)(?:;\s*q\s*=\s*([0-9\.]+))?" segment)]
                      :when charset
                      :let [qscore (try
                                     (Double/parseDouble (s/trim qs))
                                     (catch Exception e 1))]]
                  [(s/trim charset) qscore])]
    choices))

(defn preferred-charset
  "Returns an acceptable choice from a list of [*charset* *quality-score*]"
  [charsets]
  (or
   (->> (sort-by second charsets)
        (reverse)
        (filter (comp available-charsets first))
        (first)
        (first))
   "utf-8"))

(defn make-encoder
  "Return a encoder map suitable for [[wrap-format-response.]]
   f takes a string and returns an encoded string
   type *Content-Type* of the encoded string
   (make-encoder json/generate-string \"application/json\")"
  ([encoder content-type binary?]
     {:encoder encoder
      :enc-type (first (parse-accept-header content-type))
      :binary? binary?
      ;; Include content-type to allow later introspection of encoders.
      :content-type content-type})
  ([encoder content-type]
     (make-encoder encoder content-type false)))

(defn default-handle-error
  "Default error handling function used, which rethrows the Exception"
  [e _ _]
  (throw e))

(defn choose-charset*
  "Returns an useful charset from the accept-charset string.
   Defaults to utf-8"
  [accept-charset]
  (let [possible-charsets (parse-charset-accepted accept-charset)]
      (preferred-charset possible-charsets)))

(def choose-charset
  "Memoized form of [[choose-charset*]]"
  (lu choose-charset* {} :lu/threshold 500))

(defn default-charset-extractor
  "Default charset extractor, which returns either *Accept-Charset*
   header field or *utf-8*"
  [request]
  (if-let [accept-charset (get-in request [:headers "accept-charset"])]
    (choose-charset accept-charset)
    "utf-8"))

(defn wrap-format-response
  "Wraps a handler such that responses body to requests are formatted to
  the right format. If no *Accept* header is found, use the first encoder.

 + **:predicate** is a predicate taking the request and response as
                  arguments to test if serialization should be used
 + **:encoders** a sequence of maps given by make-encoder
 + **:charset** can be either a string representing a valid charset or a fn
                taking the req as argument and returning a valid charset
                (*utf-8* is strongly suggested)
 + **:binary?** if true *:charset* will be ignored and decoder will receive
               an *InputStream*
 + **:handle-error** is a fn with a sig [exception request response]. Defaults
                     to just rethrowing the Exception"
  [handler & args]
  (let [{:keys [response-predicate predicate encoders charset binary? handle-error]} (impl/extract-options args)
        charset (or charset default-charset-extractor)
        handle-error (or handle-error default-handle-error)
        predicate (or response-predicate predicate serializable?)]
    (fn [req]
      (let [{:keys [headers body] :as response} (handler req)]
        (try
          (if (predicate req response)
            (let [{:keys [encoder enc-type binary?]} (or (preferred-encoder encoders req) (first encoders))
                  [body* content-type]
                  (if binary?
                    (let [body* (encoder body)
                          ctype (str (enc-type :type) "/" (enc-type :sub-type))]
                      [body* ctype])
                    (let [^String char-enc (if (string? charset) charset (charset req))
                          ^String body-string (if (nil? body) "" (encoder body))
                          body* (.getBytes body-string char-enc)
                          ctype (str (enc-type :type) "/" (enc-type :sub-type)
                                     "; charset=" char-enc)]
                      [body* ctype]))
                  body-length (count body*)]
              (-> response
                  (assoc :body (if (pos? body-length) (io/input-stream body*) nil))
                  (res/content-type content-type)
                  (res/header "Content-Length" body-length)))
            response)
          (catch Exception e
            (handle-error e req response)))))))

(defn make-json-encoder [pretty options]
  (let [opts (merge {:pretty pretty} options)]
    (fn [s]
      (json/generate-string s opts))))

(defn wrap-json-response
  "Wrapper to serialize structures in *:body* to JSON with sane defaults.
   See [[wrap-format-response]] for more details."
  [handler & args]
  (let [{:keys [encoder type pretty options] :as opts} (impl/extract-options args)
        encoder (or encoder (make-json-encoder pretty options))]
    (wrap-format-response handler (assoc opts :encoders [(make-encoder encoder (or type "application/json"))]))))

;; Functions for Clojure native serialization

(defn ^:no-doc generate-native-clojure
  [struct]
  (pr-str struct))

(defn ^:no-doc generate-hf-clojure
  [struct]
  (binding [*print-dup* true]
    (pr-str struct)))

(defn wrap-clojure-response
  "Wrapper to serialize structures in *:body* to Clojure native with sane defaults.
  If *:hf* is set to true, will use *print-dup* for high-fidelity
  printing ( see
  [here](https://groups.google.com/d/msg/clojure/5wRBTPNu8qo/1dJbtHX0G-IJ) ).
  See [[wrap-format-response]] for more details."
  [handler & args]
  (let [{:keys [encoder type hf] :as options} (impl/extract-options args)]
    (wrap-format-response handler
      (assoc options :encoders [(make-encoder
                                  (if hf generate-hf-clojure (or encoder generate-native-clojure))
                                  (or type "application/edn"))]))))

(defn encode-msgpack [body]
  (with-open [out-stream (ByteArrayOutputStream.)]
    (let [data-out (java.io.DataOutputStream. out-stream)]
      (msgpack/pack-stream (stringify-keys body) data-out))
    (.toByteArray out-stream)))

(defn wrap-msgpack-response
  "Wrapper to serialize structures in *:body* to **msgpack** with sane
  defaults. See [[wrap-format-response]] for more details."
  [handler & args]
  (let [{:keys [binary? encoder type] :as options} (impl/extract-options args)]
    (wrap-format-response handler (assoc options
                                         :encoders [(make-encoder (or encoder encode-msgpack) (or type "application/msgpack") :binary)]
                                         :binary? (if (nil? binary?) true binary?)))))

(defn encode-msgpack-kw [body]
  (encode-msgpack (stringify-keys body)))

(defn wrap-yaml-response
  "Wrapper to serialize structures in *:body* to YAML with sane
  defaults. See [[wrap-format-response]] for more details."
  [handler & args]
  (let [{:keys [predicate encoder type charset] :as options} (impl/extract-options args)]
    (wrap-format-response handler (assoc options
                                         :encoders [(make-encoder (or encoder yaml/generate-string) (or type "application/x-yaml"))]
                                         :charset (or charset default-charset-extractor)))))

(defn- escape-html [s]
  (s/escape s {\& "&amp;"
               \< "&lt;"
               \> "&gt;"
               \" "&quot;"
               \' "&apos;"}))

(defn ^:no-doc wrap-yaml-in-html
  [body]
  (str
   "<html>\n<head></head>\n<body><div><pre>\n"
   (escape-html (yaml/generate-string body))
   "</pre></div></body></html>"))

(defn wrap-yaml-in-html-response
  "Wrapper to serialize structures in *:body* to YAML wrapped in HTML to
  check things out in the browser. See [[wrap-format-response]] for more
  details."
  [handler & args]
  (let [{:keys [predicate encoder type charset handle-error] :as options} (impl/extract-options args)]
    (wrap-format-response handler (assoc options
                                         :encoders [(make-encoder (or encoder wrap-yaml-in-html) (or type "text/html"))]
                                         :charset (or charset default-charset-extractor)))))

;;;;;;;;;;;;;
;; Transit ;;
;;;;;;;;;;;;;

(defn ^:no-doc make-transit-encoder
  [fmt {:keys [verbose] :as options}]
  (fn [data]
    (let [out (ByteArrayOutputStream.)
          full-fmt (if (and (= fmt :json) verbose)
                     :json-verbose
                     fmt)
          wrt (transit/writer out full-fmt options)]
      (transit/write wrt data)
      (.toByteArray out))))

(defn wrap-transit-json-response
  "Wrapper to serialize structures in *:body* to transit over **JSON** with sane defaults.
  See [[wrap-format-response]] for more details."
  [handler & args]
  (let [{:keys [encoder type options] :as opts} (impl/extract-options args)
        encoder (or encoder (make-transit-encoder :json options))]
    (wrap-format-response handler (assoc opts
                                         :encoders [(make-encoder encoder (or type "application/transit+json") :binary)]
                                         :charset nil))))

(defn wrap-transit-msgpack-response
  "Wrapper to serialize structures in *:body* to transit over **msgpack** with sane defaults.
  See [[wrap-format-response]] for more details."
  [handler & args]
  (let [{:keys [encoder type options] :as opts} (impl/extract-options args)
        encoder (or encoder (make-transit-encoder :msgpack options))]
    (wrap-format-response handler (assoc opts
                                         :encoders [(make-encoder encoder (or type "application/transit+msgpack") :binary)]
                                         :charset nil))))

(def ^:no-doc format-encoders
  {:json (assoc (make-encoder nil "application/json")
                :encoder-fn #(make-json-encoder false %))
   :json-kw (assoc (make-encoder nil "application/json")
                   :encoder-fn #(make-json-encoder false %))
   :edn (make-encoder generate-native-clojure "application/edn")
   :msgpack (make-encoder encode-msgpack "application/msgpack" :binary)
   :msgpack-kw (make-encoder encode-msgpack-kw "application/msgpack" :binary)
   :clojure (make-encoder generate-native-clojure "application/clojure")
   :yaml (make-encoder yaml/generate-string "application/x-yaml")
   :yaml-kw (make-encoder yaml/generate-string "application/x-yaml")
   :yaml-in-html (make-encoder wrap-yaml-in-html "text/html")
   :transit-json (assoc (make-encoder nil "application/transit+json" :binary)
                   :encoder-fn #(make-transit-encoder :json %))
   :transit-msgpack (assoc (make-encoder nil "application/transit+msgpack" :binary)
                      :encoder-fn #(make-transit-encoder :msgpack %))})

(defn init-encoder [encoder opts]
  (if-let [init (:encoder-fn encoder)]
    (assoc encoder :encoder (init opts))
    encoder))

(def default-formats [:json :yaml :edn :msgpack :clojure :yaml-in-html :transit-json :transit-msgpack])

(defn wrap-restful-response
  "Wrapper that tries to do the right thing with the response *:body*
  and provide a solid basis for a RESTful API. It will serialize to
  JSON, YAML, Clojure, Transit or HTML-wrapped YAML depending on Accept header.
  See wrap-format-response for more details. Recognized formats are
  *:json*, *:json-kw*, *:edn* *:yaml*, *:yaml-in-html*, *:transit-json*,
  *:transit-msgpack*.
  Options to specific encoders can be passed in using *:format-options*
  option. If is a map from format keyword to options map."
  [handler & args]
  (let [{:keys [formats charset binary? format-options] :as options} (impl/extract-options args)
        common-options (dissoc options :formats :format-options)
        encoders (for [format (or formats default-formats)
                       :when format
                       :let [encoder (if (map? format)
                                       format
                                       (init-encoder (get format-encoders (keyword format)) (get format-options (keyword format))))]
                       :when encoder]
                   encoder)]
    (wrap-format-response handler
                          (assoc common-options
                                 :encoders encoders
                                 :binary? binary?))))
