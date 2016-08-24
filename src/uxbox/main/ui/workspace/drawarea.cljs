;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.drawarea
  "Draw interaction and component."
  (:require [beicon.core :as rx]
            [uxbox.util.rstore :as rs]
            [uxbox.util.mixins :as mx :include-macros true]
            [uxbox.main.constants :as c]
            [uxbox.main.data.workspace :as udw]
            [uxbox.main.data.shapes :as uds]
            [uxbox.main.ui.shapes :as shapes]
            [uxbox.main.ui.workspace.base :as wb]
            [uxbox.main.ui.workspace.rlocks :as rlocks]
            [uxbox.main.geom :as geom]
            [uxbox.util.geom.point :as gpt]
            [uxbox.util.geom.path :as path]
            [uxbox.util.dom :as dom]))

;; --- State

(defonce drawing-shape (atom nil))
(defonce drawing-position (atom nil))

(def ^:private canvas-coords
  (gpt/point c/canvas-start-x
             c/canvas-start-y))

;; --- Draw Area (Component)

(declare watch-draw-actions)
(declare on-init-draw)

(defn- watch-draw-actions
  []
  (let [stream (->> (rx/map first rlocks/stream)
                    (rx/filter #(= % :ui/draw)))]
    (rx/subscribe stream on-init-draw)))

(defn- draw-area-will-mount
  [own]
  (assoc own ::sub (watch-draw-actions)))

(defn- draw-area-will-unmount
  [own]
  (.close (::sub own))
  (dissoc own ::sub))

(mx/defc draw-area
  {:will-mount draw-area-will-mount
   :will-unmount draw-area-will-unmount
   :mixins [mx/static mx/reactive]}
  [own]
  (let [shape (mx/react drawing-shape)
        position (mx/react drawing-position)]
    (when shape
      (if position
        (-> (assoc shape :drawing? true)
            (geom/resize position)
            (shapes/render-component))
        (-> (assoc shape :drawing? true)
            (shapes/render-component))))))

;; --- Drawing Initialization

(declare on-init-draw-icon)
(declare on-init-draw-path)
(declare on-init-draw-free-path)
(declare on-init-draw-generic)

(defn- on-init-draw
  "Function execution when draw shape operation is requested.
  This is a entry point for the draw interaction."
  []
  (when-let [shape (:drawing @wb/workspace-ref)]
    (case (:type shape)
      :icon (on-init-draw-icon shape)
      :path (if (:free shape)
              (on-init-draw-free-path shape)
              (on-init-draw-path shape))
      (on-init-draw-generic shape))))

;; --- Icon Drawing

(defn- on-init-draw-icon
  [shape]
  (let [{:keys [x y]} (gpt/divide @wb/mouse-canvas-a @wb/zoom-ref)
        props {:x1 x :y1 y :x2 (+ x 100) :y2 (+ y 100)}
        shape (geom/setup shape props)]
    (rs/emit! (uds/add-shape shape)
              (udw/select-for-drawing nil)
              (uds/select-first-shape))
    (rlocks/release! :ui/draw)))

;; --- Path Drawing

(def ^:private immanted-zones
  (let [transform #(vector (- % 7) (+ % 7) %)]
    (concat
     (mapv transform (range 0 181 15))
     (mapv (comp transform -) (range 0 181 15)))))

(defn- align-position
  [angle pos]
  (reduce (fn [pos [a1 a2 v]]
            (if (< a1 angle a2)
              (reduced (gpt/update-angle pos v))
              pos))
          pos
          immanted-zones))

(defn- on-init-draw-path
  [shape]
  (let [mouse (->> (rx/sample 10 wb/mouse-viewport-s)
                   (rx/mapcat (fn [point]
                                (if @wb/alignment-ref
                                  (uds/align-point point)
                                  (rx/of point))))
                   (rx/map #(gpt/subtract % canvas-coords)))
        stoper (->> wb/events-s
                    (rx/map first)
                    (rx/filter #(= % :mouse/double-click))
                    (rx/take 1))
        firstpos (rx/take 1 mouse)
        stream (->> (rx/take-until stoper mouse)
                    (rx/skip-while #(nil? @drawing-shape))
                    (rx/with-latest-from vector wb/mouse-ctrl-s))
        ptstream (->> (rx/take-until stoper wb/events-s)
                      (rx/map first)
                      (rx/filter #(= % :mouse/click))
                      (rx/with-latest-from vector mouse)
                      (rx/map second))
        counter (atom 0)]
    (letfn [(append-point [{:keys [type] :as shape} point]
              (let [point (gpt/point point)]
                (update shape :points conj point)))

            (update-point [{:keys [type] :as shape} point index]
              (let [point (gpt/point point)
                    points (:points shape)]
                (if (= (count points) index)
                  (append-point shape point)
                  (assoc-in shape [:points index] point))))

            (normalize-shape [{:keys [points] :as shape}]
              (let [minx (apply min (map :x points))
                    miny (apply min (map :y points))
                    maxx (apply max (map :x points))
                    maxy (apply max (map :y points))

                    ;; dx (- 0 minx)
                    ;; dy (- 0 miny)
                    ;; points (mapv #(gpt/add % [dx dy]) points)
                    width (- maxx minx)
                    height (- maxy miny)]

                (assoc shape
                       ;; :x1 minx
                       ;; :y1 miny
                       ;; :x2 maxx
                       ;; :y2 maxy
                       ;; :view-box [0 0 width height]
                       :points points)))

            (on-first-point [point]
              (let [shape (append-point shape point)]
                (swap! counter inc)
                (reset! drawing-shape shape)))

            (on-click [point]
              (let [shape (append-point @drawing-shape point)]
                (swap! counter inc)
                (reset! drawing-shape shape)))

            (on-assisted-draw [point]
              (let [center (get-in @drawing-shape [:points (dec @counter)])
                    point (as-> point $
                            (gpt/subtract $ center)
                            (align-position (gpt/angle $) $)
                            (gpt/add $ center))]
                (->> (update-point @drawing-shape point @counter)
                     (reset! drawing-shape))))

            (on-free-draw [point]
              (->> (update-point @drawing-shape point @counter)
                   (reset! drawing-shape)))

            (on-draw [[point ctrl?]]
              (if ctrl?
                (on-assisted-draw point)
                (on-free-draw point)))

            (on-end []
              (let [shape (normalize-shape @drawing-shape)]
                (rs/emit! (uds/add-shape shape)
                          (udw/select-for-drawing nil)
                          (uds/select-first-shape))
                (reset! drawing-shape nil)
                (reset! drawing-position nil)
                (rlocks/release! :ui/draw)))]

      (rx/subscribe firstpos on-first-point)
      (rx/subscribe ptstream on-click)
      (rx/subscribe stream on-draw nil on-end))))

(defn- on-init-draw-free-path
  [shape]
  (let [mouse (->> (rx/sample 10 wb/mouse-viewport-s)
                   (rx/mapcat (fn [point]
                                (if @wb/alignment-ref
                                  (uds/align-point point)
                                  (rx/of point))))
                   (rx/map #(gpt/subtract % canvas-coords)))
        stoper (->> wb/events-s
                    (rx/map first)
                    (rx/filter #(= % :mouse/up))
                    (rx/take 1))
        stream (rx/take-until stoper mouse)]
    (letfn [(normalize-shape [{:keys [points] :as shape}]
              (let [minx (apply min (map :x points))
                    miny (apply min (map :y points))
                    maxx (apply max (map :x points))
                    maxy (apply max (map :y points))

                    ;; dx (- 0 minx)
                    ;; dy (- 0 miny)
                    ;; _ (println "Initial number of points:" (count points))
                    ;; points (mapv #(gpt/add % [dx dy]) points)
                    points (path/simplify points 0.1)
                    ;; _ (println "Final number of points:" (count points))
                    width (- maxx minx)
                    height (- maxy miny)]

                (assoc shape
                       :x1 minx
                       :y1 miny
                       :x2 maxx
                       :y2 maxy
                       :view-box [0 0 width height]
                       :points points)))

            (on-draw [point]
              (let [point (gpt/point point)
                    shape (-> (or @drawing-shape shape)
                              (update :points conj point))]
                (reset! drawing-shape shape)))

            (on-end []
              (let [shape (normalize-shape @drawing-shape)]
                (rs/emit! (uds/add-shape shape)
                          (udw/select-for-drawing nil)
                          (uds/select-first-shape))
                (reset! drawing-shape nil)
                (reset! drawing-position nil)
                (rlocks/release! :ui/draw)))]

      (rx/subscribe stream on-draw nil on-end))))

(defn- on-init-draw-generic
  [shape]
  (let [mouse (->> wb/mouse-viewport-s
                   (rx/mapcat (fn [point]
                                (if @wb/alignment-ref
                                  (uds/align-point point)
                                  (rx/of point))))
                   (rx/map #(gpt/subtract % canvas-coords)))

        stoper (->> wb/events-s
                    (rx/map first)
                    (rx/filter #(= % :mouse/up))
                    (rx/take 1))
        firstpos (rx/take 1 mouse)
        stream (->> (rx/take-until stoper mouse)
                    (rx/skip-while #(nil? @drawing-shape))
                    (rx/with-latest-from vector wb/mouse-ctrl-s))]

    (letfn [(on-start [{:keys [x y] :as pt}]
              (let [shape (geom/setup shape {:x1 x :y1 y :x2 x :y2 y})]
                (reset! drawing-shape shape)))

            (on-draw [[pt ctrl?]]
              (let [pt (gpt/divide pt @wb/zoom-ref)]
                (reset! drawing-position (assoc pt :lock ctrl?))))
            (on-end []
              (let [shape @drawing-shape
                    shpos @drawing-position
                    shape (geom/resize shape shpos)]
                (rs/emit! (uds/add-shape shape)
                          (udw/select-for-drawing nil)
                          (uds/select-first-shape))
                (reset! drawing-position nil)
                (reset! drawing-shape nil)
                (rlocks/release! :ui/draw)))]
      (rx/subscribe firstpos on-start)
      (rx/subscribe stream on-draw nil on-end))))
