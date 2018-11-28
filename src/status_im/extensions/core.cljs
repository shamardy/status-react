(ns status-im.extensions.core
  (:refer-clojure :exclude [list])
  (:require [clojure.string :as string]
            [pluto.storages :as storages]
            [pluto.reader :as reader]
            [re-frame.core :as re-frame]
            [status-im.chat.commands.core :as commands]
            [status-im.chat.commands.impl.transactions :as transactions]
            [status-im.ui.components.button.view :as button]
            [status-im.ui.components.checkbox.view :as checkbox]
            [status-im.ui.components.list.views :as list]
            [status-im.ui.components.react :as react]
            [status-im.ui.screens.wallet.settings.views :as settings]
            [status-im.i18n :as i18n]
            [status-im.utils.money :as money]
            [status-im.ui.components.colors :as colors]
            [status-im.ui.screens.navigation :as navigation]
            [status-im.utils.handlers :as handlers]
            [status-im.utils.fx :as fx]
            status-im.extensions.ethereum
            [status-im.utils.ethereum.tokens :as tokens]
            [status-im.utils.ethereum.core :as ethereum]
            [status-im.chat.commands.sending :as commands-sending]))

(re-frame/reg-fx
 ::alert
 (fn [value] (js/alert value)))

(handlers/register-handler-fx
 :alert
 (fn [_ [_ _ {:keys [value]}]]
   {::alert value}))

(re-frame/reg-fx
 ::log
 (fn [value] (js/console.log value)))

(handlers/register-handler-fx
 :log
 (fn [_ [_ _ {:keys [value]}]]
   {::log value}))

(re-frame/reg-fx
 ::schedule-start
 (fn [{:keys [interval on-created on-result]}]
   (let [id (js/setInterval #(re-frame/dispatch (on-result {})) interval)]
     (re-frame/dispatch (on-created {:value id})))))

(handlers/register-handler-fx
 :extensions/schedule-start
 (fn [_ [_ _ m]]
   {::schedule-start m}))

(re-frame/reg-fx
 ::schedule-cancel
 (fn [{:keys [value]}]
   (js/clearInterval value)))

(handlers/register-handler-fx
 :extensions/schedule-cancel
 (fn [_ [_ _ m]]
   {::schedule-cancel m}))

(re-frame/reg-sub
 :extensions/identity
 (fn [_ [_ _ {:keys [value]}]]
   value))

(re-frame/reg-sub
 :extensions.wallet/balance
 :<- [:wallet/all-tokens]
 :<- [:network]
 :<- [:balance]
 (fn [[all-tokens network balance] [_ _ {token :token}]]
   (let [{:keys [decimals]} (tokens/token-for (ethereum/network->chain-keyword network) all-tokens token)
         value (get balance (keyword token))]
     {:value        (money/token->unit value decimals)
      :value-in-wei value})))

(re-frame/reg-sub
 :extensions.wallet/token
 :<- [:wallet/all-tokens]
 :<- [:network]
 (fn [[all-tokens network] [_ _ {token :token}]]
   (tokens/token-for (ethereum/network->chain-keyword network) all-tokens token)))

(re-frame/reg-sub
 :extensions.wallet/tokens
 :<- [:wallet/all-tokens]
 :<- [:network]
 (fn [[all-tokens network] [_ _ {filter-vector :filter}]]
   (let [tokens (tokens/sorted-tokens-for all-tokens (ethereum/network->chain-keyword network))]
     (if (= :all (first filter-vector))
       tokens
       (filter #((set filter-vector) (:symbol %)) tokens)))))

(re-frame/reg-sub
 :store/get
 (fn [db [_ {id :id} {:keys [key]}]]
   (get-in db [:extensions/store id key])))

(defn- empty-value? [o]
  (cond
    (seqable? o) (empty? o)
    :else (nil? o)))

(handlers/register-handler-fx
 :store/put
 (fn [{:keys [db]} [_ {id :id} {:keys [key value]}]]
   {:db (if (empty-value? value)
          (update-in db [:extensions/store id] dissoc key)
          (assoc-in db [:extensions/store id key] value))}))

(defn- append [acc k v]
  (let [o (get acc k)]
    (assoc acc k (conj (if (vector? o) o (vector o)) v))))

(handlers/register-handler-fx
 :store/append
 (fn [{:keys [db]} [_ {id :id} {:keys [key value]}]]
   {:db (update-in db [:extensions/store id] append key value)}))

(handlers/register-handler-fx
 :store/clear
 (fn [{:keys [db]} [_ {id :id} {:keys [key]}]]
   {:db (update-in db [:extensions/store id] dissoc key)}))

(defn- json? [res]
  (when-let [type (get-in res [:headers "content-type"])]
    (string/starts-with? type "application/json")))

(defn- parse-json [o]
  (when o
    (js->clj (js/JSON.parse o) :keywordize-keys true)))

(re-frame/reg-fx
 ::json-parse
 (fn [{:keys [value on-result]}]
   (re-frame/dispatch (on-result {:value (parse-json value)}))))

(handlers/register-handler-fx
 :extensions/json-parse
 (fn [_ [_ _ m]]
   {::json-parse m}))

(re-frame/reg-fx
 ::json-stringify
 (fn [value on-result]
   (re-frame/dispatch (on-result {:value (js/JSON.stringify (clj->js value))}))))

(handlers/register-handler-fx
 :extensions/json-stringify
 (fn [_ [_ _ {:keys [value]}]]
   {::json-stringify value}))

(defn- parse-result [o on-success]
  (let [res (if (json? o) (update o :body parse-json) o)]
    (on-success res)))

(re-frame/reg-event-fx
 :http/get
 (fn [_ [_ _ {:keys [url on-success on-failure timeout]}]]
   {:http-raw-get (merge {:url url
                          :success-event-creator #(parse-result % on-success)}
                         (when on-failure
                           {:failure-event-creator on-failure})
                         (when timeout
                           {:timeout-ms timeout}))}))

(re-frame/reg-event-fx
 :ipfs/cat
 (fn [_ [_ _ {:keys [hash on-success on-failure]}]]
   {:http-raw-get (merge {:url (str "https://ipfs.infura.io/ipfs/" hash)
                          :success-event-creator
                          (fn [{:keys [status body]}]
                            (if (= 200 status)
                              (on-success {:value body})
                              (when on-failure
                                (on-failure {:value status}))))}
                         (when on-failure
                           {:failure-event-creator on-failure})
                         {:timeout-ms 5000})}))

(re-frame/reg-event-fx
 :http/post
 (fn [_ [_ _ {:keys [url body on-success on-failure timeout]}]]
   {:http-raw-post (merge {:url  url
                           :body (clj->js body)
                           :success-event-creator #(parse-result % on-success)}
                          (when on-failure
                            {:failure-event-creator on-failure})
                          (when timeout
                            {:timeout-ms timeout}))}))

(handlers/register-handler-fx
 :extensions.chat.command/set-parameter
 (fn [_ [_ _ {:keys [value]}]]
   {:dispatch [:chat.ui/set-command-parameter value]}))

(handlers/register-handler-fx
 :extensions.chat.command/set-custom-parameter
 (fn [{{:keys [current-chat-id] :as db} :db} [_ _ {:keys [key value]}]]
   {:db (assoc-in db [:chats current-chat-id :custom-params key] value)}))

(handlers/register-handler-fx
 :extensions.chat.command/set-parameter-with-custom-params
 (fn [{{:keys [current-chat-id] :as db} :db} [_ _ {:keys [value params]}]]
   {:db (update-in db [:chats current-chat-id :custom-params] merge params)
    :dispatch [:chat.ui/set-command-parameter value]}))

(handlers/register-handler-fx
 :extensions.chat.command/send-plain-text-message
 (fn [_ [_ _ {:keys [value]}]]
   {:dispatch [:chat/send-plain-text-message value]}))

(handlers/register-handler-fx
 :extensions.chat.command/send-message
 (fn [{{:keys [current-chat-id] :as db} :db :as cofx} [_ {:keys [hook-id]} {:keys [params]}]]
   (when hook-id
     (when-let [command (last (first (filter #(= (ffirst %) (name hook-id)) (:id->command db))))]
       (commands-sending/send cofx current-chat-id command params)))))

(defn operation->fn [k]
  (case k
    :plus   +
    :minus  -
    :times  *
    :divide /))

(re-frame/reg-fx
 ::arithmetic
 (fn [{:keys [operation values on-result]}]
   (re-frame/dispatch (on-result {:value (apply (operation->fn operation) values)}))))

(handlers/register-handler-fx
 :extensions/arithmetic
 (fn [_ [_ _ m]]
   {::arithmetic m}))

(defn button [{:keys [on-click enabled disabled] :as m} label]
  [button/secondary-button (merge {:disabled? (or (when (contains? m :enabled) (or (nil? enabled) (false? enabled))) disabled)}
                                  (when on-click {:on-press #(re-frame/dispatch (on-click {}))})) label])

(defn input [{:keys [keyboard-type style on-change placeholder]}]
  [react/text-input (merge {:placeholder placeholder}
                           (when style {:style style})
                           (when keyboard-type {:keyboard-type keyboard-type})
                           (when on-change
                             {:on-change-text #(re-frame/dispatch (on-change {:value %}))}))])

(defn touchable-opacity [{:keys [style on-press]} & children]
  (into [react/touchable-opacity (merge (when on-press {:on-press #(re-frame/dispatch (on-press {}))})
                                        (when style {:style style}))] children))

(defn image [{:keys [uri style]}]
  [react/image (merge {:style (merge {:width 100 :height 100} style)} {:source {:uri uri}})])

(defn link [{:keys [uri]}]
  [react/text
   {:style    {:color                colors/white
               :text-decoration-line :underline}
    :on-press #(re-frame/dispatch [:browser.ui/message-link-pressed uri])}
   uri])

(defn list [{:keys [key data item-view]}]
  [list/flat-list {:data data :key-fn (or key (fn [_ i] (str i))) :render-fn item-view}])

(defn checkbox [{:keys [on-change checked]}]
  [react/view {:style {:background-color colors/white}}
   [checkbox/checkbox {:checked?        checked
                       :style           {:padding 0}
                       :on-value-change #(re-frame/dispatch (on-change {:value %}))}]])

(defn activity-indicator-size [k]
  (condp = k
    :small "small"
    :large "large"
    nil))

(defn activity-indicator [{:keys [animating hides-when-stopped color size]}]
  [react/activity-indicator (merge (when animating {:animating animating})
                                   (when hides-when-stopped {:hidesWhenStopped hides-when-stopped})
                                   (when color {:color color})
                                   (when-let [size' (activity-indicator-size size)] {:size size'}))])

(defn picker [{:keys [style on-change selected enabled data]}]
  [react/picker {:style style :on-change #(re-frame/dispatch (on-change {:value %})) :selected selected :enabled enabled :data data}])

(defn text [o & children]
  (if (map? o)
    [react/text o children]
    (into [react/text {} o] children)))

(defn- wrap-view-child [child]
  (if (vector? child) child [text {} child]))

(defn view [o & children]
  (if (map? o)
    (into [react/view o] (map wrap-view-child children))
    (into [react/view {} (wrap-view-child o)] (map wrap-view-child children))))

(def capacities
  {:components {'view               {:value view}
                'text               {:value text}
                'touchable-opacity  {:value touchable-opacity :properties {:on-press :event}}
                'image              {:value image :properties {:uri :string}}
                'input              {:value input :properties {:on-change :event :placeholder :string :keyboard-type :string}}
                'button             {:value button :properties {:enabled :boolean :disabled :boolean :on-click :event}}
                'link               {:value link :properties {:uri :string}}
                'list               {:value list :properties {:data :vector :item-view :view :key? :keyword}}
                'checkbox           {:value checkbox :properties {:on-change :event :checked :boolean}}
                'activity-indicator {:value activity-indicator :properties {:animating :boolean :color :string :size :keyword :hides-when-stopped :boolean}}
                'picker             {:value picker :properties {:on-change :event :selected :string :enabled :boolean :data :vector}}
                'nft-token-viewer   {:value transactions/nft-token :properties {:token :string}}
                'transaction-status {:value transactions/transaction-status :properties {:outgoing :string :tx-hash :string}}}
   :queries    {'identity            {:value :extensions/identity :arguments {:value :map}}
                'store/get           {:value :store/get :arguments {:key :string}}
                'wallet/collectibles {:value :get-collectible-token :arguments {:token :string :symbol :string}}
                'wallet/balance      {:value :extensions.wallet/balance :arguments {:token :string}}
                'wallet/token        {:value :extensions.wallet/token :arguments {:token :string}}
                'wallet/tokens       {:value :extensions.wallet/tokens :arguments {:filter :vector}}}
   :events     {'alert
                {:permissions [:read]
                 :value       :alert
                 :arguments   {:value :string}}
                'chat.command/set-parameter
                {:permissions [:read]
                 :value       :extensions.chat.command/set-parameter
                 :arguments   {:value :any}}
                'chat.command/set-custom-parameter
                {:permissions [:read]
                 :value       :extensions.chat.command/set-custom-parameter
                 :arguments   {:key :keyword :value :any}}
                'chat.command/set-parameter-with-custom-params
                {:permissions [:read]
                 :value       :extensions.chat.command/set-parameter-with-custom-params
                 :arguments   {:value :string :params :map}}
                'chat.command/send-plain-text-message
                {:permissions [:read]
                 :value       :extensions.chat.command/send-plain-text-message
                 :arguments   {:value :string}}
                'chat.command/send-message
                {:permissions [:read]
                 :value       :extensions.chat.command/send-message
                 :arguments   {:params :map}}
                'log
                {:permissions [:read]
                 :value       :log
                 :arguments   {:value :string}}
                'arithmetic
                {:permissions [:read]
                 :value       :extensions/arithmetic
                 :arguments   {:values    #{:plus :minus :times :divide}
                               :operation :keyword
                               :on-result :event}}
                'schedule/start
                {:permissions [:read]
                 :value       :extensions/schedule-start
                 :arguments   {:interval   :number
                               :on-created :event
                               :on-result  :event}}
                'schedule/cancel
                {:permissions [:read]
                 :value       :extensions/schedule-cancel
                 :arguments   {:value      :number}}
                'json/parse
                {:permissions [:read]
                 :value       :extensions/json-parse
                 :arguments   {:value     :string
                               :on-result :event}}
                'json/stringify
                {:permissions [:read]
                 :value       :extensions/json-stringify
                 :arguments   {:value     :string
                               :on-result :event}}
                'store/put
                {:permissions [:read]
                 :value       :store/put
                 :arguments   {:key :string :value :any}}
                'store/append
                {:permissions [:read]
                 :value       :store/append
                 :arguments   {:key :string :value :any}}
                'store/clear
                {:permissions [:read]
                 :value       :store/clear
                 :arguments   {:key :string}}
                'http/get
                {:permissions [:read]
                 :value       :http/get
                 :arguments   {:url         :string
                               :timeout?    :string
                               :on-success  :event
                               :on-failure? :event}}
                'http/post
                {:permissions [:read]
                 :value       :http/post
                 :arguments   {:url         :string
                               :body        :string
                               :timeout?    :string
                               :on-success  :event
                               :on-failure? :event}}
                'ipfs/cat
                {:permissions [:read]
                 :value       :ipfs/cat
                 :arguments   {:hash        :string
                               :on-success  :event
                               :on-failure? :event}}
                'ethereum/transaction-receipt
                {:permissions [:read]
                 :value       :extensions/ethereum-transaction-receipt
                 :arguments   {:value     :string
                               :on-result :event}}
                'ethereum/send-transaction
                {:permissions [:read]
                 :value       :extensions/ethereum-send-transaction
                 :arguments   {:to         :string
                               :gas?       :string
                               :gas-price? :string
                               :value?     :string
                               :method?    :string
                               :params?    :vector
                               :nonce?     :string
                               :on-result :event}}
                'ethereum/logs
                {:permissions [:read]
                 :value       :extensions/ethereum-logs
                 :arguments   {:fromBlock? :string
                               :toBlock?   :string
                               :address?   :vector
                               :topics?    :vector
                               :blockhash? :string
                               :on-result :event}}
                'ethereum/call
                {:permissions [:read]
                 :value       :extensions/ethereum-call
                 :arguments   {:to         :string
                               :method     :string
                               :params?    :vector
                               :outputs?   :vector
                               :on-result  :event}}}
   :hooks      {:chat.command    commands/command-hook
                :wallet.settings settings/hook}})

(defn parse [{:keys [data]} id]
  (try
    (reader/parse {:capacities capacities :env {:id id}} data)
    (catch :default e {:errors [{:value (str e)}]})))

(defn parse-extension [{:keys [type value]} id]
  (if (= type :success)
    (parse (reader/read (:content value)) id)
    {:errors [{:type type :value value}]}))

(def uri-prefix "https://get.status.im/extension/")

(defn valid-uri? [s]
  (boolean
   (when s
     (re-matches (re-pattern (str "^" uri-prefix "\\w+@.+")) (string/trim s)))))

(defn url->uri [s]
  (when s
    (string/replace s uri-prefix "")))

(defn load-from [url f]
  (when-let [uri (url->uri url)]
    (storages/fetch uri f)))

(fx/defn set-extension-url-from-qr
  [cofx url]
  (fx/merge (assoc-in cofx [:db :extensions/manage :url] {:value url
                                                          :error false})
            (navigation/navigate-back)))

(fx/defn set-input
  [{:keys [db]} input-key value]
  {:db (update db :extensions/manage assoc input-key {:value value})})

(fx/defn fetch [cofx ext-key]
  (get-in cofx [:db :account/account :extensions ext-key]))

(fx/defn edit
  [cofx extension-key]
  (let [{:keys [url]} (fetch cofx extension-key)]
    (fx/merge (set-input cofx :url (str url))
              (navigation/navigate-to-cofx :edit-extension nil))))
