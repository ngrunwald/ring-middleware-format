(defproject ring-middleware-format "0.7.4-SNAPSHOT"
  :description "Ring middleware for parsing parameters and emitting
  responses in various formats (mainly JSON, YAML and Transit out of
  the box)"
  :url "https://github.com/ngrunwald/ring-middleware-format"
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :dependencies [[org.clojure/core.memoize "0.7.1"]
                 [ring/ring-core "1.7.1"]
                 [cheshire "5.8.1"]
                 [org.clojure/tools.reader "1.3.2"]
                 [clj-commons/clj-yaml "0.6.0"]
                 [clojure-msgpack "1.2.1"]
                 [com.cognitect/transit-clj "0.8.313"]]
  :plugins [[lein-codox "0.10.2"]]
  :codox {:src-uri "http://github.com/ngrunwald/ring-middleware-format/blob/master/{filepath}#L{line}"
          :defaults {:doc/format :markdown}}
  ;; Include :icu profile for all tasks, except if with-profile is used without "+"
  :profiles {:default [:base :system :user :provided :dev :icu]
             ;; leaky metadata to include this dep in pom.xml
             :icu ^:leaky {:dependencies [[com.ibm.icu/icu4j "63.1"]]}
             :dev {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :1.6 {:dependencies [[org.clojure/clojure "1.6.0"]]}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.0"]]}}
  :aliases {"all" ["with-profile" "default:+1.6:+1.7:+1.8:+1.10:-icu"]})
