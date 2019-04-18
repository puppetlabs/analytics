(ns puppetlabs.analytics.api
  (:require [schema.core :as schema]))

(def ScalarValue
  (schema/cond-pre schema/Str schema/Num schema/Bool))

(def MapValue
  {schema/Str ScalarValue})

(def ListValue
  [(schema/cond-pre MapValue ScalarValue)])

(def FieldValue
  (schema/cond-pre
   ListValue
   MapValue
   ScalarValue))

(def Metadata
  {schema/Str FieldValue})

(def SnapshotIn
  {(schema/required-key "fields") Metadata
   ; This exists as a string after conversion to internal snapshot.
   (schema/optional-key "timestamp") schema/Str})

(def SnapshotOut
  {schema/Str {(schema/required-key "value") FieldValue
               (schema/required-key "timestamp") schema/Str}})

(def EventIn
  {(schema/required-key "event") schema/Str
   (schema/optional-key "metadata") Metadata})

(def EventOut
  {(schema/required-key "event") schema/Str
   (schema/optional-key "metadata") Metadata
   ; This exists as a string after conversion to internal event.
   (schema/optional-key "timestamp") schema/Str})

(def Analytics
  {(schema/required-key "events") [EventOut]
   (schema/required-key "snapshots") SnapshotOut})
