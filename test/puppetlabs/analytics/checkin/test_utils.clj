(ns puppetlabs.analytics.checkin.test-utils
  (:require [clojure.test :refer :all]
            [puppetlabs.analytics.storage :as storage]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [cheshire.core :as json]
            [me.raynes.fs :as fs]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :refer :all]
            [ring.util.codec :as codec])
  (:import (java.io File)))

(def test-resources-path "./dev-resources/puppetlabs/analytics/test")
(def ssl-cert-path (str test-resources-path "/localhost_cert.pem"))
(def ssl-key-path (str test-resources-path "/localhost_key.pem"))
(def ssl-ca-cert-path (str test-resources-path "/ca.pem"))

(def jetty-config
  {:webserver {:port 8553
               :ssl-host "0.0.0.0"
               :ssl-port 8554
               :ssl-cert ssl-cert-path
               :ssl-key ssl-key-path
               :ssl-ca-cert ssl-ca-cert-path}})

(defn- add-dujour-handler
  "Adds a handler for dujour. This accepts a promise that will return the parameters
  sent to the dujour client, and returns a string for the URL for the endpoint that
  the handler will handle."
  [app handler-extension a-promise]
  (add-ring-handler
   (tk-app/get-service app :WebserverService)
   (fn [req]
     (let [params (json/parse-string (slurp (:body req)))
           return-message {"newer" true
                           "product" "puppet-server"
                           "link" "https://docs.puppet.com/puppet-server/9.9/release_notes.html"
                           "message" "Puppet Server 9.9.9 is now available."
                           "version" "9.9.9"}
           body (json/encode return-message)]
       (deliver a-promise params)
       {:status 200 :body body}))
   handler-extension)
  (str "http://localhost:8553" handler-extension))

(defn check-promise
  ([promise]
   (check-promise promise nil))
  ([promise result]
   (if (realized? promise)
     @promise
     (if result
       (throw (RuntimeException. "Promise not realized before returning"))
       (throw (RuntimeException. (str "Promise not realized before returning; error was " result)))))))

(defn add-promise
  "Adds a promise to the args for send-analytics, returning
  an array of the arguments including `:telemetry-url`, plus
  the promise which will contain the parameters sent to the
  dujour handler."
  [app args]
  (let [result (promise)
        telemetry-url (add-dujour-handler app "/dujour" result)
        args (merge {:telemetry-url telemetry-url} args)]
    [args result]))

(defn with-mock-dujour
  [body]
  (with-test-logging
   (with-app-with-config
    app
    [jetty9-service]
    jetty-config
    (let [s (tk-app/get-service app :WebserverService)
          add-ring-handler (partial add-ring-handler s)
          queue (storage/init-queue (.getPath (ks/temp-dir "queue")))
          args {:analytics-opt-out-path (File. "/also/does/not/exist")
                :version-path (File. "/fake/version/path")
                :cacert "my-ca-cert"
                :certname "localhost"
                :product-name "puppet"
                :queue queue}]
      (body app args)))))
