(ns puppetlabs.analytics.web-core
  (:require [cheshire.core :as json]
            [puppetlabs.analytics.api :as api]
            [puppetlabs.analytics.core :as core]
            [puppetlabs.analytics.middleware :as middleware]
            [puppetlabs.analytics.storage :as storage]
            [puppetlabs.comidi :as comidi]
            [schema.core :as schema]
            [slingshot.slingshot :refer [throw+]]
            [puppetlabs.analytics.internal-api :as internal-api]
            [puppetlabs.analytics.checkin.core :as checkin])
  (:import com.fasterxml.jackson.core.JsonParseException))

(defn app->wrapped-handler
  [url-prefix app]
  (->> app
       (comidi/context url-prefix)
       comidi/routes->handler
       middleware/wrap-invalid-json
       middleware/wrap-schema-errors
       middleware/wrap-error-catchall))

(defn parse-json
  [body]
  (try
    (json/decode (slurp body))
    (catch JsonParseException e
      (throw+ {:kind :invalid-json
               :exception e}))))

(defn validate-schema!
  "This wraps schema/check and throws a custom slingshot stone instead of the
  generic exception that schema/validate would throw."
  [schema body]
  (when-let [schema-error (schema/check schema body)]
    (throw+ {:kind :schema-violation
             :schema schema
             :submitted body
             :error schema-error})))

(defn handle-snapshot-post
  [queue whitelist body]
  (let [snapshot (parse-json body)
        _ (validate-schema! api/SnapshotIn snapshot)

        {:keys [accepted rejected] :as results}
        (->> snapshot
             internal-api/->internal-snapshot
             (core/partition-snapshots-whitelist whitelist))

        to-render (update results :accepted internal-api/render-snapshot)]
    (storage/store queue accepted)
    (if (not-empty rejected)
      (middleware/json-response 206 to-render)
      (middleware/json-response 200 to-render))))

(defn handle-event-post
  [queue whitelist body]
  (let [api-event (parse-json body)
        _ (validate-schema! api/EventIn api-event)
        event (internal-api/->internal-event api-event)
        error (core/event-whitelist-error? whitelist event)]
    (if error
      (middleware/json-response 422 {"rejected" {(get event "event") error}})
      (do
        (storage/store queue event)
        (middleware/json-response 200 (internal-api/render-event event))))))

(defn handle-send-post
  [queue !whitelist config body]
  (try
    (let [config (assoc config :queue queue)
          checkin-args (checkin/create-checkin-args config)
          result (checkin/checkin checkin-args)]
      (if (:error result)
        (middleware/json-response 500 (assoc result :status "failure"))
        (do
          (when-let [new-whitelist (:whitelist result)]
            (swap! !whitelist (constantly new-whitelist)))
          (middleware/json-response 200 {:status "success" :response result}))))
   (catch Exception e
     (middleware/json-response 422 (str "Caught exception: " (.getMessage e))))))

(defn app
  [queue !whitelist config]
  (comidi/routes
   (comidi/GET [""] request
     (middleware/json-response 200 (core/entrypoint request)))

   (comidi/POST ["/commands/snapshot"] {body :body}
     (handle-snapshot-post queue @!whitelist body))

   (comidi/POST ["/commands/event"] {body :body}
     (handle-event-post queue @!whitelist body))

   (comidi/POST ["/commands/send"] {body :body}
     (handle-send-post queue !whitelist config body))

   (comidi/GET ["/collections/snapshots"] [request]
     (->> (storage/reduce-entries
           queue
           (fn [acc entry-id]
             (let [entry (storage/read-entry queue entry-id)]
               (if (nil? (schema/check internal-api/Snapshot entry))
                 (conj acc (internal-api/render-snapshot entry))
                 acc)))
           [])
          (middleware/json-response 200)))

   (comidi/GET ["/collections/events"] [request]
     (->> (storage/reduce-entries
           queue
           (fn [acc entry-id]
             (let [entry (storage/read-entry queue entry-id)]
               (if (nil? (schema/check internal-api/Event entry))
                 (conj acc (internal-api/render-event entry))
                 acc)))
           [])
          (middleware/json-response 200)))

   (comidi/not-found "Not Found!!!!")))
