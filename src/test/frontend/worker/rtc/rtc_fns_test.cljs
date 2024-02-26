(ns frontend.worker.rtc.rtc-fns-test
  (:require [clojure.test :as t :refer [deftest is testing]]
            [datascript.core :as d]
            [frontend.db.conn :as conn]
            [frontend.worker.rtc.core :as rtc-core]
            [frontend.worker.rtc.op-mem-layer :as op-mem-layer]
            [frontend.handler.page :as page-handler]
            [logseq.outliner.core :as outliner-core]
            [logseq.outliner.transaction :as outliner-tx]
            [frontend.state :as state]
            [frontend.test.helper :as test-helper]
            [logseq.common.config :as common-config]
            [frontend.worker.state :as worker-state]
            [frontend.worker.rtc.const :as rtc-const]
            [logseq.db :as ldb]))


(deftest filter-remote-data-by-local-unpushed-ops-test
  (testing "case1"
    (let [[uuid1 uuid2] (repeatedly (comp str random-uuid))
          affected-blocks-map
          {uuid1
           {:op :move
            :self uuid1
            :parents [uuid2]
            :left uuid2
            :content "content-str"}}
          unpushed-ops
          [["update" {:block-uuid uuid1
                      :updated-attrs {:content nil}
                      :epoch 1}]]
          r (rtc-core/filter-remote-data-by-local-unpushed-ops affected-blocks-map unpushed-ops)]
      (is (= {uuid1
              {:op :move
               :self uuid1
               :parents [uuid2]
               :left uuid2}}
             r))))
  (testing "case2"
    (let [[uuid1 uuid2] (repeatedly (comp str random-uuid))
          affected-blocks-map
          {uuid1
           {:op :update-attrs
            :self uuid1
            :parents [uuid2]
            :left uuid2
            :content "content-str"
            :created-at 123}}
          unpushed-ops
          [["update" {:block-uuid uuid1
                      :updated-attrs {:content nil}
                      :epoch 1}]]
          r (rtc-core/filter-remote-data-by-local-unpushed-ops affected-blocks-map unpushed-ops)]
      (is (= {uuid1
              {:op :update-attrs
               :self uuid1
               :parents [uuid2]
               :left uuid2
               :created-at 123}}
             r))))
  (testing "case3"
    (let [[uuid1] (repeatedly (comp str random-uuid))
          affected-blocks-map
          {uuid1
           {:op :remove
            :block-uuid uuid1}}
          unpushed-ops
          [["move" {:block-uuid uuid1 :epoch 1}]]
          r (rtc-core/filter-remote-data-by-local-unpushed-ops affected-blocks-map unpushed-ops)]
      (is (empty? r)))))


(deftest gen-remote-ops-test
  (state/set-current-repo! test-helper/test-db)
  (test-helper/reset-test-db!)
  (let [conn (conn/get-db test-helper/test-db false)
        [uuid1 uuid2 uuid3 uuid4] (repeatedly random-uuid)
        opts {:persist-op? false
              :transact-opts {:repo test-helper/test-db
                              :conn conn}}]
    (page-handler/create! "gen-remote-ops-test" {:redirect? false :create-first-block? false :uuid uuid1})
    (outliner-tx/transact!
     opts
     (outliner-core/insert-blocks!
      test-helper/test-db
      conn
      [{:block/uuid uuid2 :block/content "uuid2-block"}
       {:block/uuid uuid3 :block/content "uuid3-block"
        :block/left [:block/uuid uuid2]
        :block/parent [:block/uuid uuid1]}
       {:block/uuid uuid4 :block/content "uuid4-block"
        :block/left [:block/uuid uuid3]
        :block/parent [:block/uuid uuid1]}]
      (d/pull @conn '[*] [:block/name "gen-remote-ops-test"])
      {:sibling? true :keep-uuid? true}))

    (op-mem-layer/init-empty-ops-store! test-helper/test-db)
    (op-mem-layer/add-ops! test-helper/test-db [["move" {:block-uuid (str uuid2) :epoch 1}]
                                                ["move" {:block-uuid (str uuid4) :epoch 2}]
                                                ["move" {:block-uuid (str uuid3) :epoch 3}]
                                                ["update" {:block-uuid (str uuid4) :epoch 4}]])
    (let [_ (op-mem-layer/new-branch! test-helper/test-db)
          r1 (rtc-core/gen-block-uuid->remote-ops test-helper/test-db conn :n 1)
          _ (op-mem-layer/rollback! test-helper/test-db)
          r2 (rtc-core/gen-block-uuid->remote-ops test-helper/test-db conn :n 2)]
      (is (= {uuid2 [:move]}
             (update-vals r1 keys)))
      (is (= {uuid2 [:move]
              uuid3 [:move]
              uuid4 [:move :update]}
             (update-vals r2 keys))))
    (op-mem-layer/remove-ops-store! test-helper/test-db))
  (state/set-current-repo! nil)
  (test-helper/destroy-test-db!))


(deftest apply-remote-move-ops-test
  (state/set-current-repo! test-helper/test-db)
  (test-helper/reset-test-db!)
  (let [conn (conn/get-db test-helper/test-db false)
        repo test-helper/test-db
        opts {:persist-op? false
              :transact-opts {:repo test-helper/test-db
                              :conn conn}}
        date-formatter (common-config/get-date-formatter (worker-state/get-config test-helper/test-db))
        page-name "apply-remote-move-ops-test"
        [page-uuid
         uuid1-client uuid2-client
         uuid1-remote uuid2-remote] (repeatedly random-uuid)]
    (page-handler/create! page-name {:redirect? false :create-first-block? false :uuid page-uuid})
    (outliner-tx/transact!
     opts
     (outliner-core/insert-blocks!
      test-helper/test-db
      conn
      [{:block/uuid uuid1-client :block/content "uuid1-client"
        :block/left [:block/uuid page-uuid]
        :block/parent [:block/uuid page-uuid]}
       {:block/uuid uuid2-client :block/content "uuid2-client"
        :block/left [:block/uuid uuid1-client]
        :block/parent [:block/uuid page-uuid]}]
      (d/pull @conn '[*] [:block/name page-name])
      {:sibling? true :keep-uuid? true}))
    (testing "apply-remote-move-ops-test1"
      (let [data-from-ws {:req-id "req-id"
                          :t 1        ;; not used
                          :t-before 0 ;; not used
                          :affected-blocks
                          {uuid1-remote {:op :move
                                         :self uuid1-remote
                                         :parents [page-uuid]
                                         :left page-uuid
                                         :content "uuid1-remote"}}}
            move-ops (#'rtc-core/move-ops-map->sorted-move-ops
                      (:move-ops-map
                       (#'rtc-core/affected-blocks->diff-type-ops repo (:affected-blocks data-from-ws))))]
        (is (rtc-const/data-from-ws-validator data-from-ws))
        (rtc-core/apply-remote-move-ops repo conn date-formatter move-ops)
        (let [page-blocks (ldb/get-page-blocks @conn page-name {})]
          (is (= #{uuid1-remote uuid1-client uuid2-client} (set (map :block/uuid page-blocks))))
          (is (= page-uuid (:block/uuid (:block/left (d/entity @conn [:block/uuid uuid1-remote]))))))))

    (testing "apply-remote-move-ops-test2"
      (let [data-from-ws {:req-id "req-id"
                          :t 1 ;; not used
                          :t-before 0
                          :affected-blocks
                          {uuid2-remote {:op :move
                                         :self uuid2-remote
                                         :parents [uuid1-client]
                                         :left uuid1-client
                                         :content "uuid2-remote"}
                           uuid1-remote {:op :move
                                         :self uuid1-remote
                                         :parents [uuid2-remote]
                                         :left uuid2-remote}}}
            move-ops (#'rtc-core/move-ops-map->sorted-move-ops
                      (:move-ops-map
                       (#'rtc-core/affected-blocks->diff-type-ops repo (:affected-blocks data-from-ws))))]
        (is (rtc-const/data-from-ws-validator data-from-ws))
        (rtc-core/apply-remote-move-ops repo conn date-formatter move-ops)
        (let [page-blocks (ldb/get-page-blocks @conn page-name {})]
          (is (= #{uuid1-remote uuid2-remote uuid1-client uuid2-client} (set (map :block/uuid page-blocks))))
          (is (= uuid1-client (:block/uuid (:block/left (d/entity @conn [:block/uuid uuid2-remote])))))
          (is (= uuid2-remote (:block/uuid (:block/left (d/entity @conn [:block/uuid uuid1-remote]))))))))

    (state/set-current-repo! nil)
    (test-helper/destroy-test-db!)))


(deftest apply-remote-update-ops-test
  (state/set-current-repo! test-helper/test-db)
  (test-helper/reset-test-db!)
  (let [conn (conn/get-db test-helper/test-db false)
        repo test-helper/test-db
        opts {:persist-op? false
              :transact-opts {:repo test-helper/test-db
                              :conn conn}}
        date-formatter (common-config/get-date-formatter (worker-state/get-config test-helper/test-db))
        page-name "apply-remote-update-ops-test"
        [page-uuid
         uuid1-client uuid2-client
         uuid1-remote
         uuid1-not-exist] (repeatedly random-uuid)]
    (page-handler/create! page-name {:redirect? false :create-first-block? false :uuid page-uuid})
    (outliner-tx/transact!
     opts
     (outliner-core/insert-blocks!
      test-helper/test-db
      conn
      [{:block/uuid uuid1-client :block/content "uuid1-client"
        :block/left [:block/uuid page-uuid]
        :block/parent [:block/uuid page-uuid]}
       {:block/uuid uuid2-client :block/content "uuid2-client"
        :block/left [:block/uuid uuid1-client]
        :block/parent [:block/uuid page-uuid]}]
      (d/pull @conn '[*] [:block/name page-name])
      {:sibling? true :keep-uuid? true}))
    (testing "apply-remote-update-ops-test1"
      (let [data-from-ws {:req-id "req-id"
                          :t 1 ;; not used
                          :t-before 0
                          :affected-blocks
                          {uuid1-remote {:op :update-attrs
                                         :self uuid1-remote
                                         :parents [uuid1-client]
                                         :left uuid1-client
                                         :content "uuid2-remote"
                                         :created-at 1
                                         :link uuid1-client
                                         :type ["property"]}}}
            update-ops (vals
                        (:update-ops-map
                         (#'rtc-core/affected-blocks->diff-type-ops repo (:affected-blocks data-from-ws))))]
        (is (rtc-const/data-from-ws-validator data-from-ws))
        (rtc-core/apply-remote-update-ops repo conn date-formatter update-ops)
        (let [page-blocks (ldb/get-page-blocks @conn page-name {})]
          (is (= #{uuid1-client uuid2-client uuid1-remote} (set (map :block/uuid page-blocks))))
          (is (= [uuid1-client #{"property"}]
                 ((juxt (comp :block/uuid :block/link) :block/type) (d/entity @conn [:block/uuid uuid1-remote])))))))

    (testing "apply-remote-update-ops-test2"
      (let [data-from-ws {:req-id "req-id"
                          :t 1
                          :t-before 0
                          :affected-blocks
                          {uuid1-remote {:op :update-attrs
                                         :self uuid1-remote
                                         :parents [uuid1-client]
                                         :left uuid1-client
                                         :content "uuid2-remote"
                                         :created-at 1
                                         :link nil
                                         :type nil}}}
            update-ops (vals
                        (:update-ops-map
                         (#'rtc-core/affected-blocks->diff-type-ops repo (:affected-blocks data-from-ws))))]
        (is (rtc-const/data-from-ws-validator data-from-ws))
        (rtc-core/apply-remote-update-ops repo conn date-formatter update-ops)
        (let [page-blocks (ldb/get-page-blocks @conn page-name {})]
          (is (= #{uuid1-client uuid2-client uuid1-remote} (set (map :block/uuid page-blocks))))
          (is (= [nil nil] ((juxt :block/link :block/type) (d/entity @conn [:block/uuid uuid1-remote])))))))
    (testing "apply-remote-update-ops-test3"
      (let [data-from-ws {:req-id "req-id"
                          :t 1 :t-before 0
                          :affected-blocks
                          {uuid1-remote {:op :update-attrs
                                         :self uuid1-remote
                                         :parents [uuid2-client]
                                         :left uuid2-client
                                         :link uuid1-not-exist}}}
            update-ops (vals
                        (:update-ops-map
                         (#'rtc-core/affected-blocks->diff-type-ops repo (:affected-blocks data-from-ws))))]
        (is (rtc-const/data-from-ws-validator data-from-ws))
        (rtc-core/apply-remote-update-ops repo conn date-formatter update-ops)
        (let [page-blocks (ldb/get-page-blocks @conn page-name {})]
          (is (= #{uuid1-client uuid2-client uuid1-remote} (set (map :block/uuid page-blocks))))
          (is (= [nil nil] ((juxt :block/link :block/type) (d/entity @conn [:block/uuid uuid1-remote]))))))))

  (state/set-current-repo! nil)
  (test-helper/destroy-test-db!))

(deftest apply-remote-remove-ops-test
  (state/set-current-repo! test-helper/test-db)
  (test-helper/reset-test-db!)
  (let [conn (conn/get-db test-helper/test-db false)
        repo test-helper/test-db
        opts {:persist-op? false
              :transact-opts {:repo test-helper/test-db
                              :conn conn}}
        date-formatter (common-config/get-date-formatter (worker-state/get-config test-helper/test-db))
        page-name "apply-remote-remove-ops-test"
        [page-uuid
         uuid1-client uuid2-client
         uuid1-not-exist] (repeatedly random-uuid)]
    (page-handler/create! page-name {:redirect? false :create-first-block? false :uuid page-uuid})
    (outliner-tx/transact!
     opts
     (outliner-core/insert-blocks!
      test-helper/test-db
      conn
      [{:block/uuid uuid1-client :block/content "uuid1-client"
        :block/left [:block/uuid page-uuid]
        :block/parent [:block/uuid page-uuid]}
       {:block/uuid uuid2-client :block/content "uuid2-client"
        :block/left [:block/uuid uuid1-client]
        :block/parent [:block/uuid page-uuid]}]
      (d/pull @conn '[*] [:block/name page-name])
      {:sibling? true :keep-uuid? true}))
    (testing "apply-remote-remove-ops-test1"
      (let [data-from-ws {:req-id "req-id" :t 1 :t-before 0
                          :affected-blocks
                          {uuid1-client {:op :remove
                                         :block-uuid uuid1-not-exist}}}
            remove-ops (vals
                        (:remove-ops-map
                         (#'rtc-core/affected-blocks->diff-type-ops repo (:affected-blocks data-from-ws))))]
        (is (rtc-const/data-from-ws-validator data-from-ws))
        (rtc-core/apply-remote-remove-ops repo conn date-formatter remove-ops)
        (let [page-blocks (ldb/get-page-blocks @conn page-name {})]
          (is (= #{uuid1-client uuid2-client} (set (map :block/uuid page-blocks)))))))
    (testing "apply-remote-remove-ops-test2"
      (let [data-from-ws {:req-id "req-id" :t 1 :t-before 0
                          :affected-blocks
                          {uuid1-client {:op :remove
                                         :block-uuid uuid1-client}}}
            remove-ops (vals
                        (:remove-ops-map
                         (#'rtc-core/affected-blocks->diff-type-ops repo (:affected-blocks data-from-ws))))]
        (is (rtc-const/data-from-ws-validator data-from-ws))
        (rtc-core/apply-remote-remove-ops repo conn date-formatter remove-ops)
        (let [page-blocks (ldb/get-page-blocks @conn page-name {})]
          (is (= #{uuid2-client} (set (map :block/uuid page-blocks))))))))

  (state/set-current-repo! nil)
  (test-helper/destroy-test-db!))


(deftest apply-remote-update&remove-page-ops-test
  (state/set-current-repo! test-helper/test-db)
  (test-helper/reset-test-db!)
  (let [conn (conn/get-db test-helper/test-db false)
        repo test-helper/test-db
        date-formatter (common-config/get-date-formatter (worker-state/get-config test-helper/test-db))
        [page1-uuid] (repeatedly random-uuid)]
    (testing "apply-remote-update-page-ops-test1"
      (let [data-from-ws {:req-id "req-id" :t 1 :t-before 0
                          :affected-blocks
                          {page1-uuid {:op :update-page
                                       :self page1-uuid
                                       :page-name (str page1-uuid)
                                       :original-name (str page1-uuid)}}}
            update-page-ops (vals
                             (:update-page-ops-map
                              (#'rtc-core/affected-blocks->diff-type-ops repo (:affected-blocks data-from-ws))))]
        (is (rtc-const/data-from-ws-validator data-from-ws))
        (rtc-core/apply-remote-update-page-ops repo conn date-formatter update-page-ops)
        (is (= page1-uuid (:block/uuid (d/entity @conn [:block/uuid page1-uuid]))))))

    (testing "apply-remote-update-page-ops-test2"
      (let [data-from-ws {:req-id "req-id" :t 1 :t-before 0
                          :affected-blocks
                          {page1-uuid {:op :update-page
                                       :self page1-uuid
                                       :page-name (str page1-uuid "-rename")
                                       :original-name (str page1-uuid "-rename")}}}
            update-page-ops (vals
                             (:update-page-ops-map
                              (#'rtc-core/affected-blocks->diff-type-ops repo (:affected-blocks data-from-ws))))]
        (is (rtc-const/data-from-ws-validator data-from-ws))
        (rtc-core/apply-remote-update-page-ops repo conn date-formatter update-page-ops)
        (is (= (str page1-uuid "-rename") (:block/name (d/entity @conn [:block/uuid page1-uuid]))))))

    (testing "apply-remote-remove-page-ops-test1"
      (let [data-from-ws {:req-id "req-id" :t 1 :t-before 0
                          :affected-blocks
                          {page1-uuid {:op :remove-page
                                       :block-uuid page1-uuid}}}
            remove-page-ops (vals
                             (:remove-page-ops-map
                              (#'rtc-core/affected-blocks->diff-type-ops repo (:affected-blocks data-from-ws))))]
        (is (rtc-const/data-from-ws-validator data-from-ws))
        (rtc-core/apply-remote-remove-page-ops repo conn remove-page-ops)
        (is (nil? (d/entity @conn [:block/uuid page1-uuid]))))))

  (state/set-current-repo! nil)
  (test-helper/destroy-test-db!))


(deftest same-name-two-pages-merge-test
  (state/set-current-repo! test-helper/test-db)
  (test-helper/reset-test-db!)
  (let [conn (conn/get-db test-helper/test-db false)
        repo test-helper/test-db
        date-formatter (common-config/get-date-formatter (worker-state/get-config test-helper/test-db))
        opts {:persist-op? false
              :transact-opts {:repo test-helper/test-db
                              :conn conn}}
        page-name "same-name-page-test"
        [page1-uuid page2-uuid
         uuid1-client uuid2-client
         uuid1-remote uuid2-remote] (repeatedly random-uuid)]
    (page-handler/create! page-name {:redirect? false :create-first-block? false :uuid page1-uuid})
    (outliner-tx/transact!
     opts
     (outliner-core/insert-blocks!
      test-helper/test-db
      conn
      [{:block/uuid uuid1-client :block/content "uuid1-client"
        :block/left [:block/uuid page1-uuid]
        :block/parent [:block/uuid page1-uuid]}
       {:block/uuid uuid2-client :block/content "uuid2-client"
        :block/left [:block/uuid uuid1-client]
        :block/parent [:block/uuid page1-uuid]}]
      (d/pull @conn '[*] [:block/name page-name])
      {:sibling? true :keep-uuid? true}))
    (let [data-from-ws {:req-id "req-id" :t 1 :t-before 0
                        :affected-blocks
                        {page2-uuid {:op :update-page
                                     :self page2-uuid
                                     :page-name page-name
                                     :original-name page-name}
                         uuid1-remote {:op :move
                                       :self uuid1-remote
                                       :parents [page2-uuid]
                                       :left page2-uuid
                                       :content "uuid1-remote"}
                         uuid2-remote {:op :move
                                       :self uuid2-remote
                                       :parents [page2-uuid]
                                       :left uuid1-remote
                                       :content "uuid2-remote"}}}
          all-ops (#'rtc-core/affected-blocks->diff-type-ops repo (:affected-blocks data-from-ws))
          update-page-ops (vals (:update-page-ops-map all-ops))
          move-ops (#'rtc-core/move-ops-map->sorted-move-ops (:move-ops-map all-ops))]
      (is (rtc-const/data-from-ws-validator data-from-ws))
      (rtc-core/apply-remote-update-page-ops repo conn date-formatter update-page-ops)
      (rtc-core/apply-remote-move-ops repo conn date-formatter move-ops)
      (is (= #{uuid1-client uuid2-client uuid1-remote uuid2-remote}
             (set (map :block/uuid (ldb/get-page-blocks @conn page-name {})))))
      (is (= page2-uuid (:block/uuid (d/entity @conn [:block/name page-name]))))))

  (state/set-current-repo! nil)
  (test-helper/destroy-test-db!))
