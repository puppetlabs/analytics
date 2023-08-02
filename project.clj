(defn deploy-info
  [url]
  {:url url
   :username :env/clojars_jenkins_username
   :password :env/clojars_jenkins_password
   :sign-releases false})

(defproject puppetlabs/analytics "1.1.4-SNAPSHOT"
  :description "Analytics service for Puppet"
  :url "https://github.com/puppetlabs/analytics"

  :pedantic? :abort

  :dependencies [[org.clojure/clojure]
                 [org.clojure/tools.logging]
                 [puppetlabs/comidi]
                 [puppetlabs/dujour-version-check]
                 [puppetlabs/http-client]
                 [puppetlabs/kitchensink]
                 [puppetlabs/trapperkeeper]
                 [puppetlabs/trapperkeeper-scheduler]
                 [puppetlabs/trapperkeeper-webserver-jetty9]
                 [prismatic/schema]
                 [trptcolin/versioneer "0.2.0"]
                 [puppetlabs/stockpile "0.0.4"]
                 [clj-time]]

  :parent-project {:coords [puppetlabs/clj-parent "7.1.0"]
                   :inherit [:managed-dependencies]}

  :profiles {:defaults {:source-paths ["dev"]
                        :dependencies [[puppetlabs/trapperkeeper :classifier "test" :scope "test"]
                                       [puppetlabs/kitchensink :classifier "test" :scope "test"]
                                       [clj-http "3.0.0"]
                                       [org.clojure/tools.namespace "0.2.11"]
                                       [ring-mock "0.1.5"]]}
             :dev [:defaults {:dependencies [[org.bouncycastle/bcpkix-jdk15on]]}]
             :fips [:defaults {:dependencies [[org.bouncycastle/bcpkix-fips]
                                              [org.bouncycastle/bctls-fips]
                                              [org.bouncycastle/bc-fips]]
                               :jvm-opts ~(let [version (System/getProperty "java.version")
                                                [major minor _] (clojure.string/split version #"\.")
                                                unsupported-ex (ex-info "Unsupported major Java version. Expects 8 or 11."
                                                                 {:major major
                                                                  :minor minor})]
                                            (condp = (java.lang.Integer/parseInt major)
                                              1 (if (= 8 (java.lang.Integer/parseInt minor))
                                                  ["-Djava.security.properties==./dev-resources/java.security.jdk8-fips"]
                                                  (throw unsupported-ex))
                                              11 ["-Djava.security.properties==./dev-resources/java.security.jdk11-fips"] 
                                              17 ["-Djava.security.properties==./dev-resources/java.security.jdk17-fips"]
                                              (throw unsupported-ex)))}]
             :ci {:plugins [[lein-pprint "1.1.2" :exclusions [org.clojure/clojure]]]}}

  :repl-options {:init-ns user}

  :aliases {"tk" ["trampoline" "run" "--config" "dev-resources/config.conf"]}

  :main puppetlabs.trapperkeeper.main
  :plugins [[lein-parent "0.3.8"]]

  :repositories [["releases" "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-releases__local/"]
                 ["snapshots" "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-snapshots__local/"]]
  :deploy-repositories [["releases" ~(deploy-info "https://clojars.org/repo")]
                        ["snapshots" "https://artifactory.delivery.puppetlabs.net/artifactory/clojure-snapshots__local/"]]
  )
