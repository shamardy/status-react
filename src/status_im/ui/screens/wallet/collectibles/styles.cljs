(ns status-im.ui.screens.wallet.collectibles.styles
  (:require [status-im.ui.components.colors :as colors]
            [status-im.ui.components.styles :as styles]))

(def default-collectible
  {:padding-left     10
   :padding-vertical 20})

(def loading-indicator
  {:flex            1
   :align-items     :center
   :justify-content :center})

(def details
  {:padding-vertical 10})

(def details-text
  {:flex               1
   :flex-direction     :row
   :align-items        :center
   :padding-horizontal 16})

(def details-name
  {:text-align-vertical :center
   :margin-bottom       10})

(def details-image
  {:flex   1
   :margin 10})

(def container
  (merge
   styles/flex
   {:background-color colors/white}))
