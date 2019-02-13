(ns ring.middleware.format-params.guess-charset
  "Add dependency on com.ibm.icu/icu4j to your project to use
  this namespace."
  (:require [clojure.string :as str]
            [ring.middleware.format-params :refer [get-charset]])
  (:import [com.ibm.icu.text CharsetDetector]
           [java.nio.charset Charset]))

(set! *warn-on-reflection* true)

(defn ^:no-doc guess-charset
  [{:keys [#^bytes body]}]
  (try
    (let [detector (CharsetDetector.)]
      (.enableInputFilter detector true)
      (.setText detector body)
      (let [m (.detect detector)
            encoding (.getName m)]
        (if (try (Charset/forName encoding)
                 true
                 (catch java.nio.charset.UnsupportedCharsetException _
                   false))
          encoding)))
    (catch Exception _ nil)))

(defn get-or-guess-charset
  "Tries to get the request encoding from the header or guess
  it if not given in *Content-Type*. Defaults to *utf-8*"
  [req]
  (or
   (get-charset req)
   (guess-charset req)
   "utf-8"))
