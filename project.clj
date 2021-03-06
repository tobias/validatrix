(defproject validatrix "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.apache.ws.xmlschema/xmlschema-walker "2.2.1"] ;; used to turn the schema into a tree we can search
                 [clemence "0.2.0"]                         ;; provides levenshtein distance calculation to detect misspellings
                 [myguidingstar/clansi "1.3.0"]]            ;; ansi color codes for colorized output
  :main validatrix.main
  :profiles {:dev {:dependencies [[spyscope "0.1.5"]]}})

