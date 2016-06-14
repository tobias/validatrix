(ns validatrix.main
  (:require [validatrix.validate :as v]
            [clojure.java.io :as io])
  (:gen-class))

(defn -main [file schema]
  (v/validate (io/file file) (io/file schema) {:ignore-var-values? true}))
