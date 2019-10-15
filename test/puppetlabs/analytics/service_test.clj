(ns puppetlabs.analytics.service-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer [jetty9-service]]
            [puppetlabs.trapperkeeper.services.webrouting.webrouting-service :refer [webrouting-service]]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service :refer [scheduler-service]]
            [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.test :refer :all]
            [puppetlabs.analytics.test-util
             :refer
             [base-url
              with-analytics-service
              with-analytics-service-config]]
            [puppetlabs.trapperkeeper.testutils.bootstrap
             :refer
             [with-app-with-config]]
            [clojure.set :as set]))

(deftest analytics-service-test
  (testing "returns the entrypoint"
    (with-analytics-service
      (let [resp (client/get base-url {:as :text})]
        (is (= ["commands" "version"] (keys (json/parse-string (:body resp)))))))))
