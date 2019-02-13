(ns ring.middleware.format-params.guess-charset
  (:require [clojure.string :as str])
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
        (if (Charset/forName encoding)
          encoding)))
    (catch Exception _ nil)))
