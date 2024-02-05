(ns frontend.components.imports
  "Import data into Logseq."
  (:require [cljs.core.async.interop :refer [p->c]]
            [clojure.core.async :as async]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [datascript.core :as d]
            [frontend.components.onboarding.setups :as setups]
            [frontend.components.repo :as repo]
            [frontend.components.svg :as svg]
            [frontend.config :as config]
            [frontend.context.i18n :refer [t]]
            [frontend.db :as db]
            [frontend.fs :as fs]
            [frontend.handler.db-based.editor :as db-editor-handler]
            [frontend.handler.import :as import-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.repo :as repo-handler]
            [frontend.handler.route :as route-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.modules.outliner.ui :as ui-outliner-tx]
            [frontend.persist-db.browser :as db-browser]
            [frontend.state :as state]
            [frontend.ui :as ui]
            [frontend.util :as util]
            [frontend.util.fs :as fs-util]
            [goog.functions :refer [debounce]]
            [goog.object :as gobj]
            [logseq.common.path :as path]
            [logseq.common.util :as common-util]
            [logseq.db :as ldb]
            [logseq.graph-parser :as graph-parser]
            [logseq.outliner.core :as outliner-core]
            [medley.core :as medley]
            [promesa.core :as p]
            [rum.core :as rum]
            [logseq.common.config :as common-config]
            [lambdaisland.glogi :as log]))

;; Can't name this component as `frontend.components.import` since shadow-cljs
;; will complain about it.

(defonce *opml-imported-pages (atom nil))

(defn- finished-cb
  []
  (route-handler/redirect-to-home!)
  (notification/show! "Import finished!" :success)
  (ui-handler/re-render-root!))

(defn- roam-import-handler
  [e]
  (let [file      (first (array-seq (.-files (.-target e))))
        file-name (gobj/get file "name")]
    (if (string/ends-with? file-name ".json")
      (do
        (state/set-state! :graph/importing :roam-json)
        (let [reader (js/FileReader.)]
          (set! (.-onload reader)
                (fn [e]
                  (let [text (.. e -target -result)]
                    (import-handler/import-from-roam-json!
                     text
                     #(do
                        (state/set-state! :graph/importing nil)
                        (finished-cb))))))
          (.readAsText reader file)))
      (notification/show! "Please choose a JSON file."
                          :error))))

(defn- lsq-import-handler
  [e & {:keys [sqlite? graph-name]}]
  (let [file      (first (array-seq (.-files (.-target e))))
        file-name (some-> (gobj/get file "name")
                          (string/lower-case))
        edn? (string/ends-with? file-name ".edn")
        json? (string/ends-with? file-name ".json")]
    (cond
      sqlite?
      (let [graph-name (string/trim graph-name)]
        (cond
          (string/blank? graph-name)
          (notification/show! "Empty graph name." :error)

          (repo-handler/graph-already-exists? graph-name)
          (notification/show! "Please specify another name as another graph with this name already exists!" :error)

          :else
          (let [reader (js/FileReader.)]
            (set! (.-onload reader)
                  (fn []
                    (let [buffer (.-result ^js reader)]
                      (import-handler/import-from-sqlite-db! buffer graph-name finished-cb)
                      (state/close-modal!))))
            (set! (.-onerror reader) (fn [e] (js/console.error e)))
            (set! (.-onabort reader) (fn [e]
                                       (prn :debug :aborted)
                                       (js/console.error e)))
            (.readAsArrayBuffer reader file))))

      (or edn? json?)
      (do
        (state/set-state! :graph/importing :logseq)
        (let [reader (js/FileReader.)
              import-f (if edn?
                         import-handler/import-from-edn!
                         import-handler/import-from-json!)]
          (set! (.-onload reader)
                (fn [e]
                  (let [text (.. e -target -result)]
                    (import-f
                     text
                     #(do
                        (state/set-state! :graph/importing nil)
                        (finished-cb))))))
          (.readAsText reader file)))

      :else
      (notification/show! "Please choose an EDN or a JSON file."
                          :error))))

(defn- opml-import-handler
  [e]
  (let [file      (first (array-seq (.-files (.-target e))))
        file-name (gobj/get file "name")]
    (if (string/ends-with? file-name ".opml")
      (do
        (state/set-state! :graph/importing :opml)
        (let [reader (js/FileReader.)]
          (set! (.-onload reader)
                (fn [e]
                  (let [text (.. e -target -result)]
                    (import-handler/import-from-opml! text
                                                      (fn [pages]
                                                        (reset! *opml-imported-pages pages)
                                                        (state/set-state! :graph/importing nil)
                                                        (finished-cb))))))
          (.readAsText reader file)))
      (notification/show! "Please choose a OPML file."
                          :error))))

(rum/defcs set-graph-name-dialog
  < rum/reactive
  (rum/local "" ::input)
  [state sqlite-input-e opts]
  (let [*input (::input state)
        on-submit #(if (repo/invalid-graph-name? @*input)
                     (repo/invalid-graph-name-warning)
                     (lsq-import-handler sqlite-input-e (assoc opts :graph-name @*input)))]
    [:div.container
     [:div.sm:flex.sm:items-start
      [:div.mt-3.text-center.sm:mt-0.sm:text-left
       [:h3#modal-headline.leading-6.font-medium
        "New graph name:"]]]

     [:input.form-input.block.w-full.sm:text-sm.sm:leading-5.my-2.mb-4
      {:auto-focus true
       :on-change (fn [e]
                    (reset! *input (util/evalue e)))
       :on-key-press (fn [e]
                       (when (= "Enter" (util/ekey e))
                         (on-submit)))}]

     [:div.mt-5.sm:mt-4.flex
      (ui/button "Submit"
                 {:on-click on-submit})]]))


(defn- import-from-doc-files!
  [db-conn repo config doc-files]
  (let [imported-chan (async/promise-chan)]
    (try
      (let [docs-chan (async/to-chan! (medley/indexed doc-files))]
        (state/set-state! [:graph/importing-state :total] (count doc-files))
        (async/go-loop []
          (if-let [[i {:keys [rpath] :as file}] (async/<! docs-chan)]
            (let [extract-options {:date-formatter (common-config/get-date-formatter config)
                                   :user-config config
                                   :filename-format (or (:file/name-format config) :legacy)
                                   :block-pattern (common-config/get-block-pattern (common-util/get-format rpath))}]
              (state/set-state! [:graph/importing-state :current-idx] (inc i))
              (state/set-state! [:graph/importing-state :current-page] rpath)
              (async/<! (async/timeout 10))
              (async/<! (p->c (-> (.text (:file-object file))
                                  (p/then (fn [content]
                                            (prn :import rpath)
                                            {:file/path rpath
                                             :file/content content}))
                                  (p/then (fn [m]
                                            ;; Write to frontend first as writing to worker first is poor ux with slow streaming changes
                                            (let [{:keys [tx-report]}
                                                  (graph-parser/import-file-to-db-graph db-conn (:file/path m) (:file/content m) {:extract-options extract-options})]
                                              (db-browser/transact! @db-browser/*worker repo (:tx-data tx-report) (:tx-meta tx-report)))
                                            m)))))
              (recur))
            (async/offer! imported-chan true))))
      (catch :default e
        (notification/show! (str "Error happens when importing:\n" e) :error)
        (async/offer! imported-chan true)))))

(defn- import-from-asset-files!
  [asset-files]
  (let [ch (async/to-chan! asset-files)
        repo (state/get-current-repo)
        repo-dir (config/get-repo-dir repo)]
    (prn :in-files asset-files)
    (async/go-loop []
      (if-let [file (async/<! ch)]
        (do
          (async/<! (p->c (-> (.arrayBuffer (:file-object file))
                              (p/then (fn [buffer]
                                        (let [content (js/Uint8Array. buffer)
                                              parent-dir (path/path-join repo-dir (path/dirname (:rpath file)))]
                                          (p/do!
                                           (fs/mkdir-if-not-exists parent-dir)
                                           (fs/write-file! repo repo-dir (:rpath file) content {:skip-transact? true}))))))))
          (recur))
        true))))

(defn- import-logseq-files
  [logseq-files]
  (let [custom-css (first (filter #(= (:rpath %) "logseq/custom.css") logseq-files))
        custom-js (first (filter #(= (:rpath %) "logseq/custom.js") logseq-files))]
    (p/do!
     (some-> (:file-object custom-css)
             (.text)
             (p/then #(db-editor-handler/save-file! "logseq/custom.css" %)))
     (some-> (:file-object custom-js)
             (.text)
             (p/then #(db-editor-handler/save-file! "logseq/custom.js" %))))))

(defn- import-config-file!
  [{:keys [file-object]}]
  (-> (.text file-object)
      (p/then (fn [content]
                (let [migrated-content (repo-handler/migrate-db-config content)]
                  (p/do!
                   (db-editor-handler/save-file! "logseq/config.edn" migrated-content))
                  ;; Return original config as import process depends on original config e.g. :hidden
                  (edn/read-string content))))
      (p/catch (fn [err]
                 (log/error :import-config-file err)
                 (notification/show! "Import may have mistakes due to an invalid config.edn. Recommend re-importing with a valid config.edn"
                                     :warning
                                     false)
                 (edn/read-string config/config-default-content)))))

(defn- build-hidden-favorites-page-blocks
  [page-block-uuid-coll]
  (map
   (fn [uuid]
     {:block/link [:block/uuid uuid]
      :block/content ""
      :block/format :markdown})
   page-block-uuid-coll))

(def hidden-favorites-page-name "$$$favorites")
(def hidden-favorites-page-tx
  {:block/uuid (d/squuid)
   :block/name hidden-favorites-page-name
   :block/original-name hidden-favorites-page-name
   :block/journal? false
   :block/type #{"hidden"}
   :block/format :markdown})

(defn- import-favorites-from-config-edn!
  [db-conn repo config-file]
  (let [now (inst-ms (js/Date.))]
    (p/do!
     (ldb/transact! repo [(assoc hidden-favorites-page-tx
                                 :block/created-at now
                                 :block/updated-at now)])
     (p/let [content (when config-file (.text (:file-object config-file)))]
       (when-let [content-edn (try (edn/read-string content)
                                   (catch :default _ nil))]
         (when-let [favorites (seq (:favorites content-edn))]
           (when-let [page-block-uuid-coll
                      (seq
                       (keep (fn [page-name]
                               (some-> (d/entity @db-conn [:block/name (common-util/page-name-sanity-lc page-name)])
                                       :block/uuid))
                             favorites))]
             (let [page-entity (d/entity @db-conn [:block/name hidden-favorites-page-name])]
               (ui-outliner-tx/transact!
                {:outliner-op :insert-blocks}
                (outliner-core/insert-blocks! repo db-conn (build-hidden-favorites-page-blocks page-block-uuid-coll)
                                              page-entity {}))))))))))


(rum/defc confirm-graph-name-dialog
  [initial-name on-graph-name-confirmed]
  (let [[input set-input!] (rum/use-state initial-name)
        on-submit #(do (on-graph-name-confirmed input)
                       (state/close-modal!))]
    [:div.container
     [:div.sm:flex.sm:items-start
      [:div.mt-3.text-center.sm:mt-0.sm:text-left
       [:h3#modal-headline.leading-6.font-medium
        "New graph name:"]]]

     [:input.form-input.block.w-full.sm:text-sm.sm:leading-5.my-2.mb-4
      {:auto-focus true
       :default-value input
       :on-change (fn [e]
                    (set-input! (util/evalue e)))
       :on-key-down (fn [e]
                      (when (= "Enter" (util/ekey e))
                        (on-submit)))}]

     [:div.mt-5.sm:mt-4.flex
      (ui/button "Confirm"
                 {:on-click on-submit})]]))

(defn- import-file-graph
  [*files graph-name config-file]
  (state/set-state! :graph/importing :folder)
  (state/set-state! [:graph/importing-state :current-page] (str graph-name " Assets"))
  (async/go
    (async/<! (p->c (repo-handler/new-db! graph-name {:file-graph-import? true})))
    (let [repo (state/get-current-repo)
          db-conn (db/get-db repo false)
          config (async/<! (p->c (import-config-file! config-file)))
          files (common-config/remove-hidden-files *files config :rpath)
          doc-files (filter #(contains? #{"md" "org" "markdown" "edn"} (path/file-ext (:rpath %))) files)
          asset-files (filter #(string/starts-with? (:rpath %) "assets/") files)]
      (async/<! (p->c (import-logseq-files (filter #(string/starts-with? (:rpath %) "logseq/") files))))
      (async/<! (import-from-asset-files! asset-files))
      (async/<! (import-from-doc-files! db-conn repo config doc-files))
      (async/<! (p->c (import-favorites-from-config-edn! db-conn repo config-file)))
      (state/set-state! :graph/importing nil)
      (state/set-state! :graph/importing-state nil)
      (when-let [org-files (seq (filter #(= "org" (path/file-ext (:rpath %))) files))]
        (log/info :org-files (mapv :rpath org-files))
        (notification/show! (str "Imported " (count org-files) " org file(s) as markdown. Support for org files will be added later")
                            :info false))
      (finished-cb))))

(defn graph-folder-to-db-import-handler
  "Import from a graph folder as a DB-based graph.

- Page name, journal name creation"
  [ev _opts]
  (let [^js file-objs (array-seq (.-files (.-target ev)))
        original-graph-name (string/replace (.-webkitRelativePath (first file-objs)) #"/.*" "")
        import-graph-fn (fn [graph-name]
                          (let [files (->> file-objs
                                           (map #(hash-map :file-object %
                                                           :rpath (path/trim-dir-prefix original-graph-name (.-webkitRelativePath %))))
                                           (remove #(and (not (string/starts-with? (:rpath %) "assets/"))
                                                         ;; TODO: Update this when supporting more formats as this aggressively excludes most formats
                                                         (fs-util/ignored-path? original-graph-name (.-webkitRelativePath (:file-object %))))))]
                            (if-let [config-file (first (filter #(= (:rpath %) "logseq/config.edn") files))]
                              (import-file-graph files graph-name config-file)
                              (notification/show! "Import failed as the file 'logseq/config.edn' was not found for a Logseq graph."
                                                  :error))))]
    (state/set-modal!
     #(confirm-graph-name-dialog original-graph-name
                                 (fn [graph-name]
                                   (cond
                                     (repo/invalid-graph-name? graph-name)
                                     (repo/invalid-graph-name-warning)

                                     (string/blank? graph-name)
                                     (notification/show! "Empty graph name." :error)

                                     (repo-handler/graph-already-exists? graph-name)
                                     (notification/show! "Please specify another name as another graph with this name already exists!" :error)

                                     :else
                                     (import-graph-fn graph-name)))))))


  (rum/defc importer < rum/reactive
    [{:keys [query-params]}]
    (if (state/sub :graph/importing)
      (let [{:keys [total current-idx current-page]} (state/sub :graph/importing-state)
            left-label [:div.flex.flex-row.font-bold
                        (t :importing)
                        [:div.hidden.md:flex.flex-row
                         [:span.mr-1 ": "]
                         [:div.text-ellipsis-wrapper {:style {:max-width 300}}
                          current-page]]]
            width (js/Math.round (* (.toFixed (/ current-idx total) 2) 100))
            process (when (and total current-idx)
                      (str current-idx "/" total))]
        (ui/progress-bar-with-label width left-label process))
      (setups/setups-container
       :importer
       [:article.flex.flex-col.items-center.importer.py-16.px-8
        [:section.c.text-center
         [:h1 (t :on-boarding/importing-title)]
         [:h2 (t :on-boarding/importing-desc)]]
        [:section.d.md:flex.flex-col
         [:label.action-input.flex.items-center.mx-2.my-2
          [:span.as-flex-center [:i (svg/logo 28)]]
          [:span.flex.flex-col
           [[:strong "SQLite"]
            [:small (t :on-boarding/importing-sqlite-desc)]]]
          [:input.absolute.hidden
           {:id        "import-sqlite-db"
            :type      "file"
            :on-change (fn [e]
                         (state/set-modal!
                          #(set-graph-name-dialog e {:sqlite? true})))}]]

         (when (or util/electron? util/web-platform?)
          [:label.action-input.flex.items-center.mx-2.my-2
           [:span.as-flex-center [:i (svg/logo 28)]]
           [:span.flex.flex-col
            [[:strong "File to DB graph"]
             [:small  "Import a file-based Logseq graph folder into a new DB graph"]]]
           [:input.absolute.hidden
            {:id        "import-graph-folder"
             :type      "file"
             :webkitdirectory "true"
             :on-change (debounce (fn [e]
                                    (graph-folder-to-db-import-handler e {}))
                                  1000)}]])

         [:label.action-input.flex.items-center.mx-2.my-2
          [:span.as-flex-center [:i (svg/logo 28)]]
          [:span.flex.flex-col
           [[:strong "EDN / JSON"]
            [:small (t :on-boarding/importing-lsq-desc)]]]
          [:input.absolute.hidden
           {:id        "import-lsq"
            :type      "file"
            :on-change lsq-import-handler}]]

         [:label.action-input.flex.items-center.mx-2.my-2
          [:span.as-flex-center [:i (svg/roam-research 28)]]
          [:div.flex.flex-col
           [[:strong "RoamResearch"]
            [:small (t :on-boarding/importing-roam-desc)]]]
          [:input.absolute.hidden
           {:id        "import-roam"
            :type      "file"
            :on-change roam-import-handler}]]

         [:label.action-input.flex.items-center.mx-2.my-2
          [:span.as-flex-center.ml-1 (ui/icon "sitemap" {:size 26})]
          [:span.flex.flex-col
           [[:strong "OPML"]
            [:small (t :on-boarding/importing-opml-desc)]]]

          [:input.absolute.hidden
           {:id        "import-opml"
            :type      "file"
            :on-change opml-import-handler}]]]

        (when (= "picker" (:from query-params))
          [:section.e
           [:a.button {:on-click #(route-handler/redirect-to-home!)} "Skip"]])])))
