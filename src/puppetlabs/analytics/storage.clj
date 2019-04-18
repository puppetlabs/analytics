(ns puppetlabs.analytics.storage
  (:require [cheshire.core :as json]
            [puppetlabs.analytics.internal-api :as internal-api]
            [puppetlabs.stockpile.queue :as stockpile]
            [schema.core :as schema]
            [slingshot.slingshot :refer [throw+]])
  (:import [java.io ByteArrayInputStream IOException]
           [java.nio.file Files FileSystems NoSuchFileException Path Paths]
           java.nio.file.attribute.FileAttribute
           [org.joda.time DateTime DateTimeZone]
           puppetlabs.stockpile.queue.Stockpile))

(defn- ^Path path-get [^String s & more-strings]
  (Paths/get s (into-array String more-strings)))

(defn init-queue
  "Initialize a queue. The `dir` is expected to exist and be writable."
  [dir]
  (let [root (.toAbsolutePath (path-get dir))
        queue-path (.resolve root "analytics")]
    (try
      (try
        (Files/createDirectories root (make-array FileAttribute 0))
        (catch java.nio.file.FileAlreadyExistsException _))
      (try (stockpile/open queue-path)
           (catch java.nio.file.NoSuchFileException _
             (stockpile/create queue-path)))
      (catch java.io.IOException e
        (throw+ {:kind :storage-init-failed
                 :msg (format "Encountered an error initializing analytics storage in %s: %s" queue-path e)}
                e)))))

(defn- timestamps->millis
  "Convert all timestamps in an object to be written to the queue to
  milliseconds since the epoch."
  [object]
  (if (contains? object "timestamp")
    (update object "timestamp" #(.getMillis %))
    (into {} (for [[field snapshot] object]
               [field
                (update snapshot "timestamp" #(.getMillis %))]))))

(defn- millis->timestamps
  "Convert timestamps in millisecond since to epoch to joda DateTime objects"
  [object]
  (let [from-millis (fn [millis] (DateTime. (long millis) (DateTimeZone/UTC)))]
    (if (contains? object "timestamp")
     (update object "timestamp" from-millis)
     (into {} (for [[field snapshot] object]
                [field
                 (update snapshot "timestamp" from-millis)])))))

(schema/defn ^:always-validate
  store :- (schema/protocol stockpile/Entry)
  "Store a message on the queue. The queue should already be initialized. The
  message should be a map. This returns a message id that can be used to
  retrieve the message."
  [queue :- puppetlabs.stockpile.queue.Stockpile
   object :- (schema/if #(contains? % "event") internal-api/Event internal-api/Snapshot)]
  (->> object
       timestamps->millis
       json/encode
       .getBytes
       ByteArrayInputStream.
       (stockpile/store queue)))

(defn read-entry
  "Read a message from the queue. This does not remove it from the queue, but
  just reads a message by id."
  [queue message-id]
  (-> (stockpile/stream queue message-id)
      slurp
      json/decode
      millis->timestamps))

(defn reduce-entries
  "Reduce over the entries. The reducer function should expect to get entry ids."
  [queue f val]
  (stockpile/reduce queue f val))

(schema/defn ^:always-validate discard!
  [queue :- Stockpile
   entry-ids :- [schema/Int]]
  (doseq [entry entry-ids]
    (stockpile/discard queue entry)))

(schema/defn ^:always-validate purge-queue!
  [queue :- Stockpile]
  (let [entries (stockpile/reduce queue conj ())]
    (discard! queue entries)))

