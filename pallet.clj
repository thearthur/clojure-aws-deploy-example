;;; Pallet project configuration file

(require
 '[wordopia.groups.wordopia :refer [wordopia]])

(defproject wordopia
  :provider {:jclouds
             {:node-spec
              {:image {:os-family :ubuntu :os-version-matches "12.04"
                       :os-64-bit true}}}}

  :groups [wordopia])
