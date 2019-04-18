(ns puppetlabs.analytics.core
  (:require [puppetlabs.analytics.api :as api]
            [puppetlabs.analytics.internal-api :as internal-api]
            [puppetlabs.analytics.storage :as storage]
            [schema.core :as schema]
            [trptcolin.versioneer.core :as version]
            [clj-time.core :as time]
            [puppetlabs.kitchensink.core :as ks])
  (:import org.joda.time.DateTime)
  (:import (puppetlabs.stockpile.queue Stockpile)))

(def Whitelist
  {schema/Str {(schema/required-key "datatype") schema/Str
               (schema/optional-key "description") schema/Str}})

(defn entrypoint
  "Returns the entrypoint, which is metadata describing the API and where to find
  various items."
  [request]
  (let [protocol (name (get request :scheme "https"))
        uri (get request :uri "/analytics")
        host (get request :server-name)
        port (get request :server-port)
        entrypoint-url (str protocol "://" host ":" port uri)]
    {:commands {:event {:name "event"
                        :rel "https://api.puppetlabs.com/analytics/v1/commands/event"
                        :id (str entrypoint-url "/commands/event")
                        :params {"namespace" {"datatype" "string"}
                                 "event" {"datatype" "string"}
                                 "metadata" {"datatype" "object"
                                             "optional" "true"}}},
                :snapshot {:name "snapshot"
                           :rel "https://api.puppetlabs.com/analytics/v1/commands/snapshot"
                           :id (str entrypoint-url "/commands/snapshot")
                           :params {"namespace" {"datatype" "string"}
                                    "fields" {"datatype" "object"}}}}
     ;; Note: Collection endpoints are undocumented and unsupported until required in the API.
     :version {:server (version/get-version "puppetlabs" "analytics" "")}}))

(schema/defn ^:always-validate combine-events :- internal-api/Analytics
  [base-analytics :- internal-api/Analytics
   new-events :- [internal-api/Event]]
  (update base-analytics "events" #(concat % new-events)))

(defn- add-snapshot-if-newer
  [snapshots field candidate-snapshot]
  (if-let [{existing-timestamp "timestamp"} (get snapshots field)]
    (if (time/before? existing-timestamp (get candidate-snapshot "timestamp"))
      (assoc snapshots field candidate-snapshot)
      snapshots)
    (assoc snapshots field candidate-snapshot)))

(defn- select-newest-snapshots
  "Given two maps of snapshots produce a single map using the newest value present for each."
  [base-snapshots candidate-snapshots]
  (reduce-kv add-snapshot-if-newer base-snapshots candidate-snapshots))

(schema/defn ^:always-validate combine-snapshots :- internal-api/Analytics
  [base-analytics :- internal-api/Analytics
   candidate-snapshots :- internal-api/Snapshot]
  (update base-analytics "snapshots" #(select-newest-snapshots % candidate-snapshots)))

(schema/defn ^:always-validate partition-entries :- [(schema/cond-pre internal-api/Analytics [schema/Int])]
  "Partitions entries from a storage queue into a map of events and snapshots."
  [queue :- Stockpile]
  (storage/reduce-entries
   queue
   (fn [acc entry-id]
     (let [entry (storage/read-entry queue entry-id)]
       (if (nil? (schema/check internal-api/Event entry))
         [(combine-events (first acc) [entry]) (conj (second acc) entry-id)]
         (if (nil? (schema/check internal-api/Snapshot entry))
           [(combine-snapshots (first acc) entry) (conj (second acc) entry-id)]
           acc))))
   [{"events" []
     "snapshots" {}}
    []]))

(schema/defn ^:always-validate get-self-service-analytics :- [(schema/cond-pre api/Analytics [schema/Int])]
  "Retrieves self-service analytics from the given path."
  [queue :- Stockpile]
  (let [[entries entry-ids] (partition-entries queue)
        rendered (internal-api/render-analytics entries)]
    [rendered entry-ids]))

(schema/defn ^:always-validate purge-queue!
  [queue :- Stockpile]
  (storage/purge-queue! queue))

(schema/defn ^:always-validate discard!
  [queue :- Stockpile
   entry-ids :- [schema/Int]]
  (storage/discard! queue entry-ids))

(def datatype->schema-checker
  (ks/mapvals
   schema/checker
   {"string" schema/Str
    "number" schema/Num
    "boolean" schema/Bool
    "array[string]" [schema/Str]
    "array[number]" [schema/Num]
    "array[boolean]" [schema/Bool]
    "object[string-number]" {schema/Str schema/Num}
    "object[string-string]" {schema/Str schema/Str}
    "object[string-boolean]" {schema/Str schema/Bool}}))

(defn- type-error?
  [{:strs [datatype]} {:strs [value]}]
  (when ((datatype->schema-checker datatype) value)
    (str "Expected a value of type " datatype)))

(defn- validate-snapshot
  [whitelist results field value]
  (if-let [spec (get whitelist field)]
    (if-let [error (type-error? spec value)]
      (assoc-in results [:rejected field] error)
      (assoc-in results [:accepted field] value))
    (assoc-in results [:rejected field] "Not an expected field")))

(schema/defn ^:always-validate partition-snapshots-whitelist
  [whitelist :- (schema/maybe Whitelist)
   snapshot :- internal-api/Snapshot]
  (if whitelist
    (reduce-kv (partial validate-snapshot whitelist)
               {:accepted {} :rejected {}}
               snapshot)
    {:accepted snapshot :rejected {}}))

(schema/defn ^:always-validate event-whitelist-error?
  [whitelist :- (schema/maybe Whitelist)
   event :- internal-api/Event]
  (when whitelist
    (let [event-name (get event "event")
          expected-type (get-in whitelist [event-name "datatype"])]
      (if (nil? expected-type)
        "Not an expected event"
        (if (not (= "event" expected-type))
          (str "Expected a value of type " expected-type)
          nil)))))
