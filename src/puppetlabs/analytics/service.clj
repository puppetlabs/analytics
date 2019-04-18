(ns puppetlabs.analytics.service
  (:require [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [puppetlabs.analytics.checkin.core :as checkin]
            [puppetlabs.analytics.storage :as storage]
            [puppetlabs.analytics.web-core :as web]
            [puppetlabs.http.client.sync :as http]
            [puppetlabs.trapperkeeper.core :as trapperkeeper])
  (:import java.io.IOException))

(defprotocol AnalyticsService)

(defn get-whitelist
  [dujour-url]
  (try
    (let [response (http/get (str dujour-url "/whitelist")
                             {:connect-timeout-milliseconds 5000
                              :socket-timeout-milliseconds 5000})]
     (if (= 200 (:status response))
       (-> response
           :body
           slurp
           json/decode)
       (log/infof "Received unexpected response with status code %s when trying to retrieve the whitelist." (:status response))))
    (catch java.io.IOException e
      (log/infof "Encountered an error trying to retrieve the whitelist: %s" e))))

(trapperkeeper/defservice analytics-service
  AnalyticsService
  [[:ConfigService get-in-config get-config]
   [:WebroutingService add-ring-handler get-route]
   [:SchedulerService interspaced]]
  (init [this context]
        (log/info "Initializing analytics webservice")
        (let [url-prefix (get-route this)
              data-dir (get-in-config [:analytics :data-directory])
              dujour-url (get-in-config [:product :update-server-url])
              !whitelist (atom (when dujour-url (get-whitelist dujour-url)))
              queue (storage/init-queue data-dir)
              handler (web/app->wrapped-handler url-prefix (web/app queue !whitelist (get-config)))]
          (log/infof "Prefix is %s" url-prefix)
          (add-ring-handler this handler)
          (assoc context
                 :url-prefix url-prefix
                 :whitelist !whitelist
                 :queue queue)))

  (start [this context]
         (let [checkin-interval-millis (* 1000 60 60 24)  ; once per day
               !whitelist (:whitelist context)
               queue (:queue context)
               config (assoc (get-config) :queue queue)]
           (let [checkin-interval-millis (* 1000 60 60 24)
                 args (checkin/create-checkin-args config)] ; once per day
             ;; TODO Read analytics, submit to dujour, clear cache
             (interspaced checkin-interval-millis
              (fn []
                (let [checkin-args (checkin/create-checkin-args config)]
                  (when-let [new-whitelist (:whitelist (checkin/checkin checkin-args))]
                    (swap! !whitelist (constantly new-whitelist)))))))
           (log/infof "Analytics web service started."))
         context))
