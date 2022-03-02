(defproject ring-middleware-format "0.7.5-SNAPSHOT"
  :description "Ring middleware for parsing parameters and emitting
  responses in various formats (mainly JSON, YAML and Transit out of
  the box)"
  :url "https://github.com/ngrunwald/ring-middleware-format"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :dependencies [[org.clojure/core.memoize "1.0.257"]
                 [ring/ring-core "1.9.5"]
                 [cheshire "5.10.2"]
                 [org.clojure/tools.reader "1.3.6"]
                 [clj-commons/clj-yaml "0.7.108"]
                 [clojure-msgpack "1.2.1"]
                 [com.cognitect/transit-clj "1.0.329"]]
  :plugins [[lein-codox "0.10.2"]]
  :codox {:src-uri "http://github.com/ngrunwald/ring-middleware-format/blob/master/{filepath}#L{line}"
          :defaults {:doc/format :markdown}}
  :profiles {:icu {:dependencies [[com.ibm.icu/icu4j "70.1"]]}
             :dev {:dependencies [[org.clojure/clojure "1.10.3"]]}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}}
  :aliases {"all" ["with-profile" "default:+1.7:+1.8:+1.9:+icu"]})
