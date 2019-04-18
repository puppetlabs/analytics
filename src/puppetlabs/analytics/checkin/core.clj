(ns puppetlabs.analytics.checkin.core
  (:require [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [me.raynes.fs :as fs]
            [puppetlabs.dujour.version-check :as version-check]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as schema]
            [slingshot.slingshot :refer [try+]]
            [puppetlabs.i18n.core :refer [trs]]
            [puppetlabs.config.typesafe :as hocon]
            [puppetlabs.analytics.core :as core])
  (:import java.io.File
           (puppetlabs.stockpile.queue Stockpile)))

(defn get-opt-out-reason
  "Calculate whether the user has opted out and return the reason for opting out, or nil if opting in"
  [opt-out-path]
  (cond (fs/exists? opt-out-path)
        (trs "{0} exists" opt-out-path)

        :default nil))

(def CheckinRequirements
  "These are various file paths and variables required
  to create a checkin."
  {:analytics-opt-out-path File
   :cacert schema/Str
   :certname schema/Str
   :telemetry-url schema/Str
   :version-path File
   :product-name (schema/maybe schema/Str)
   :queue Stockpile})

(def ProductConfig
  "Optional product keys that help the developer configure the analytics
  service with local settings."
  {(schema/optional-key :version-path) schema/Str
   (schema/optional-key :pe-version-path) schema/Str ; deprecated
   (schema/optional-key :name) {:group-id schema/Str
                                :artifact-id schema/Str}
   (schema/optional-key :update-server-url) schema/Str
   (schema/optional-key :conf-dir) schema/Str
   schema/Any schema/Any})

(def AnalyticsConfig
  "The minimum required config settings for the analytics namespace"
  {:global {:certs {:ssl-ca-cert schema/Str
                    schema/Any schema/Any}
            :hostname schema/Str
            schema/Any schema/Any}
   (schema/optional-key :product) ProductConfig
   schema/Any schema/Any})

(def CheckVersionResponse
  "The response map returned from Dujour when checking the version"
  {:message schema/Str
   :link schema/Str
   schema/Any schema/Any})

(schema/defn ^:always-validate
  create-checkin-args :- CheckinRequirements
  "Creates the object that is used as input to the checkin method."
  [config :- AnalyticsConfig]
  (let [telemetry-url (get-in config [:product :update-server-url] "https://updates.puppetlabs.com")
        conf-dir (get-in config [:product :conf-dir] "/etc/puppetlabs")
        analytics-opt-out-path (File. (str conf-dir "/analytics-opt-out"))
        certname (get-in config [:global :hostname])
        ssl-ca-cert (get-in config [:global :certs :ssl-ca-cert])
        cacert (if (fs/readable? ssl-ca-cert)
                 (slurp ssl-ca-cert)
                 (log/debug (trs "cacert file is not readable")))
        version-path (File. (or (get-in config [:product :version-path])
                                (get-in config [:product :pe-version-path])
                                "N/A"))
        product-name (get-in config [:product :name :artifact-id])
        queue (get-in config [:queue])]
    {:analytics-opt-out-path analytics-opt-out-path
     :telemetry-url telemetry-url
     :version-path version-path
     :certname certname
     :cacert cacert
     :product-name product-name
     :queue queue}))

(defn send-analytics-and-log-errors
  [query-params telemetry-url]
  (try+
    (version-check/send-telemetry query-params telemetry-url)
    (catch [:kind :puppetlabs.dujour.version-check/connection-error] {:keys [msg cause]}
      (log/debug cause (trs "Unable to connect to telemetry service at {0}: {1}." telemetry-url msg))
      {:error msg})
    (catch [:kind :puppetlabs.dujour.version-check/http-error-code] {:keys [msg details]}
      (log/debug (trs "Telemetry service at {0} responded with HTTP status code {1}, body: {2}." telemetry-url (:status details) (:body details)))
      {:error msg
       :details details})
    (catch [:kind :puppetlabs.dujour.version-check/unexpected-response] {:keys [msg details]}
      (log/debug (trs "Received an unexpected response from telemetry service at {0}. body: {1}" telemetry-url (:body details)))
      {:error msg
       :details details})
    (catch Exception {:keys [msg] :as e}
      (log/debug e (trs "Encountered an error when sending telemetry - url: {0}" telemetry-url))
      {:error msg})))

(schema/defn ^:always-validate send-analytics
  "Retrieves and sends analytics to dujour"
  [args telemetry-url]
  (send-analytics-and-log-errors args telemetry-url))

(defn get-version
  [version-path]
  (if (fs/exists? version-path)
    (str/trim (slurp version-path))
    (log/info (trs "No version file; analytics will fail to send."))))

(schema/defn ^:always-validate checkin
  [{:keys [analytics-opt-out-path telemetry-url queue
           version-path product-name cacert certname]}
    :- CheckinRequirements]
  (let [opt-out-reason (get-opt-out-reason analytics-opt-out-path)
        missing-name-warning (when-not product-name
                               (trs "missing product name value from configuration"))]
    (if (or opt-out-reason missing-name-warning)
      (let [reason (->> [opt-out-reason missing-name-warning] (remove nil?) (str/join ", "))]
        (core/purge-queue! queue)               ; Empty the queue to prevent overflow.
        (log/info (trs "Not sending analytics data - (reason: {0})." reason)))
      (let [[self-service-analytics entry-ids] (core/get-self-service-analytics queue)
            version (get-version version-path)
            args {:product-name product-name
                  :version version
                  :cacert cacert
                  :certname certname
                  :self-service-analytics self-service-analytics}
            result (send-analytics args telemetry-url)]
        ; TODO Should this check for something? `dujour-version-check` only returns those 5 keys...
        ;(when (some #(= (:status result) %) ["success", "partial"]) ... )
          (core/discard! queue entry-ids)
        result))))
