(ns clojars.routes.api
  (:require [clojure.set :refer [rename-keys]]
            [compojure.core :refer [GET ANY defroutes context]]
            [compojure.route :refer [not-found]]
            [ring.middleware.format-response :refer [wrap-restful-response]]
            [ring.util.response :refer [response]]
            [clojars.db :as db]
            [clojars.stats :as stats]
            [korma.core :refer [exec-raw]]))

(defn get-artifact [group-id artifact-id]
  (let [stats (stats/all)]
    (some-> (db/find-jar group-id artifact-id)
            (dissoc :id :created :promoted_at)
            (assoc :recent_versions (db/recent-versions group-id artifact-id)
                   :downloads (stats/download-count stats group-id artifact-id))
            (update-in [:recent_versions] (fn [versions]
                                            (map (fn [version]
                                                   (assoc version :downloads (stats/download-count stats group-id artifact-id (:version version))))
                                                 versions))))))

(defn jars-by-groupname [groupname]
    (exec-raw [(str
              "select j.*, j2.version as latest_release "
              "from jars j "
              ;; Find the latest version
              "join "
              "(select jar_name, max(created) as created "
              "from jars "
              "group by group_name, jar_name) l "
              "on j.jar_name = l.jar_name "
              "and j.created = l.created "
              ;; Find the latest release
              "join "
              "(select jar_name, max(created) as created "
              "from jars "
              "where version not like '%-SNAPSHOT' "
              "group by group_name, jar_name) r "
              "on j.jar_name = r.jar_name "
              ;; Join with latest release
              "join "
              "(select jar_name, created, version from jars) as j2 "
              "on j2.jar_name = j.jar_name "
              "and j2.created = r.created "
              "where j.group_name = ? "
              "order by j.group_name asc, j.jar_name asc")
             [groupname]]
              :results))

(defroutes handler
  (context "/api" []
    (GET ["/groups/:group-id", :group-id #"[^/]+"] [group-id]
      (let [stats (stats/all)]
        (-> (jars-by-groupname group-id)
            (->> (map (fn [jar]
                        (-> jar
                            (rename-keys {:version :latest_version})
                            (dissoc :id :created :promoted_at)
                            (assoc :downloads (stats/download-count stats group-id (:jar_name jar)))))))
            response)))
    (GET ["/artifacts/:artifact-id", :artifact-id #"[^/]+"] [artifact-id]
      (response (get-artifact artifact-id artifact-id)))
    (GET ["/artifacts/:group-id/:artifact-id", :group-id #"[^/]+", :artifact-id #"[^/]+"] [group-id artifact-id]
      (response (get-artifact group-id artifact-id)))
    (GET "/users/:username" [username]
      (response (-> {:groups (db/find-groupnames username)})))
    (ANY "*" _
      (not-found nil))))

(def routes
  (-> handler
      (wrap-restful-response :formats [:json :edn :yaml :transit-json])))
