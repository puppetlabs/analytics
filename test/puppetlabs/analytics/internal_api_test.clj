(ns puppetlabs.analytics.internal-api-test
  (:require [clojure.test :refer :all]
            [clj-time.core :as time]
            [puppetlabs.analytics.internal-api :as internal-api]))

(deftest test-convert-internal-snapshot
  (testing "converts a snapshot to an internal snapshot"
    (let [fake-now (time/date-time 1234)]
      (time/do-at
       fake-now
        (let [internal-snapshot (internal-api/->internal-snapshot
                                 {"fields" {"abc.def" 123
                                            "abc.ghi.jk" [{"cat" "dog" "ferret" "gerbil"}]}})]
          (is (= {"abc.def" {"value" 123, "timestamp" fake-now}
                  "abc.ghi.jk" {"value" [{"cat" "dog" "ferret" "gerbil"}], "timestamp" fake-now}}
                 internal-snapshot)))))))

(deftest test-convert-internal-event
  (testing "converts an event to an internal event"
    (let [internal-event (internal-api/->internal-event {"event" "abc.some.event"
                                                         "metadata" {"meta" "data"}})]
      (is (= {"event" "abc.some.event"
              "metadata" {"meta" "data"}}
             (select-keys internal-event ["event" "metadata"]))))))

(deftest test-render-event
  (testing "properly serializes date objects"
    (let [fake-now (time/date-time 1234)]
      (time/do-at fake-now
        (let [event {"event" "some.other.analytic"
                     "metadata" {"some.crazy.metadata" true}
                     "timestamp" (time/now)}
              rendered (internal-api/render-event event)]
          (is (= "1234-01-01T00:00:00.000Z" (get rendered "timestamp"))))))))

(deftest test-render-snapshot
  (testing "properly serializes date objects"
    (let [snapshot {"some.snapshot" {"value" 5
                                     "timestamp" (time/date-time 1234)}
                    "other.snapshot" {"value" [{"cat" "dog"}]
                                      "timestamp" (time/date-time 2015)}}
          rendered (internal-api/render-snapshot snapshot)
          expected {"some.snapshot" {"value" 5
                                     "timestamp" "1234-01-01T00:00:00.000Z"}
                    "other.snapshot" {"value" [{"cat" "dog"}]
                                      "timestamp" "2015-01-01T00:00:00.000Z"}}]
      (is (= expected rendered)))))

(deftest test-render-analytics
  (testing "properly serializes all date objects"
    (let [input {"snapshots" {"some.snapshot" {"value" 5
                                               "timestamp" (time/date-time 1234)}
                              "other.snapshot" {"value" [3 4 5]
                                                "timestamp" (time/date-time 2015)}}
                 "events" [{"event" "some.event"
                           "metadata" {"some.crazy.metadata" true}
                           "timestamp" (time/date-time 2011)}
                           {"event" "some.other.event"
                            "timestamp" (time/date-time 2012)}]}
          rendered (internal-api/render-analytics input)
          expected {"snapshots" {"some.snapshot" {"value" 5
                                     "timestamp" "1234-01-01T00:00:00.000Z"}
                    "other.snapshot" {"value" [3 4 5]
                                      "timestamp" "2015-01-01T00:00:00.000Z"}}
                    "events" [{"event" "some.event"
                               "metadata" {"some.crazy.metadata" true}
                               "timestamp" "2011-01-01T00:00:00.000Z"}
                              {"event" "some.other.event"
                               "timestamp" "2012-01-01T00:00:00.000Z"}]}]
      (is (= expected rendered)))))
