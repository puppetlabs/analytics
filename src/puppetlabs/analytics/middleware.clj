(ns puppetlabs.analytics.middleware
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [ring.util.response :as ring-response]
            [schema.core :as schema]
            [slingshot.slingshot :refer [throw+ try+]]))

(defn- log-exception
  "Log an exception including request method and URI"
  [request exception]
  (let [method (-> request :request-method name str/upper-case)]
    (log/warn exception method (:uri request))))

(defn json-response
  [status-code body]
  (-> body
      json/encode
      ring-response/response
      (ring-response/status status-code)
      (ring-response/content-type "application/json; charset=utf-8")))

(defn wrap-schema-errors
  "Middleware to handle schema validation failures."
  [handler]
  (fn [request]
    (try+
      (handler request)
      (catch [:kind :schema-violation] {:keys [schema submitted error]}
        (json-response
         422
         {:kind "schema-violation"
          :msg (format "The objects that were submitted did not conform to the schema. The problem is: %s" (seq error))
          :details {:submitted (str submitted)
                    :schema (str schema)
                    :error error}})))))

(defn wrap-error-catchall
  "Wrap the handler in a try/catch that swallows any throwable and returns a 500
  to the client with the throwable's error message."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Exception e
        (log-exception request e)
        (json-response
         500
         {:kind :application-error
          :msg "An uncaught server error was thrown. Check the log for details."})))))

(defn wrap-invalid-json
  [handler]
  (fn [request]
    (try+
      (handler request)
      (catch [:kind :invalid-json] {:keys [exception]}
        (json-response
         400
         {:kind :invalid-data
          :msg (format "Failed to parse body as JSON: %s" (.getMessage exception))})))))
