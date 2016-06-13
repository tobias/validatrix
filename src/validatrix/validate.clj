(ns validatrix.validate
  (:require [clojure.string :as str]
            [validatrix.parsing :as p]
            [clojure.zip :as z]
            spyscope.core
            [clojure.java.io :as io])
  (:import [javax.xml.validation
            SchemaFactory Schema]
           (javax.xml XMLConstants)
           (java.io File)
           (javax.xml.transform.stream StreamSource)
           (org.xml.sax ErrorHandler InputSource)
           (javax.xml.parsers SAXParserFactory)
           (org.xml.sax.helpers DefaultHandler)))

(defn content-handler [tree]
  (let [locator (atom nil)
        location (fn []
                   {:line (.getLineNumber @locator)
                    :col  (.getColumnNumber @locator)})
        last-location (atom {:line 1 :col 2})
        loc> (fn [l1 l2]
               (or
                 (> (:line l1) (:line l2))
                 (and (= (:line l1) (:line l2))
                      (> (:col l1) (:col l2)))))
        store-loc (fn [loc]
                    (when (loc> loc @last-location)
                      (reset! last-location loc)))]
    (proxy [DefaultHandler] []
      (setDocumentLocator [l] (reset! locator l))
      (startElement [_ _ q-name _]
        (swap! tree #(-> % (z/append-child
                             [{:name  q-name
                               :start @last-location}])
                         z/down z/rightmost)))
      (endElement [_ _ _]
        (let [loc (location)]
          (store-loc loc)
          (swap! tree #(-> % (z/edit assoc-in [0 :end] loc) z/up))))
      (characters [_ _ _]
        (store-loc (location)))
      (ignorableWhitespace [_ _ _]
        (store-loc (location))))))

(defn doc-location-zip [file]
  (let [reader (-> (SAXParserFactory/newInstance) .newSAXParser .getXMLReader)
        tree (atom (z/vector-zip []))]
    (.setContentHandler reader (content-handler tree))
    (with-open [stream (io/input-stream file)]
      (.parse reader (InputSource. stream)))
    (z/root @tree)))

(def schema-factory (delay (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)))

(defn err-handler [data]
  (let [store (fn [k e]
                (let [msg (.getMessage e)
                      [err-type _] (p/split-err msg)
                      ignore? (p/ignored-errors err-type)]
                  (when-not (and ignore? (re-find ignore? msg))
                    (swap! data #(update % k (fnil conj []) e)))))]
    (reify ErrorHandler
      (error [_ e] (store :error e))
      (fatalError [_ e] (store :fatal e))
      (warning [_ e] (store :warning e)))))

(defn validate [^File file schemas]
  (let [errors (atom {})]
    (doseq [^Schema schema schemas]
      (.validate (doto (.newValidator schema)
                   (.setErrorHandler (err-handler errors)))
                 (StreamSource. file)))
    @errors))

(defn lines [file]
  (str/split (slurp file) #"\n"))

(def context-lines 3)

(defn prefix-linum [offset idx-width idx line]
  (format (str "%" idx-width "s: %s") (+ offset idx) line))

(defn prefix-lines [f-lines linum idx-width]
  (let [start (- linum context-lines)
        start (if (< start 0) 0 start)]
    (str/join "\n" (map-indexed (partial prefix-linum (inc start) idx-width)
                                (subvec f-lines start (inc linum))))))

(defn postfix-lines [f-lines linum idx-width]
  (let [end (+ linum context-lines)
        line-count (count f-lines)
        end (if (> end line-count) line-count end)]
    (str/join "\n" (map-indexed (partial prefix-linum (inc linum) idx-width)
                                (subvec f-lines linum end)))))

(def divider
  (apply str (repeat 80 \-)))

(defn header [msg]
  (let [fill (apply str (repeat (int (/ (- 80 (count msg) 2) 2)) \-))]
    (format "%s %s %s" fill msg fill)))

(defn re-positions [re s]
  (loop [matcher (re-matcher re s)
         matches []]
    (if (.find matcher)
      (recur matcher (conj matches [(.start matcher) (.group matcher)]))
      matches)))

(defn rewind [to f-lines start-line end-col]
  (when to
    (let [line (nth f-lines start-line)
          line' (if (= ::end end-col)
                  line
                  (subs line 0 end-col))
          matches (re-positions to line')]
      (if (seq matches)
        {:line start-line
         :col  (first (last matches))}
        (when (> start-line 0)
          (recur to f-lines (dec start-line) ::end))))))

(comment
  (let [data ["abc" "def" "gha"]]
    (rewind #"a" data 2 1))
  )

(defn translate-error [err f-lines]
  (let [line-num (dec (.getLineNumber err))
        col-num (dec (.getColumnNumber err))
        base-msg (.getMessage err)
        [key _] (p/split-err base-msg)]
    (let [translator (or (p/translators key) p/default-translator)
          translated (translator base-msg)]
      (merge {:line line-num
              :col col-num
              :msg base-msg}
             translated
             (rewind (:rewind-to translated) f-lines line-num col-num)))))

(defn left-pad [s padding]
  (format (str "%" (+ padding (count s)) "s") s))

(defn format-error [err f-lines]
  (let [{:keys [line col msg original-msg extra-msg]} (translate-error err f-lines)
        line-index-width (count (str (+ line (* 2 context-lines))))
        padding (+ col line-index-width 2)]
    (str (header "Validation Error")
         "\n\n"
         (prefix-lines f-lines line line-index-width)
         "\n\n"
         (left-pad (str "^ " msg) padding)
         (when extra-msg (str "\n" (left-pad (str "  " extra-msg) padding)))
         "\n\n"
         (postfix-lines f-lines (inc line) line-index-width)
         "\n\n"
         "Original message:\n"
         original-msg
         "\n\n"
         divider)))

(def display-error (comp println format-error))



(comment
  (def errors (validate (io/file (io/resource "subsystem.xml"))
                        [(.newSchema @schema-factory (io/resource "wildfly-messaging-activemq_1_0.xsd"))]))
  (def f-lines (lines (io/resource "subsystem.xml")))
  (def locations (doc-location-zip (io/file (io/resource "subsystem.xml"))))
  (display-error (first (:error errors)) f-lines)
  (run! #(display-error % f-lines) (:error errors))





  )
