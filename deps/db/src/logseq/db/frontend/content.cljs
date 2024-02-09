(ns logseq.db.frontend.content
  "Fns to handle block content e.g. special ids"
  (:require [clojure.string :as string]
            [logseq.common.util.page-ref :as page-ref]
            [datascript.core :as d]
            [logseq.db.sqlite.util :as sqlite-util]))

(defonce page-ref-special-chars "~^")

(defn special-id->page
  "Convert special id backs to page name."
  [content refs]
  (reduce
   (fn [content ref]
     (if (:block/name ref)
       (string/replace content (str page-ref-special-chars (:block/uuid ref)) (:block/original-name ref))
       content))
   content
   refs))

(defn special-id-ref->page
  "Convert special id ref backs to page name."
  [content refs]
  (reduce
   (fn [content ref]
     (if (:block/name ref)
       (string/replace content
                       (str page-ref/left-brackets
                            page-ref-special-chars
                            (:block/uuid ref)
                            page-ref/right-brackets)
                       (:block/original-name ref))
       content))
   content
   refs))

(defn update-block-content
  "Replace `[[internal-id]]` with `[[page name]]`"
  [repo db item eid]
  (if (sqlite-util/db-based-graph? repo)
    (if-let [content (:block/content item)]
      (let [refs (:block/refs (d/entity db eid))]
        (assoc item :block/content (special-id->page content refs)))
      item)
    item))

(defn content-without-tags
  "Remove tags from content"
  [content tags]
  (->
   (reduce
    (fn [content tag]
      (-> content
          (string/replace (str "#" tag) "")
          (string/replace (str "#" page-ref/left-brackets tag page-ref/right-brackets) "")))
    content
    tags)
   (string/trim)))

(defn replace-tags-with-page-refs
  "Replace tags in content with page-ref ids"
  [content tags]
  (reduce
   (fn [content tag]
     (string/replace content
                     (str "#" (:block/original-name tag))
                     (str page-ref/left-brackets
                                ;; TODO: Use uuid when it becomes available
                                ;; page-ref-special-chars
                                ;; (:block/uuid tag)
                          (:block/original-name tag)
                          page-ref/right-brackets)))
   content
   tags))
