(ns wordopia.deploy
  (:require [pallet.api :refer [converge group-spec node-spec plan-fn]]
            [pallet.configure :refer [compute-service]]
            [pallet.repl :refer [show-nodes]]
            [pallet.compute.vmfest :refer [add-image]]
            [pallet.crate :refer [nodes-in-group target-node]]
            [pallet.actions :refer [exec-checked-script package package-manager]]
            [pallet.action :refer [clj-action]]
            [pallet.crate.java :as java]
            [pallet.core.user :refer [make-user]]
            [aws.sdk.ec2 :as ec2]
            [pallet.crate.automated-admin-user :refer [automated-admin-user]]
            [pallet.node :refer [primary-ip id] :as node]
            [aws.sdk.ec2 :as ec2]))

(def instances
  {:aws (node-spec
         :image {:os-family :ubuntu
                 :image-id "us-east-1/ami-10314d79" })
   :vmfest (node-spec
            :image {:os-family :ubuntu
                    :os-version "12-04"})})

(defn ips-in-group [group-name]
  "Sequence of the first private IP from each node in the group"
  (map pallet.node/primary-ip (nodes-in-group group-name)))

(defn ec2-keys-form-file []
  (-> (str (System/getenv "HOME") "/.pallet/services/aws.clj")
      slurp
      read-string
      :aws
      (#(hash-map :access-key (:identity %) :secret-key (:credential %)))))


(defn make-image []
  (when (= (class (node/compute-service (target-node))) pallet.compute.jclouds.JcloudsService)
      (let [me (target-node)
            id (-> me id (.  split "/") second)
            image-name (str (node/group-name me) "-" id)
            cred (ec2-keys-form-file)
            new-ami (ec2/create-image cred {:instance-id id
                                            :name image-name
                                            :description "image"})]
        (while (->> new-ami :image-id ec2/image-id-filter
                    (ec2/describe-images (ec2-keys-form-file)) first
                    :state (not= "available"))
          (println "waiting for " new-ami "to become available")
          (Thread/sleep 5000))
        (println "created:" new-ami)
        new-ami)))

(defn load-balancer [count where]
  (group-spec "load-balancers"
               :count count
               :node-spec (instances where)
               :phases {:bootstrap automated-admin-user
                        :configure
                        (plan-fn
                         (println "using web servers:" (ips-in-group :web-servers))
                         (make-image))}))

(defn web-server [count where]
  (group-spec "web-servers"
               :count count
               :node-spec (instances where)
               :phases {:bootstrap automated-admin-user
                        :configure
                        (plan-fn (package-manager :update)
                                 (package "git-core"))
                        :install
                        (plan-fn
                         (exec-checked-script
                          "checkout project"
                          "mkdir -p /opt/web"
                          "rm /opt/web/wordopia-app -rf"
                          "git clone https://github.com/thearthur/wordopia-app.git /opt/web/wordopia-app")
                         (make-image))}
               :extends [(java/server-spec
                          {:vendor :oracle
                           :components #{:jdk}
                               :version [7]})]))
; Who
(def vmfest-user (make-user "vmfest" {:password "vmfest" :sudo-password "vmfest"
                                      :private-key-path (str (System/getenv "HOME") "/.ssh/id_rsa")
                                      :public-key-path  (str (System/getenv "HOME") "/.ssh/id_rsa.pub")}))
(def local-user (make-user "arthur" {:password "****" :sudo-password "****"}))
(def aws-user (make-user "ubuntu" {:private-key-path (str (System/getenv "HOME") "/.ssh/id_rsa")
                                   :public-key-path  (str (System/getenv "HOME") "/.ssh/id_rsa.pub")}))

; What
(defn single  [where] [(web-server 1 where)])
(defn double  [where] [(web-server 2 where) (load-balancer 1 where)])
(defn nothing [where] [(web-server 0 where) (load-balancer 0 where)])

(defn make-it-so [who what where]
  (converge 
   (what where)
   :phase [:configure :install]
   :compute (compute-service where)
   :user who))
