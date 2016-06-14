(ns validatrix.schema
  (:require [clojure.zip :as z]
            [clojure.java.io :as io])
  (:import (org.apache.ws.commons.schema XmlSchemaCollection)
           (org.apache.ws.commons.schema.walker XmlSchemaVisitor XmlSchemaWalker)))

(defn read-schema [f]
  (.read (XmlSchemaCollection.) (io/reader f)))

(defn visitor [tree]
  (reify
    XmlSchemaVisitor
    (onEnterElement [_ element _ visited?]
      (when-not visited?
        (swap! tree #(-> % (z/append-child [{:name (.getName element)}]) z/down z/rightmost))))
    (onExitElement [_ _ _ visited?]
      (when-not visited?
        (swap! tree z/up)))
    (onVisitAttribute [_ _ attr-info]
      (swap! tree #(z/edit % update-in [0 :attributes] (fnil conj #{})
                           (-> attr-info .getAttribute .getName))))
    (onEndAttributes [_ _ _])
    (onEnterSubstitutionGroup [_ _])
    (onExitSubstitutionGroup [_ _])
    (onEnterAllGroup [_ _])
    (onExitAllGroup [_ _])
    (onEnterChoiceGroup [_ _])
    (onExitChoiceGroup [_ _])
    (onEnterSequenceGroup [_ _])
    (onExitSequenceGroup [_ _])
    (onVisitAny [_ _])
    (onVisitAnyAttribute [_ _ _])))

(defn schema-zip [schema]
  (let [tree (atom (z/vector-zip []))]
    (doseq [root (vals (.getElements schema))]
      (.walk (XmlSchemaWalker. (.getParent schema) (visitor tree)) root))
    (z/root @tree)))

(def schema-tree (comp schema-zip read-schema))

(comment
  (def schema (read-schema (io/resource "wildfly-messaging-activemq_1_0.xsd")))
  (def walked-schema (schema-zip schema))
  (require '[validatrix.util :as u])
  (u/search-zipper #(some #{:quality-of-service} (:attributes %)) walked-schema)

  )
