(ns validatrix.translate
  (:require [clojure.string :as str]
            [validatrix.util :as u]
            spyscope.core))

(def attribute-match "(?<=(^|\\s+)){:attribute}\\s*=")

;; FIXME: this will fail with whitespace on either side of the =
(def attribute-value-match "(?<=(^|\\s+){:attribute}=[\"']){:value}[\"']")

(def element-match "(?<=<){:element}[\\s/>]")

(def element-name-end-match "(?<=<{:element})[\\s/>]")

(def value-match "(?<=[\"']){:value}[\"']")

(defn extract-message [msg re mappings]
  (-> (when-let [m (re-find re msg)]
        (zipmap mappings (rest m)))
      (dissoc :_)
      (assoc :original-msg msg)))

(defn apply-template [tmpl m process-fn]
  (str/replace tmpl #"\{(.*?)\}" #(-> % second read-string m str process-fn)))

(defn apply-templates
  ([m keys]
    (apply-templates m keys identity))
  ([m keys process-fn]
   (into {} (map (fn [[k v]]
                   [k (if (some #{k} keys)
                        (apply-template v m process-fn)
                        v)]) m))))

(let [chars "(){}*&^%$#!\""
      esc-map (zipmap chars
                      (map (partial format "\\%s") chars))]

  (defn escape-regex-chars
    [s]
    (->> s
         (replace esc-map)
         (apply str))))

(defn basic-translate [context msg spec]
  (let [[re & mappings] (:parse spec)]
    (-> (extract-message msg re mappings)
        (assoc :_context context)
        (merge (select-keys spec [:msg :rewind-to]))
        (apply-templates [:msg])
        (apply-templates [:rewind-to] escape-regex-chars)
        (update :rewind-to re-pattern))))

(defn parse-options [m]
  (update m :options
          (fn [s]
            (->> (str/split s #", ")
                (map #(last (str/split % #":")))))))

(def single-quote (partial format "'%s'"))

(defn split-err [msg]
  (if-let [res (re-find #"^([^\s]*?): (.*)" msg)]
    (rest res)
    [nil msg]))

(defmulti translate (fn [_ msg] (-> msg split-err first)))

(defmethod translate :default
  [_ msg]
  {:msg (-> msg split-err last)
   :original-msg msg})

;; cvc-attribute.3: The value ''{2}'' of attribute ''{1}'' on element ''{0}'' is not valid with respect to its type, ''{3}''.
(defmethod translate "cvc-attribute.3"
  [context msg]
  (basic-translate
    context msg
    {:parse     [#"value '(.*?)' of attribute '(.*?)' on element '(.*?)'.*type, '(.*?)'"
                 :value :attribute :element :type]
     :msg       "This should be a {:type}"
     :rewind-to attribute-value-match}))

;; cvc-complex-type.2.4.a: Invalid content was found starting with element ''{0}''. One of ''{1}'' is expected.
(defmethod translate "cvc-complex-type.2.4.a"
  [context msg]
  (-> (basic-translate
        context msg
        {:parse     [#"element '(.*?)'\. One of '\{(.*?)\}'"
                     :element :options]
         :msg       "Element '{:element}' doesn't belong here"
         :rewind-to element-match})
      parse-options
      (as-> res
            (assoc res
              :extra-msg
              (if-let [alt (u/alternate-spelling (:element res) (:options res))]
                (format "Did you mean '%s'?" alt)
                (format "Valid options are: %s"
                        (str/join ", " (map single-quote (:options res)))))))))

;; cvc-complex-type.3.2.2: Attribute ''{1}'' is not allowed to appear in element ''{0}''.
(defmethod translate "cvc-complex-type.3.2.2"
  [context msg]
  (-> (basic-translate
        context msg
        {:parse     [#" Attribute '(.*?)' .* element '(.*?)'"
                     :attribute :element]
         :msg       "'{:attribute}' isn't an allowed attribute for the '{:element}' element"
         :rewind-to attribute-match})
      (as-> res
            (if-let [els (u/search-tree #(some #{(:attribute res)} (:attributes %)) (-> res :_context :schema))]
              (assoc res :extra-msg (format "'%s' is allowed on elements: %s\nDid you intend to put it on one of them?"
                                            (:attribute res)
                                            (str/join ", " (map (comp single-quote :name first) els))))
              (if-let [alt (u/alternate-spelling (:attribute res)
                                                 (-> (u/search-tree #(= (:element res) (:name %)) (-> res :_context :schema))
                                                     ffirst
                                                     :attributes))]
                (assoc res :extra-msg (format "Did you mean '%s'?" alt))
                res)))))

;; cvc-enumeration-valid: Value ''{0}'' is not facet-valid with respect to enumeration ''{1}''. It must be a value from the enumeration.
(defmethod translate "cvc-enumeration-valid"
  [context msg]
  (basic-translate
    context msg
    {:parse     [#"Value '(.*?)'.*enumeration '\[(.*?)\]'"
                 :value :options]
     :msg       "Best guess, but this should probably be one of: {:options}"
     :rewind-to value-match}))

;; cvc-complex-type.4: Attribute ''{1}'' must appear on element ''{0}''.
(defmethod translate "cvc-complex-type.4"
  [context msg]
  (basic-translate
    context msg
    {:parse     [#"Attribute '(.*?)'.*element '(.*?)'"
                 :attribute :element]
     :msg       "You need a '{:attribute}' attribute here"
     :rewind-to element-name-end-match}))

(defmulti ignore-error? (comp first split-err))

(defmethod ignore-error? :default                   [_] false)
;; will also trigger a cvc-attribute.3, which is more useful
(defmethod ignore-error? "cvc-datatype-valid.1.2.1" [_] true)
;; synthesized types should trigger other errors as well
(defmethod ignore-error? "cvc-attribute.3"          [msg] (re-find #"'#AnonType" msg))

(comment

  ;;cvc-attribute.4: The value ''{2}'' of attribute ''{1}'' on element ''{0}'' is not valid with respect to its fixed '{'value constraint'}'. The attribute must have a value of ''{3}''.
  "cvc-attribute.4"
  ;; cvc-complex-type.2.1: Element ''{0}'' must have no character or element information item [children], because the type''s content type is empty.
  "cvc-complex-type.2.1"
  ;; cvc-complex-type.2.2: Element ''{0}'' must have no element [children], and the value must be valid.
  "cvc-complex-type.2.2"
  ;; cvc-complex-type.2.3: Element ''{0}'' cannot have character [children], because the type''s content type is element-only.
  "cvc-complex-type.2.3"

  ;; cvc-complex-type.2.4.b: The content of element ''{0}'' is not complete. One of ''{1}'' is expected.
  "cvc-complex-type.2.4.b"
  ;; cvc-complex-type.2.4.c: The matching wildcard is strict, but no declaration can be found for element ''{0}''.
  "cvc-complex-type.2.4.c"
  ;; cvc-complex-type.2.4.d: Invalid content was found starting with element ''{0}''. No child element is expected at this point.
  "cvc-complex-type.2.4.d"
  ;; cvc-complex-type.2.4.d: Invalid content was found starting with element ''{0}''. No child element ''{1}'' is expected at this point.
  "cvc-complex-type.2.4.e"
  ;; cvc-complex-type.3.1: Value ''{2}'' of attribute ''{1}'' of element ''{0}'' is not valid with respect to the corresponding attribute use. Attribute ''{1}'' has a fixed value of ''{3}''.
  "cvc-complex-type.3.1"
  ;; cvc-complex-type.3.2.1: Element ''{0}'' does not have an attribute wildcard for attribute ''{1}''.
  "cvc-complex-type.3.2.1"


  ;; cvc-complex-type.5.1: In element ''{0}'', attribute ''{1}'' is a Wild ID. But there is already a Wild ID ''{2}''. There can be only one.
  "cvc-complex-type.5.1"
  ;; cvc-complex-type.5.2: In element ''{0}'', attribute ''{1}'' is a Wild ID. But there is already an attribute ''{2}'' derived from ID among the '{'attribute uses'}'.
  "cvc-complex-type.5.2"
  ;; cvc-datatype-valid.1.2.1: ''{0}'' is not a valid value for ''{1}''.
  "cvc-datatype-valid.1.2.2"
  ;; cvc-datatype-valid.1.2.3: ''{0}'' is not a valid value of union type ''{1}''.
  "cvc-datatype-valid.1.2.3"
  ;;  cvc-elt.1: Cannot find the declaration of element ''{0}''.
  "cvc-elt.1"
  ;; cvc-elt.2: The value of '{'abstract'}' in the element declaration for ''{0}'' must be false.
  "cvc-elt.2"
  ;; cvc-elt.3.1: Attribute ''{1}'' must not appear on element ''{0}'', because the '{'nillable'}' property of ''{0}'' is false.
  "cvc-elt.3.1"
  ;; cvc-elt.3.2.1: Element ''{0}'' cannot have character or element information [children], because ''{1}'' is specified.
  "cvc-elt.3.2.1"
  ;; cvc-elt.3.2.2: There must be no fixed '{'value constraint'}' for element ''{0}'', because ''{1}'' is specified.
  "cvc-elt.3.2.2"
  ;; cvc-elt.4.1: The value ''{2}'' of attribute ''{1}'' of element ''{0}'' is not a valid QName.
  "cvc-elt.4.1"
  ;; cvc-elt.4.2: Cannot resolve ''{1}'' to a type definition for element ''{0}''.
  "cvc-elt.4.2"
  ;; cvc-elt.4.3: Type ''{1}'' is not validly derived from the type definition, ''{2}'', of element ''{0}''.
  "cvc-elt.4.3"
  ;; cvc-elt.5.1.1: '{'value constraint'}' ''{2}'' of element ''{0}'' is not a valid default value for type ''{1}''.
  "cvc-elt.5.1.1"
  ;; cvc-elt.5.2.2.1: Element ''{0}'' must have no element information item [children].
  "cvc-elt.5.2.2.1"
  ;; cvc-elt.5.2.2.2.1: The value ''{1}'' of element ''{0}'' does not match the fixed '{'value constraint'}' value ''{2}''.
  "cvc-elt.5.2.2.2.1"
  ;; cvc-elt.5.2.2.2.2: The value ''{1}'' of element ''{0}'' does not match the '{'value constraint'}' value ''{2}''.
  "cvc-elt.5.2.2.2.2"

  ;; cvc-fractionDigits-valid: Value ''{0}'' has {1} fraction digits, but the number of fraction digits has been limited to {2}.
  "cvc-fractionDigits-valid"
  ;; cvc-id.1: There is no ID/IDREF binding for IDREF ''{0}''.
  "cvc-id.1"
  ;; cvc-id.2: There are multiple occurrences of ID value ''{0}''.
  "cvc-id.2"
  ;; cvc-id.3: A field of identity constraint ''{0}'' matched element ''{1}'', but this element does not have a simple type.
  "cvc-id.3"
  ;; cvc-length-valid: Value ''{0}'' with length = ''{1}'' is not facet-valid with respect to length ''{2}'' for type ''{3}''.
  "cvc-length-valid"
  ;; cvc-maxExclusive-valid: Value ''{0}'' is not facet-valid with respect to maxExclusive ''{1}'' for type ''{2}''.
  "cvc-maxExclusive-valid"
  ;; cvc-maxInclusive-valid: Value ''{0}'' is not facet-valid with respect to maxInclusive ''{1}'' for type ''{2}''.
  "cvc-maxInclusive-valid"
  ;; cvc-maxLength-valid: Value ''{0}'' with length = ''{1}'' is not facet-valid with respect to maxLength ''{2}'' for type ''{3}''.
  "cvc-maxLength-valid"
  ;; cvc-minExclusive-valid: Value ''{0}'' is not facet-valid with respect to minExclusive ''{1}'' for type ''{2}''.
  "cvc-minExclusive-valid"
  ;; cvc-minInclusive-valid: Value ''{0}'' is not facet-valid with respect to minInclusive ''{1}'' for type ''{2}''.
  "cvc-minInclusive-valid"
  ;; cvc-minLength-valid: Value ''{0}'' with length = ''{1}'' is not facet-valid with respect to minLength ''{2}'' for type ''{3}''.
  "cvc-minLength-valid"
  ;; cvc-pattern-valid: Value ''{0}'' is not facet-valid with respect to pattern ''{1}'' for type ''{2}''.
  "cvc-pattern-valid"
  ;; cvc-totalDigits-valid: Value ''{0}'' has {1} total digits, but the number of total digits has been limited to {2}.
  "cvc-totalDigits-valid"
  ;; cvc-type.2: The type definition cannot be abstract for element {0}.
  "cvc-type.2"
  ;; cvc-type.3.1.1: Element ''{0}'' is a simple type, so it cannot have attributes, excepting those whose namespace name is identical to ''http://www.w3.org/2001/XMLSchema-instance'' and whose [local name] is one of ''type'', ''nil'', ''schemaLocation'' or ''noNamespaceSchemaLocation''. However, the attribute, ''{1}'' was found.
  "cvc-type.3.1.1"
  ;; cvc-type.3.1.2: Element ''{0}'' is a simple type, so it must have no element information item [children].
  "cvc-type.3.1.2"
  ;; cvc-type.3.1.3: The value ''{1}'' of element ''{0}'' is not valid.
  "cvc-type.3.1.3")


