;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.icon-measures
  (:require [lentes.core :as l]
            [potok.core :as ptk]
            [uxbox.builtins.icons :as i]
            [uxbox.util.i18n :refer [tr]]
            [uxbox.util.router :as r]
            [uxbox.main.store :as st]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.geom :as geom]
            [rumext.core :as mx :include-macros true]
            [uxbox.util.dom :as dom]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.data :refer [parse-int parse-float read-string]]
            [uxbox.util.math :refer [precision-or-0]]))

(mx/defc icon-measures-menu
  {:mixins [mx/static]}
  [menu shape]
  (letfn [(on-size-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)
                  props {attr value}]
              (st/emit! (uds/update-dimensions sid props))))
          (on-rotation-change [event]
            (let [value (dom/event->value event)
                  value (parse-int value 0)
                  sid (:id shape)]
              (st/emit! (uds/update-rotation sid value))))
          (on-pos-change [attr event]
            (let [value (dom/event->value event)
                  value (parse-int value nil)
                  sid (:id shape)
                  point (gpt/point {attr value})]
              (st/emit! (uds/update-position sid point))))
          (on-proportion-lock-change [event]
            (if (:proportion-lock shape)
              (st/emit! (uds/unlock-proportions (:id shape)))
              (st/emit! (uds/lock-proportions (:id shape)))))]
    (let [size (geom/size shape)]
      [:div.element-set {:key (str (:id menu))}
       [:div.element-set-title (:name menu)]
       [:div.element-set-content
        ;; SLIDEBAR FOR ROTATION AND OPACITY
        [:span "Size"]
        [:div.row-flex
         [:div.input-element.pixels
          [:input.input-text
           {:placeholder "Width"
            :type "number"
            :min "0"
            :value (precision-or-0 (:width size) 2)
            :on-change (partial on-size-change :width)}]]
         [:div.lock-size
          {:class (when (:proportion-lock shape) "selected")
           :on-click on-proportion-lock-change}
          (if (:proportion-lock shape) i/lock i/unlock)]
         [:div.input-element.pixels
          [:input.input-text
           {:placeholder "Height"
            :type "number"
            :min "0"
            :value (precision-or-0 (:height size) 2)
            :on-change (partial on-size-change :height)}]]]

        [:span "Position"]
        [:div.row-flex
         [:div.input-element.pixels
          [:input.input-text
           {:placeholder "X"
            :type "number"
            :value (precision-or-0 (:x1 shape 0) 2)
            :on-change (partial on-pos-change :x)}]]
         [:div.input-element.pixels
          [:input.input-text
           {:placeholder "Y"
            :type "number"
            :value (precision-or-0 (:y1 shape 0) 2)
            :on-change (partial on-pos-change :y)}]]]

        [:span "Rotation"]
        [:div.row-flex
         [:input.slidebar
          {:type "range"
           :min 0
           :max 360
           :value (:rotation shape 0)
           :on-change on-rotation-change}]]

        [:div.row-flex
         [:div.input-element.degrees
          [:input.input-text
           {:placeholder ""
            :type "number"
            :min 0
            :max 360
            :value (precision-or-0 (:rotation shape 0) 2)
            :on-change on-rotation-change
            }]]
         [:input.input-text
          {:style {:visibility "hidden"}}]
         ]]])))
