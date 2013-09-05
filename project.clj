(defproject wordopia "0.1.0-SNAPSHOT"
  :description "FIXME Pallet project for wordopia"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [com.palletops/pallet "0.8.0-RC.1"]
                 [com.palletops/pallet-jclouds "1.5.3"]
                 ;; To get started we include all jclouds compute providers.
                 ;; You may wish to replace this with the specific jclouds
                 ;; providers you use, to reduce dependency sizes.
                 [org.jclouds/jclouds-allblobstore "1.5.5"]
                 [org.jclouds/jclouds-allcompute "1.5.5"]
                 [org.jclouds.driver/jclouds-slf4j "1.5.5"
                  ;; the declared version is old and can overrule the
                  ;; resolved version
                  :exclusions [org.slf4j/slf4j-api]]
                 [org.jclouds.driver/jclouds-sshj "1.5.5"]
                 [ch.qos.logback/logback-classic "1.0.9"]
                 [org.clojars.tbatchelli/vboxjws "4.2.4"]
                 [com.palletops/pallet-vmfest "0.3.0-alpha.5"]
                 [com.palletops/java-crate "0.8.0-beta.5"]
                 [clj-aws-ec2 "0.3.0"]]
  :profiles {:dev
             {:dependencies
              [[com.palletops/pallet "0.8.0-RC.1"
                :classifier "tests"]]
              :plugins
              [[com.palletops/pallet-lein "0.8.0-alpha.1"]]}
             :leiningen/reply
             {:dependencies [[org.slf4j/jcl-over-slf4j "1.7.2"]]
              :exclusions [commons-logging]}}
  :local-repo-classpath true
  :repositories
  {"sonatype" "https://oss.sonatype.org/content/repositories/releases/"})
