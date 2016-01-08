(ns uxbox.locales
  "A i18n foundation."
  (:require [hodgepodge.core :refer [local-storage]]
            [cuerdas.core :as str]
            [uxbox.locales.en :as locales-en]))

(defonce +locales+
  {:en locales-en/+locales+})

(defonce +locale+
  (get local-storage ::locale :en))

(deftype C [val]
  IDeref
  (-deref [o] val))

(defn c
  [x]
  (C. x))

(defn ^boolean c?
  [r]
  (instance? C r))

(defn tr
  "Translate the string."
  ([t]
   (let [default (name t)
         value (get-in +locales+ [+locale+ t] default)]
     (if (vector? value)
       (or (second value) default)
       value)))
  ([t & args]
   (let [value (get-in +locales+ [+locale+ t] (name t))
         plural (first (filter c? args))
         args (mapv #(if (c? %) @% %) args)
         value (if vector?
                 (if (= @plural 1) (first value) (second value))
                 value)]
     (apply str/format value args))))