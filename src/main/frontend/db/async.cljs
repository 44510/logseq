(ns frontend.db.async
  "Async queries"
  (:require [promesa.core :as p]
            [frontend.state :as state]
            [frontend.config :as config]
            [clojure.string :as string]
            [logseq.common.util.page-ref :as page-ref]
            [frontend.util :as util]
            [frontend.db.utils :as db-utils]
            [frontend.db.async.util :as db-async-util]
            [frontend.db.file-based.async :as file-async]
            [frontend.db :as db]
            [frontend.db.model :as db-model]
            [frontend.persist-db.browser :as db-browser]
            [clojure.edn :as edn]
            [datascript.core :as d]
            [frontend.db.react :as react]
            [frontend.date :as date]
            [cljs-time.core :as t]
            [cljs-time.format :as tf]))

(def <q db-async-util/<q)
(def <pull db-async-util/<pull)
(comment
  (def <pull-many db-async-util/<pull-many))

(defn <get-files
  [graph]
  (p/let [result (<q
                  graph
                  '[:find ?path ?modified-at
                    :where
                    [?file :file/path ?path]
                    [(get-else $ ?file :file/last-modified-at 0) ?modified-at]])]
    (->> result seq reverse)))

(defn <get-all-templates
  [graph]
  (p/let [result (<q graph
                     '[:find ?t (pull ?b [*])
                       :where
                       [?b :block/properties ?p]
                       [(get ?p :template) ?t]])]
    (into {} result)))

(defn <get-template-by-name
  [name]
  (let [repo (state/get-current-repo)]
    (p/let [templates (<get-all-templates repo)]
      (get templates name))))

(defn <db-based-get-all-properties
  ":block/type could be one of [property, class]."
  [graph]
  (<q graph
      '[:find [?n ...]
        :where
        [?e :block/type "property"]
        [?e :block/original-name ?n]]))

(defn <get-all-properties
  "Returns a seq of property name strings"
  []
  (when-let [graph (state/get-current-repo)]
    (if (config/db-based-graph? graph)
      (<db-based-get-all-properties graph)
      (file-async/<file-based-get-all-properties graph))))

(comment
  (defn <get-pages
    [graph]
    (p/let [result (<q graph
                       '[:find [?page-original-name ...]
                         :where
                         [?page :block/name ?page-name]
                         [(get-else $ ?page :block/original-name ?page-name) ?page-original-name]])]
      (remove db-model/hidden-page? result))))

(defn <get-db-based-property-values
  [graph property]
  (let [property-name (if (keyword? property)
                        (name property)
                        (util/page-name-sanity-lc property))]
    (p/let [result (<q graph
                       '[:find ?prop-type ?v
                         :in $ ?prop-name
                         :where
                         [?b :block/properties ?bp]
                         [?prop-b :block/name ?prop-name]
                         [?prop-b :block/uuid ?prop-uuid]
                         [?prop-b :block/schema ?prop-schema]
                         [(get ?prop-schema :type) ?prop-type]
                         [(get ?bp ?prop-uuid) ?v]]
                       property-name)]
      (->> result
           (map (fn [[prop-type v]] [prop-type (if (coll? v) v [v])]))
           (mapcat (fn [[prop-type vals]]
                     (case prop-type
                       :default
                       ;; Remove multi-block properties as there isn't a supported approach to query them yet
                       (map str (remove uuid? vals))
                       (:page :date)
                       (map #(page-ref/->page-ref (:block/original-name (db-utils/entity graph [:block/uuid %])))
                            vals)
                       :number
                       vals
                       ;; Checkboxes returned as strings as builder doesn't display boolean values correctly
                       (map str vals))))
           ;; Remove blanks as they match on everything
           (remove string/blank?)
           (distinct)
           (sort)))))

(defn <get-property-values
  [graph property]
  (if (config/db-based-graph? graph)
    (<get-db-based-property-values graph property)
    (file-async/<get-file-based-property-values graph property)))

;; TODO: batch queries for better performance and UX
(defn <get-block
  [graph name-or-uuid & {:keys [children?]
                         :or {children? true}}]
  (let [name' (str name-or-uuid)
        e (cond
            (number? name-or-uuid)
            (db/entity name-or-uuid)
            (util/uuid-string? name')
            (db/entity [:block/uuid (uuid name')])
            :else
            (db/entity [:block/name (util/page-name-sanity-lc name')]))]
    (if (:block.temp/fully-loaded? e)
      e
      (when-let [^Object sqlite @db-browser/*worker]
        (state/update-state! :db/async-queries (fn [s] (conj s name')))
        (p/let [result (.get-block-and-children sqlite graph name' children?)
                {:keys [block children] :as result'} (edn/read-string result)
                conn (db/get-db graph false)
                block-and-children (cons block children)
                _ (d/transact! conn block-and-children)]
          (state/update-state! :db/async-queries (fn [s] (disj s name')))
          (react/refresh-affected-queries!
           graph
           [[:frontend.worker.react/block (:db/id block)]])
          (if children?
            block
            result'))))))

(defn <get-right-sibling
  [graph db-id]
  (assert (integer? db-id))
  (when-let [^Object worker @db-browser/*worker]
    (p/let [result-str (.get-right-sibling worker graph db-id)
            result (edn/read-string result-str)
            conn (db/get-db graph false)
            _ (when result (d/transact! conn [result]))]
      result)))

(defn <get-block-parents
  [graph id depth]
  (assert (integer? id))
  (when-let [^Object worker @db-browser/*worker]
    (when-let [block-id (:block/uuid (db/entity graph id))]
      (state/update-state! :db/async-queries (fn [s] (conj s (str block-id "-parents"))))
      (p/let [result-str (.get-block-parents worker graph id depth)
              result (edn/read-string result-str)
              conn (db/get-db graph false)
              _ (d/transact! conn result)]
        (state/update-state! :db/async-queries (fn [s] (disj s (str block-id "-parents"))))
        result))))

(defn <get-block-refs
  [graph eid]
  (assert (integer? eid))
  (when-let [^Object worker @db-browser/*worker]
    (state/update-state! :db/async-queries (fn [s] (conj s (str eid "-refs"))))
    (p/let [result-str (.get-block-refs worker graph eid)
            result (edn/read-string result-str)
            conn (db/get-db graph false)
            _ (d/transact! conn result)]
      (state/update-state! :db/async-queries (fn [s] (disj s (str eid "-refs"))))
      result)))

(defn <get-block-refs-count
  [graph eid]
  (assert (integer? eid))
  (when-let [^Object worker @db-browser/*worker]
    (.get-block-refs-count worker graph eid)))

(defn <get-all-referenced-blocks-uuid
  "Get all uuids of blocks with any back link exists."
  [graph]
  (<q graph
      '[:find [?refed-uuid ...]
        :where
           ;; ?referee-b is block with ref towards ?refed-b
        [?refed-b   :block/uuid ?refed-uuid]
        [?referee-b :block/refs ?refed-b]]))

(defn <get-file
  [graph path]
  (when (and graph path)
    (p/let [result (<pull graph [:file/path path])]
      (:file/content result))))

(defn <get-date-scheduled-or-deadlines
  [journal-title]
  (when-let [date (date/journal-title->int journal-title)]
    (let [future-days (state/get-scheduled-future-days)
          date-format (tf/formatter "yyyyMMdd")
          current-day (tf/parse date-format (str date))
          future-day (some->> (t/plus current-day (t/days future-days))
                              (tf/unparse date-format)
                              (parse-long))]
      (when future-day
        (when-let [repo (state/get-current-repo)]
          (p/let [result (<q repo
                             '[:find [(pull ?block ?block-attrs) ...]
                               :in $ ?day ?future ?block-attrs
                               :where
                               (or
                                [?block :block/scheduled ?d]
                                [?block :block/deadline ?d])
                               [(get-else $ ?block :block/repeated? false) ?repeated]
                               [(get-else $ ?block :block/marker "NIL") ?marker]
                               [(not= ?marker "DONE")]
                               [(not= ?marker "CANCELED")]
                               [(not= ?marker "CANCELLED")]
                               [(<= ?d ?future)]
                               (or-join [?repeated ?d ?day]
                                        [(true? ?repeated)]
                                        [(>= ?d ?day)])]
                             date
                             future-day
                             db-model/block-attrs)]
            (->> result
                 db-model/sort-by-left-recursive
                 db-utils/group-by-page)))))))

(defn <fetch-all-pages
  [graph]
  (when-let [^Object worker @db-browser/*worker]
    (.fetch-all-pages worker graph)))
