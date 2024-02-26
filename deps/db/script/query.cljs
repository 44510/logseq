(ns query
  "An example script that queries any db graph from the commandline e.g.

  $ yarn -s nbb-logseq script/query.cljs db-name '[:find (pull ?b [:block/name :block/content]) :where [?b :block/created-at]]'"
  (:require [datascript.core :as d]
            [clojure.edn :as edn]
            [logseq.db.sqlite.db :as sqlite-db]
            [logseq.db.frontend.rules :as rules]
            [nbb.core :as nbb]
            [clojure.string :as string]
            ["path" :as node-path]
            ["os" :as os]))

(defn- get-dir-and-db-name
  "Gets dir and db name for use with open-db!"
  [graph-dir]
  (if (string/includes? graph-dir "/")
    (let [graph-dir'
          (node-path/join (or js/process.env.ORIGINAL_PWD ".") graph-dir)]
      ((juxt node-path/dirname node-path/basename) graph-dir'))
    [(node-path/join (os/homedir) "logseq" "graphs") graph-dir]))

(defn -main [args]
  (when (< (count args) 2)
    (println "Usage: $0 GRAPH QUERY")
    (js/process.exit 1))
  (let [[graph-dir query*] args
        [dir db-name] (get-dir-and-db-name graph-dir)
        conn (sqlite-db/open-db! dir db-name)
        query (into (edn/read-string query*) [:in '$ '%]) ;; assumes no :in are in queries
        results (mapv first (d/q query @conn (rules/extract-rules rules/db-query-dsl-rules)))]
    (when ((set args) "-v") (println "DB contains" (count (d/datoms @conn :eavt)) "datoms"))
    (prn results)))

(when (= nbb/*file* (:file (meta #'-main)))
  (-main *command-line-args*))