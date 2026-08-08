(ns smoke.util
  (:require [clojure.string :as str]))

(defn format-greeting
  [who]
  (str/join " " ["hello" who]))
