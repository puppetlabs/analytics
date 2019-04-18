(ns puppetlabs.analytics.core-test
  (:require [clj-time.coerce :as time.coerce]
            [clj-time.core :as time]
            [clojure.test :refer :all]
            [puppetlabs.analytics.core :as core]
            [puppetlabs.analytics.internal-api :as internal-api]
            [puppetlabs.analytics.storage :as storage]
            [puppetlabs.analytics.test-util :refer [with-queue]]
            [puppetlabs.kitchensink.core :as ks]
            [clj-time.coerce :as time.coerce]
            [clojure.string :as str]))

(deftest test-entrypoint
  (testing "returns the entrypoint"
    (let [result (core/entrypoint {:scheme :https
                                   :uri "/analytics"
                                   :server-name "localish"
                                   :server-port "8675309"})]
      (is (= [:commands :version] (keys result)))
      (is (= (get-in result [:commands :event :id]) "https://localish:8675309/analytics/commands/event"))
      (is (= (get-in result [:commands :snapshot :id]) "https://localish:8675309/analytics/commands/snapshot")))))

(deftest test-get-self-service-analytics
  (testing "retrieves queued analytics"
    (let [fake-now (time/date-time 1234)]
      (with-queue queue
        (time/do-at
         fake-now
         (let [serialized-now (time.coerce/to-string fake-now)
               event {"event" "some.weird.analytic"}
               event2 {"event" "some.other.analytic"
                       "metadata" {"some.crazy.metadata" true}}
               snapshot {"fields" {"some.snapshot" 5}}
               id1 (storage/store queue (internal-api/->internal-event event))
               id2 (storage/store queue (internal-api/->internal-event event2))
               id3 (storage/store queue (internal-api/->internal-snapshot snapshot))
               result (core/get-self-service-analytics queue)
               expected [{"events" #{{"event" "some.weird.analytic"
                                     "timestamp" serialized-now}
                                    {"event" "some.other.analytic"
                                     "metadata" {"some.crazy.metadata" true}
                                     "timestamp" serialized-now}}
                          "snapshots" {"some.snapshot" {"value" 5
                                                        "timestamp" serialized-now}}}
                         #{id1 id2 id3}]]
           (is (= expected
                  (-> result
                      (update-in [0 "events"] set)
                      (update 1 set)))))))))

  (testing "returns empty when no entries are available"
    (with-queue queue
      (let [result (core/get-self-service-analytics queue)]
        (is (= [{"events" [], "snapshots" {}} []] result))))))

(deftest test-partition-entries
  (testing "partitions entries"
    (with-queue queue
      (let [fake-now (time/date-time 4321)]
        (time/do-at
         fake-now
         (let [event {"event" "some.weird.analytic"}
               event2 {"event" "some.other.analytic"
                       "metadata" {"some.crazy.metadata" true}}
               snapshot {"fields" {"some.snapshot" 5}}
               id1 (storage/store queue (internal-api/->internal-event event))
               id2 (storage/store queue (internal-api/->internal-event event2))
               id3 (storage/store queue (internal-api/->internal-snapshot snapshot))
               result (core/partition-entries queue)]
           (is (= [{"events" #{{"event" "some.weird.analytic"
                               "timestamp" fake-now}
                              {"event" "some.other.analytic"
                               "metadata" {"some.crazy.metadata" true}
                               "timestamp" fake-now}}
                    "snapshots" {"some.snapshot" {"value" 5
                                                  "timestamp" fake-now}}}
                   #{id1 id2 id3}]
                  (-> result
                      (update-in [0 "events"] set)
                      (update 1 set)))))))))

  (testing "only returns entries that were sent"
    (with-queue queue
      (let [fake-now (time/date-time 4321)]
        (time/do-at
         fake-now
         (let [event {"event" "some.weird.analytic"}
               event2 {"event" "some.other.analytic"
                       "metadata" {"some.crazy.metadata" true}}
               snapshot1 {"fields" {"some.snapshot" 5}}
               snapshot2 {"fields" {"some.different.snapshot" 6}}
               id1 (storage/store queue (internal-api/->internal-event event))
               id2 (storage/store queue (internal-api/->internal-event event2))
               id3 (storage/store queue (internal-api/->internal-snapshot snapshot1))
               result (core/partition-entries queue)
               _ (storage/store queue (internal-api/->internal-snapshot snapshot2))]
           (is (= #{id1 id2 id3}
                  (-> result
                      second
                      set)))))))))

(deftest combine-events
  (testing "adds new events by just concatenating them"
    (let [base-event-1 {"event" "1", "metadata" {}, "timestamp" (time/date-time 1000)}
          base-event-2 {"event" "2", "metadata" {}, "timestamp" (time/date-time 1001)}
          new-event-1 {"event" "12345"
                       "metadata" {"whats" "up"}
                       "timestamp" (time/date-time 1908)}
          new-event-2 {"event" "54321"
                       "metadata" {"a" 1}
                       "timestamp" (time/date-time 1929)}]
      (is (= {"events" [base-event-1 base-event-2 new-event-1 new-event-2]
              "snapshots" {}}
             (core/combine-events {"events" [base-event-1 base-event-2], "snapshots" {}}
                                  [new-event-1 new-event-2]))))))

(deftest combine-snapshots
  (testing "adds new snapshots by taking the most recent of each snapshot"
    (let [base-uptime {"value" 1000, "timestamp" (time/date-time 500)}
          base-os {"value" "centos", "timestamp" (time/date-time 500)}
          base-arch {"value" "x64", "timestamp" (time/date-time 500)} ;; only in base
          new-uptime {"value" 1500, "timestamp" (time/date-time 1000)}
          new-ip {"value" "192.168.0.1", "timestamp" (time/date-time 1000)} ;; only in new
          outdated-os {"value" "debian", "timestamp" (time/date-time 200)}]
      (is (= {"events" []
              "snapshots" {"uptime" new-uptime
                           "os" base-os
                           "arch" base-arch
                           "ip" new-ip}}
             (core/combine-snapshots {"events" []
                                      "snapshots" {"uptime" base-uptime
                                                   "os" base-os
                                                   "arch" base-arch}}
                                     {"uptime" new-uptime
                                      "os" outdated-os
                                      "ip" new-ip}))))))

(deftest whitelist-snapshots
  (testing "rejects keys not in the whitelist"
    (let [whitelist {}
          snapshots {"a.b" {"value" "missing", "timestamp" (time/date-time 100)}}
          {:keys [accepted rejected]} (core/partition-snapshots-whitelist whitelist snapshots)]
      (is (= {} accepted))
      (is (= {"a.b" "Not an expected field"} rejected))))

  (testing "rejects keys of the wrong type"
    (let [whitelist {"c.d" {"datatype" "string" "description" "who knows"}}
          snapshots {"c.d" {"value" 3, "timestamp" (time/date-time 100)}}
          {:keys [accepted rejected]} (core/partition-snapshots-whitelist whitelist snapshots)]
      (is (= {} accepted))
      (is (= {"c.d" "Expected a value of type string"}
             rejected)))))
