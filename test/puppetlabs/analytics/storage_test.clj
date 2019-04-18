(ns puppetlabs.analytics.storage-test
  (:require [clj-time.core :as time]
            [clojure.test :refer :all]
            [puppetlabs.analytics.core :as core]
            [puppetlabs.analytics.storage :as storage]
            [puppetlabs.analytics.test-util :refer [with-queue with-temp-directory]]
            [puppetlabs.analytics.internal-api :as internal-api]
            slingshot.test)
  (:import java.nio.file.Files))

;; THE TESTS

(deftest initialize-queue
  (testing "if the queue already exists open it"
    (with-temp-directory tmp-queue-dir "analytics-test"
      (let [queue (storage/init-queue (.toString tmp-queue-dir))]
        (is (= (:directory queue)
               (:directory (storage/init-queue (.toString tmp-queue-dir)))))))))

(deftest initialize-queue-root-dir
  (testing "create the directory if it doesn't exist"
    (with-temp-directory tmp-queue-dir "analytics-test"
      (let [sub-path (.resolve tmp-queue-dir "nope")
            queue (storage/init-queue (.toString sub-path))]
        (is (Files/isWritable (:directory queue)))))))

(deftest initialize-queue-errors
  (testing "throws an error if unable to initialize the queue"
    (is (thrown+-with-msg? [:kind :storage-init-failed]
                           #"Encountered an error initializing analytics storage"
                           (storage/init-queue "/dev/")))))

(deftest add-to-queue
  (testing "a message can be added to the queue and replayed"
    (with-queue queue
      (let [message (-> {"event" "hello.thing"
                         "metadata" {"time" "first"}}
                        internal-api/->internal-event)
            message-id (storage/store queue message)]
        (is (= message
               (storage/read-entry queue message-id)))))))

(deftest reduce-queue-basic
  (testing "multiple messages can be combined"
    (with-queue queue
      (let [first-message (-> {"event" "good.morning"
                               "metadata" {"weather" "cloudy"}}
                              internal-api/->internal-event)
            second-message (-> {"event" "good.afternoon"
                                "metadata" {"weather" "raining"}}
                               internal-api/->internal-event)]
        (storage/store queue first-message)
        (storage/store queue second-message)
        (is (= #{first-message second-message}
               (storage/reduce-entries queue
                                       (fn [coll entry-id]
                                         (conj coll (storage/read-entry queue entry-id)))
                                       #{})))))))

(deftest time-encoding
  (testing "can encode and decode event timestamps"
    (with-queue queue
      (let [timestamp (time/date-time 1970)
            event {"event" "timey"
                   "metadata" {"stuff" [1 2 3 4 5]}
                   "timestamp" timestamp}
            entry-id (storage/store queue event)]
        (is (time/equal? timestamp
                         (-> (storage/read-entry queue entry-id)
                             (get "timestamp"))))))))


;; TODO error conditions for init-queue
;; ensure filesystem permissions etc
