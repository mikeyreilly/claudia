(ns smoke.core
  (:require [smoke.util :as util]))

(defn greet-user
  "Greets a user by name."
  [name]
  (util/format-greeting name))

(defn main-entry
  []
  (greet-user "world")
  {:smoke/status :ready})
