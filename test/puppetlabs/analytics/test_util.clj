(ns puppetlabs.analytics.test-util
  (:require [puppetlabs.analytics.service :as svc]
            [puppetlabs.analytics.storage :as storage]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.bootstrap :as bootstrap]
            [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.trapperkeeper.testutils.bootstrap
             :refer
             [with-app-with-config]]
            [me.raynes.fs :as fs])
  (:import [java.nio.file Files FileVisitor FileVisitResult]
           java.nio.file.attribute.FileAttribute))

(defn create-temp-directory
  [prefix]
  (Files/createTempDirectory prefix (make-array FileAttribute 0)))

(defn delete-tree
  "Recursively delete a directory. Java NIO uses a visitor with walkFileTree, so
  we construct a FileVisitor that deletes files on visit and deletes directories
  after visit."
  [path]
  (let [delete-visitor (reify FileVisitor
                         (visitFile [_ file attrs]
                           (Files/delete file)
                           FileVisitResult/CONTINUE)
                         (postVisitDirectory [_ dir exc]
                           (Files/delete dir)
                           FileVisitResult/CONTINUE)
                         (preVisitDirectory [_ dir attrs]
                           FileVisitResult/CONTINUE)
                         (visitFileFailed [_ file exc]
                           FileVisitResult/CONTINUE))]
    (Files/walkFileTree path delete-visitor)))

(defmacro with-temp-directory
  "Create a temporary directory bound to `name`, execute the body, and delete
  the directory and its contents."
  [name prefix & body]
  `(let [~name (create-temp-directory ~prefix)]
     (try
       ~@body
       (finally
         (delete-tree ~name)))))

(defmacro with-queue
  "Execute the body with a temporary queue bound to `name`."
  [name & body]
  `(let [temp-path# (create-temp-directory "analytics-test")
         ~name (storage/init-queue (.toString temp-path#))]
     (try
       ~@body
       (finally
         (delete-tree temp-path#)))))

(def test-config
  (tk-config/load-config "dev-resources/test-config.conf"))

(def base-url
  (let [{:keys [host port]} (get-in test-config [:webserver])
        service-id :puppetlabs.analytics.service/analytics-service
        prefix (or (get-in test-config [:web-router-service service-id :route])
                   (get-in test-config [:web-router-service service-id]))]
    (str "http://" host ":" port prefix)))

(defn service-id->webserver
  [app-config service-id]
  (let [default-server (->> (:webserver app-config)
                            (filter (comp :default-server second))
                            first
                            first)]
    (or (get-in app-config [:web-router-service service-id :server])
        default-server)))

(defn with-analytics-service*
  ([f] (with-analytics-service* {} f))
  ([config f]
   (with-temp-directory var-dir "analytics-test"
     (let [base-config (assoc-in test-config [:analytics :data-directory] (.toString var-dir))
           conf-dir (ks/temp-dir "conf-dir")
           _ (fs/touch (str conf-dir "/analytics-opt-out"))
           opt-out-config (assoc-in base-config [:product :conf-dir] (.getPath conf-dir))
           services (bootstrap/parse-bootstrap-config! "dev-resources/bootstrap.cfg")]
       (with-app-with-config
         app
         services
         (ks/deep-merge opt-out-config config)
         (f))))))

(defmacro with-analytics-service
  [& body]
  `(with-analytics-service* {} (fn [] ~@body)))

(defmacro with-analytics-service-config
  [config & body]
  `(with-analytics-service* ~config (fn [] ~@body)))

