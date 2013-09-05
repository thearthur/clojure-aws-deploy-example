(ns wordopia.deploy
  (:use [amazonica.core]
        [amazonica.aws.ec2])
  (:require [pallet.api :refer [converge group-spec node-spec plan-fn]]
            [pallet.configure :refer [compute-service]]
            [pallet.repl :refer [show-nodes]]
            [pallet.compute.vmfest :refer [add-image]]
            [pallet.crate :refer [nodes-in-group target-node]]
            [pallet.actions :refer [exec-checked-script package package-manager remote-file]]
            [pallet.action :refer [clj-action]]
            [pallet.crate.java :as java]
            [pallet.core.user :refer [make-user]]
            [pallet.crate.automated-admin-user :refer [automated-admin-user]]
            [pallet.node :refer [primary-ip id] :as node]
            [pallet.compute.jclouds]
            [pallet.crate.lein :as lein]
            [amazonica.aws.autoscaling :refer [create-launch-configuration create-auto-scaling-group
                                               describe-auto-scaling-instances describe-auto-scaling-groups
                                               delete-launch-configuration delete-auto-scaling-group
                                               describe-auto-scaling-groups]]))

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
            new-ami (create-image
                     cred
                     :instance-id id
                     :name image-name
                     :description "image")]
        (while (->> new-ami :image-id vector
                    (describe-images (ec2-keys-form-file) :image-ids ) :images first
                    :state (not= "available"))
          (println "waiting for " new-ami "to become available")
          (Thread/sleep 5000))
        (println "created:" new-ami)
        new-ami)))

(defn destroy-auto-scaling-group [image]
  (let [cred (ec2-keys-form-file)
        id (:image-id image)
        name (str "wordopia-" id)
        instances [] #_(->> name (describe-auto-scaling-groups (ec2-keys-form-file) :auto-scaling-group-name)
                       :auto-scaling-groups first :instances (map :instance-id))]
    (delete-auto-scaling-group (ec2-keys-form-file) :auto-scaling-group-name name)
    (Thread/sleep 10000)
    (delete-launch-configuration (ec2-keys-form-file) :launch-configuration-name name)
    (Thread/sleep 10000)))

(defn make-auto-scaling-group [load-balancer]
  (when (= (class (node/compute-service (target-node))) pallet.compute.jclouds.JcloudsService)
    (let [image (make-image)
          cred (ec2-keys-form-file)
          id (:image-id image)
          name (str load-balancer "-" id)]
       (create-launch-configuration cred
                                    :launch-configuration-name name
                                    :image-id (:image-id image)
                                    :instance-type "m1.small"
                                    :security-groups ["default"])
       (create-auto-scaling-group cred
                                  :load-balancer-names [load-balancer]
                                  :auto-scaling-group-name name
                                  :availability-zones ["us-east-1a" "us-east-1b"]
                                  :desired-capacity 2
                                  :health-check-grace-period 300
                                  :health-check-type "ELB"
                                  :launch-configuration-name name
                                  :min-size 0
                                  :max-size 2)
      (describe-auto-scaling-groups (ec2-keys-form-file) :auto-scaling-group-name "aws_autoscale_grp"))))

(defn load-balancer [count where]
  (group-spec "load-balancers"
               :count count
               :node-spec (instances where)
               :phases {:bootstrap automated-admin-user
                        :configure
                        (plan-fn
                         (println "using web servers:" (ips-in-group :web-servers))
                         (make-auto-scaling-group "lbs"))}))

(defn web-server [count where]
  (group-spec "web-servers"
               :count count
               :node-spec (instances where)
               :phases {:bootstrap (if-not (= :local where)
                                     automated-admin-user
                                     (plan-fn (println "no bootstrap for local")))
                        :configure
                        (plan-fn (package-manager :update)
                                 (package "git-core"))
                        :webapp
                        (plan-fn
                         (exec-checked-script
                          "checkout project"
                          "mkdir -p /opt/web"
                          "rm /opt/web/wordopia-app -rf"
                          "git clone https://github.com/thearthur/wordopia-app.git /opt/web/wordopia-app"))
                        :local-start
                        (plan-fn
                         (exec-checked-script
                          "start local server"
                          "cd /opt/web/wordopia-app && nohup lein ring server &"))
                        :start
                        (plan-fn
                         (remote-file
                          "/etc/init.d/wordopia"
                          :content "#!/bin/bash\ncd /opt/web/wordopia-app && exec nohup /usr/local/bin/lein ring server &"
                          :owner "root" :group "root"
                          :mode 755
                          :overwrite-changes true)
                         (exec-checked-script
                          "start web app"
                          "killall java || true" 
                          "rm -f /etc/rc2.d/S10wordopia"
                          "ln -s /etc/init.d/wordopia /etc/rc2.d/S10wordopia"
                          "/etc/rc2.d/S10wordopia"
                          "sleep 20")
                         (make-auto-scaling-group "wordopia"))}
               :extends [(java/server-spec
                          {:vendor :oracle
                           :components #{:jdk}
                           :version [7]})
                         (lein/leiningen {})]))
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
   :phase (if (= :local where)
            [:webapp :local-start]
            [:configure :install :webapp :start])
   :compute (compute-service where)
   :user who))
