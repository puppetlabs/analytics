(ns puppetlabs.analytics.web-core-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [puppetlabs.analytics.test-util :as test-util]
            [puppetlabs.analytics.checkin.test-utils :as checkin-utils]
            [puppetlabs.analytics.web-core :as web]
            [ring.mock.request :as mock]
            [ring.util.io :as ring-io]
            [clj-time.core :as time]))

(defn mock-post
  [endpoint input]
  (let [ring-app (web/app->wrapped-handler "some-analytics" (web/app nil (atom {}) {}))
        body (ring-io/string-input-stream input)
        request (->
                 (mock/request :post (str "some-analytics/commands" endpoint))
                 (assoc :body body))
        response (ring-app request)]
    response))

(deftest entrypoint
  (testing "provides the entrypoint"
    (let [ring-app (web/app->wrapped-handler "some-analytics" (web/app nil (atom {}) {}))
          response (ring-app (mock/request :get "some-analytics"))]
      (is (str/starts-with? (get-in response [:headers "Content-Type"])
                            "application/json"))
      (let [body (json/parse-string (:body response))]
        (is (= ["event" "snapshot"] (keys (get body "commands"))))
        (is (not-empty ((get body "version") "server")))))))

(deftest malformed-json
  (testing "returns 400 upon malformed JSON"
    (let [input "{: :) (: :}"
          response (mock-post "/snapshot" input)
          body (json/decode (:body response) true)]
      (is (= 400 (:status response)))
      (is (= "invalid-data" (:kind body)))
      (is (= (str/starts-with? (:msg body) "Failed to parse body as JSON"))))))

(deftest event-validation
  (testing "event returns reasonable schema errors"
    (let [input (json/generate-string {:event "something.something-happened"
                                       :extra "not allowed"})
          response (mock-post "/event" input)
          body (json/decode (:body response) true)]
      (is (= 422 (:status response)))
      (is (= "schema-violation" (:kind body)))
      (is (str/includes? (:msg body) "([\"extra\" disallowed-key])")))))

(deftest snapshot-validation
  (testing "event returns reasonable schema errors"
    (let [input (json/generate-string {:fields {"reasonable" "value"}
                                       :extra "not allowed"})
          response (mock-post "/snapshot" input)
          body (json/decode (:body response) true)]
      (is (= 422 (:status response)))
      (is (= "schema-violation" (:kind body)))
      (is (str/includes? (:msg body) "([\"extra\" disallowed-key])")))))

(deftest store-event
  (testing "when posting an event the event is stored"
    (test-util/with-queue queue
      (let [whitelist (atom {"here.something-said" {"datatype" "event", "description" "thing"}})
            handler (web/app->wrapped-handler "" (web/app queue whitelist {}))
            event (json/encode {:event "here.something-said"
                                :metadata {"good" "no"}})
            event-request (assoc (mock/request :post "/commands/event")
                                 :body (ring-io/string-input-stream event))
            event-response (handler event-request)
            collection-request (mock/request :get "/collections/events")
            collection-response (handler collection-request)]
        (is (= 200 (:status event-response)))
        (is (= 200 (:status collection-response)))
        (is (= {"event" "here.something-said"
                "metadata" {"good" "no"}}
               (-> collection-response
                   :body
                   json/decode
                   first
                   (select-keys ["event" "metadata"]))))))))

(deftest store-snapshot
  (testing "when posting a snapshot the snapshot is stored"
    (test-util/with-queue queue
      (time/do-at (time/date-time 1111)
        (let [whitelist (atom {"there.a" {"datatype" "string", "description" ""}
                           "there.c" {"datatype" "string", "description" ""}})
              handler (web/app->wrapped-handler "" (web/app queue whitelist {}))
              snapshot (json/encode {:fields {"there.a" "b" "there.c" "d"}})
              snapshot-request (assoc (mock/request :post "/commands/snapshot")
                                      :body (ring-io/string-input-stream snapshot))
              snapshot-response (handler snapshot-request)
              collection-request (mock/request :get "/collections/snapshots")
              collection-response (handler collection-request)]
          (is (= {"there.a" {"value" "b", "timestamp" "1111-01-01T00:00:00.000Z"}
                  "there.c" {"value" "d", "timestamp" "1111-01-01T00:00:00.000Z"}}
                 (-> collection-response
                     :body
                     json/decode
                     first))))))))

(deftest send-command
  (testing "sends on command"
    (time/do-at (time/date-time 1111)
     (checkin-utils/with-mock-dujour
       (fn [dujour-app args]
          (let [[{:keys [telemetry-url]} result-args] (checkin-utils/add-promise dujour-app args)
                queue (:queue args)
                config {:global {:certs {:ssl-ca-cert "./dev-resources/puppetlabs/analytics/test/ca.pem"}
                                 :hostname (get args :certname)}
                        :product {:update-server-url telemetry-url
                                  :name {:artifact-id "puppetserver" :group-id "puppet"}
                                  :version-path "/fake/version/path"}
                        :analytics {:url "http://localhost:8553/analytics"}
                        :queue queue}
                whitelist (atom {"there.a" {"datatype" "string", "description" ""}
                             "there.c" {"datatype" "string", "description" ""}})
                handler (web/app->wrapped-handler "" (web/app queue whitelist config))
                snapshot (json/encode {:fields {"there.a" "b" "there.c" "d"}})
                snapshot-request (assoc (mock/request :post "/commands/snapshot")
                                        :body (ring-io/string-input-stream snapshot))
                _ (handler snapshot-request)
                send-request (mock/request :post "/commands/send")
                send-response (handler send-request)
                dujour-arguments (checkin-utils/check-promise result-args)]
            (is (= {"there.a" {"value" "b", "timestamp" "1111-01-01T00:00:00.000Z"}
                    "there.c" {"value" "d", "timestamp" "1111-01-01T00:00:00.000Z"}}
                   (get-in dujour-arguments ["self-service-analytics" "snapshots"])))
            (is (= 200 (:status send-response)))
            (is (= "success"
                   (:status (json/parse-string (:body send-response) true))))))))))
