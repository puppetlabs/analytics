(ns puppetlabs.analytics.checkin.analytics-service-core-test
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.analytics.checkin.core :as checkin]
            [puppetlabs.analytics.checkin.test-utils :refer [with-mock-dujour add-promise check-promise]]
            [puppetlabs.analytics.internal-api :as internal-api]
            [puppetlabs.analytics.storage :as storage]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services.scheduler.scheduler-service
             :refer
             [scheduler-service]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer :all]
            [puppetlabs.trapperkeeper.testutils.bootstrap
             :refer
             [with-app-with-config]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [schema.test :as schema-test]
            [clojure.string :as str])
  (:import java.io.File))

(use-fixtures :once schema-test/validate-schemas)

(deftest opt-out-reason
  (testing "opt-out file exists"
    (let [opt-out-file (ks/temp-file "opt-out")]
      (is (= (str opt-out-file " exists") (checkin/get-opt-out-reason opt-out-file)))))

  (testing "everything is in order"
    (let [facter-path (ks/temp-file "facter")
          _ (fs/chmod "+x" facter-path)]
      (is (= nil (checkin/get-opt-out-reason nil))))))

(deftest checkin
  (testing "will not check in if opt-out-reason is set"
    (let [opt-out-file (ks/temp-file "opt-out")]
      (is (= (str opt-out-file " exists") (checkin/get-opt-out-reason opt-out-file)))
      (with-mock-dujour
       (fn [app args]
         (let [[args _] (add-promise app args)      ; Only done to fill URLs.
               queue (storage/init-queue (.getPath (ks/temp-dir "queue")))
               args (assoc args :analytics-opt-out-path opt-out-file
                                :version-path (File. "/fake/version/path")
                                :product-name "puppet"
                                :queue queue)]
           (with-test-logging
            (checkin/checkin args)
            (is (logged? #"Not sending analytics data"))))))))
  (testing "will not check in if product-name is missing"
    (with-mock-dujour
     (fn [app args]
       (let [[args _] (add-promise app args)      ; Only done to fill URLs.
             queue (storage/init-queue (.getPath (ks/temp-dir "queue")))
             args (assoc args :analytics-opt-out-path (File. "does/not/exist")
                              :version-path (File. "/fake/version/path")
                              :product-name nil
                              :queue queue)]
         (with-test-logging
          (checkin/checkin args)
          (is (logged? #"Not sending analytics data"))
          (is (logged? #"missing product name value")))))))

  (testing "sends the correct arguments to dujour"
    (with-mock-dujour
     (fn [app args]
       (let [[args promise] (add-promise app args)
             _ (with-test-logging (checkin/checkin args))
             params (check-promise promise)]
         (is (= "puppet" (params "product")))
         (is (= "puppetlabs.packages" (params "group")))))))

  (testing "does not throw if the server is not reachable"
    (with-mock-dujour
     (fn [app args]
       (with-test-logging
        (let [args (merge {:telemetry-url "http://does-not-exist.unreachable/dujour"} args)
              return (checkin/checkin args)]
          (is (str/starts-with? (:error return ) "does-not-exist.unreachable")))))))

  (testing "omits queued analytics when there are none"
    (with-mock-dujour
     (fn [app args]
       (let [[args promise] (add-promise app args)
             result (checkin/checkin args)
             params (check-promise promise result)]
         (is (not (contains? params "events")))
         (is (not (contains? params "snapshots")))))))

  (testing "discards entries on completion"
    (with-mock-dujour
     (fn [app args]
       (let [[args _] (add-promise app args)
             queue (:queue args)
             event {"event" "some.weird.analytic"}
             event2 {"event" "some.other.analytic"
                     "metadata" {"some.crazy.metadata" true}}
             snapshot {"fields" {"some.snapshot" 5}}]
         (storage/store queue (internal-api/->internal-event event))
         (storage/store queue (internal-api/->internal-event event2))
         (storage/store queue (internal-api/->internal-snapshot snapshot))
         (is (not (= [] (storage/reduce-entries queue conj []))))
         (checkin/checkin args))))))

(deftest create-checkin-args
  (let [cacert "secret-ca-cert"
        cacert-file (ks/temp-file "ca" ".pem")
        cacert-path (.getPath cacert-file)
        _ (spit cacert-file cacert)
        queue (storage/init-queue (.getPath (ks/temp-dir "queue")))
        config {:global {:certs {:ssl-cert "hostcert"
                                 :ssl-key "hostprivkey"
                                 :ssl-ca-cert cacert-path}
                         :hostname "certname"}
                :product {:conf-dir "/etc/puppetlabs"
                          :pe-version-path "/wrong/version/path"
                          :version-path "/fake/version/path"}
                :queue queue}
        args (checkin/create-checkin-args config)]
    (testing "it has the correct default paths"
      (is (= (File. "/etc/puppetlabs/analytics-opt-out") (:analytics-opt-out-path args)))
      (is (= (File. "/fake/version/path") (:version-path args))))
      (is (= "certname" (get-in args [:certname])))
      (is (= cacert (get-in args [:cacert])))
      (is (= nil (:product-name args)))
      (is (= "https://updates.puppetlabs.com" (:telemetry-url args)))))
