(ns puppetlabs.analytics.service-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :refer [scheduler-service]]
            [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer :all]
            dujour.testutils
            [puppetlabs.analytics.test-util
             :refer
             [base-url
              clear-db-fixture
              with-analytics-service
              with-analytics-service-config]]
            [puppetlabs.trapperkeeper.testutils.bootstrap
             :refer
             [with-app-with-config]]
            [clojure.set :as set]))

(def dujour-config
  {:webserver {:host "localhost"
               :port 9999}
   :web-router-service {:dujour.core/dujour-service ""
                        :puppetlabs.trapperkeeper.services.status.status-service/status-service
                        {:route "/status"
                         :server "default"}}
   :database {:classname "org.postgresql.Driver"
              :subprotocol "postgresql"
              :subname (or (System/getenv "DUJOUR_DBSUBNAME")
                           "//localhost:5432/dujour_test")
              :username (or (System/getenv "DUJOUR_DBUSER")
                            "dujour_test")
              :password (or (System/getenv "DUJOUR_DBPASSWORD")
                            "dujour_test")}})

(def dujour-db
  (set/rename-keys (:database dujour-config) {:username :user}))

(def whitelist
  [{:name "a.b", :datatype "string", :description "the first string"}
   {:name "a.c", :datatype "string", :description "the second string"}
   {:name "z.q", :datatype "number", :description "some number"}])

(use-fixtures :each (clear-db-fixture dujour-db))

(deftest analytics-service-test
  (testing "returns the entrypoint"
    (with-analytics-service
      (let [resp (client/get base-url {:as :text})]
        (is (= ["commands" "version"] (keys (json/parse-string (:body resp)))))))))

(deftest whitelist-integration
  (testing "it downloads the whitelist from dujour"
    (with-app-with-config dujour dujour.testutils/dujour-services dujour-config
      ;; Put a test whitelist in the dujour database
      (jdbc/execute! dujour-db ["TRUNCATE whitelist"])
      (jdbc/insert-multi! dujour-db "whitelist" whitelist)
      (with-analytics-service-config
        {:product {:update-server-url "http://localhost:9999"
                   :version-path "/fake/version/path"
                   :name {:artifact-id "puppetserver" :group-id "puppet"}}}
        (let [snapshot {:fields {:a.b 2}}
              response (client/post (str base-url "/commands/snapshot")
                                    {:content-type :json
                                     :body (json/encode snapshot)})]
          (is (= 206 (:status response)))
          (is (= {"accepted" {}
                  "rejected" {"a.b" "Expected a value of type string"}}
                 (-> response :body json/decode)))))))

  (testing "it handles whitelisting of events"
    (with-app-with-config dujour dujour.testutils/dujour-services dujour-config
      ;; Put a test whitelist in the dujour database
      (jdbc/execute! dujour-db ["TRUNCATE whitelist"])
      ;; This should be removed when dujour is updated with an event type
      (jdbc/execute! dujour-db ["INSERT INTO whitelist_types (datatype) VALUES ('event')"])
      (jdbc/insert-multi! dujour-db "whitelist"
                          [{:name "event.one", :datatype "event", :description "the event"}])
      (with-analytics-service-config
        {:product {:update-server-url "http://localhost:9999"
                   :version-path "/fake/version/path"
                   :name {:artifact-id "puppetserver" :group-id "puppet"}}}
        (let [event {:event "event.two" :metadata {:hello "goodbye"}}
              response (client/post (str base-url "/commands/event")
                                    {:content-type :json
                                     :body (json/encode event)
                                     :throw-exceptions false})]
          (is (= 422 (:status response)))
          (is (= {"rejected" {"event.two" "Not an expected event"}}
                 (-> response :body json/decode))))))))
