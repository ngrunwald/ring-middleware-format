(ns ring.middleware.format
  (:require [ring.middleware
             [format-params :as par]
             [format-response :as res]]))

(defn wrap-restful-format
  [handler & {:keys [formats]
              :as options}]
  (-> handler
      ))