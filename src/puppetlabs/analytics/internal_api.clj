(ns puppetlabs.analytics.internal-api
  (:require [schema.core :as schema]
            [puppetlabs.analytics.api :as api]
            [clj-time.core :as time]
            [clj-time.coerce :as time.coerce]))

;; The internal versions merge the namespace with the names
(def Event
  {(schema/required-key "event") schema/Str
   (schema/optional-key "metadata") api/Metadata
   (schema/required-key "timestamp") org.joda.time.DateTime})

(def Snapshot
  {schema/Str {(schema/required-key "value") api/FieldValue
               (schema/required-key "timestamp") org.joda.time.DateTime}})

(def Analytics
  {(schema/required-key "events") [Event]
   (schema/required-key "snapshots") Snapshot})

(schema/defn ^:always-validate ->internal-event :- Event
  [event :- api/EventIn]
  (assoc event
    "timestamp" (time/now)))

(schema/defn ^:always-validate
->internal-snapshot :- Snapshot
  [{:strs [fields]} :- api/SnapshotIn]
  (into {} (for [[key value] fields]
             [key {"value" value, "timestamp" (time/now)}])))

(schema/defn ^:always-validate render-event :- api/EventOut
  [event :- Event]
  (update event "timestamp" time.coerce/to-string))

(schema/defn ^:always-validate render-snapshot :- api/SnapshotOut
  [snapshot :- Snapshot]
  (reduce-kv (fn [m k _]
               (update-in m [k "timestamp"] time.coerce/to-string)) snapshot snapshot))

(schema/defn ^:always-validate render-analytics :- api/Analytics
  [analytics :- Analytics]
  {"events" (map render-event (get analytics "events"))
   "snapshots" (render-snapshot (get analytics "snapshots"))})
