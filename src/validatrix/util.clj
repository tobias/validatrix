(ns validatrix.util
  (:require [clojure.zip :as z]
            [clemence.core :as cl]))

(defn search-tree
  "Walks the tree, applying (pred node), returning a seq of [node path] entries, one for
  each node where pred returns logical true. Returns nil if no results found."
  [pred tree]
  (loop [zipper (z/vector-zip tree)
         res []]
    (if (z/end? zipper)
      (when (seq res) res)
      (recur (z/next zipper)
             (if (pred (z/node zipper))
               (conj res [(z/node zipper) (z/path zipper)])
               res)))))

(defn alternate-spelling [word possibilities]
  (->> (cl/levenshtein (cl/build-trie possibilities) word 5)
       (sort-by last)
       ffirst))
