(ns cgql.cgql-demo
  (:require [clojure.walk :refer [prewalk prewalk-replace]]
            [com.stuartsierra.dependency :as dep]
            [clojure.spec.alpha :as s]
            [clojure.repl :refer [doc]]
            [clojure.pprint :refer [pprint]]
            [clojure.string :refer [capitalize]]
            [bearz.cgql.core :refer [execute-query str-doc cgql-handler]]))

;; NOTE: now all in one file to work out queries etc. read-str should take care of serialization later when it
;; will be broke down to server and client

;; Specs
;; We use clojure.spec to validate all of the inputs and outputs before sending data up and down the wire.
;; Make sure to namespace all the specs with unique names so you can reuse them for more then one value.
;; Rule of thumb: if there are too many specs then you probably not keeping your API consistent.

;; global specs
(s/def ::num-id int?)

;; project specs
(s/def :project-spec/id ::num-id)
(s/def :project-spec/name (s/and string? #(< (count %) 24)))
(s/def :project-spec/owner (s/and string? #(< (count %) 50)))
(s/def :project-spec/project (s/keys :opt-un [:project-spec/id
                                              :project-spec/name
                                              :project-spec/description
                                              :project-spec/owner]))

;; todo some specs can be done as in interface and then just used merge to join
(s/def :task-spec/id ::num-id)
(s/def :task-spec/name (s/and string? #(< (count %) 24)))
(s/def :task-spec/owner (s/and string? #(< (count %) 50)))
(s/def :task-spec/task (s/keys :opt-un [:task-spec/id
                                        :task-spec/name
                                        :task-spec/project-id
                                        :task-spec/owner]))


;; Handlers
;;
;; Functions that are doing the actual job of retrieving and massaging data.
;; Mocks for now, but can do anything in theory
(defn project-list
  "Dumb function with no params"
  []
  (println "Called project-list")
  [{:id 1 :name "Project 1" :description "Dumb Project 1" :owner "Sergey"}
   {:id 2 :name "Project 2" :description "Dumb Project 2" :owner "Nika"}])

(defn project-fetch
  "Dumb function to illustrate function with param"
  [id]
  (println (str "Called project-fetch with id: " id))
  {:id 7 :name "Project 1" :description "Dumb Project 1" :owner "Sergey"})

(defn task-create
  "Dumb function to illustrate mutation with params and relations"
  [project-id & params]
  (println (str "Called task-create with project-id: " project-id " and params: " params))
  {:name "Task 1" :description "Do that!" :status false :project-id 1})

(defn task-list
  "Dumb function to illustrate relations with other resource"
  [project-id]
  (println (str "Called task-list with project-id: " project-id))
  [{:id 1 :name "Task 1" :description "Do that!" :status false :project-id 1}
   {:id 2 :name "Task 2" :description "Do that!" :status false :project-id 1}
   {:id 3 :name "Task 3" :description "Do that!" :status true :project-id 1}])

(defn task-complete
  "Dumb function to illustrate update of the single object"
  [name]
  (println (str "Called task-complete with name: " name))
  {:name "Task 1" :description "Do that!" :status true :project-id 1})

(defn comments-for-tasks
  [task-id]
  (println "Called comments-for-tasks with id: " task-id)
  [{:name "Sergey" :comment "Dumb Comment 1!"}
   {:name "Sergey" :comment "Dumb Comment 2!"}
   {:name "Sergey" :comment "Dumb Comment 3!"}
   {:name "Sergey" :comment "Dumb Comment 4!"}
   {:name "Sergey" :comment "Dumb Comment 5!"}])

(defn user-fetch
  [user-id]
  (println "Called user-fetch with id: " user-id)
  {:id 4 :name "Sergey Shvets" :email "sergey@example.com"})

;; specs!
;; Guide link: https://clojure.org/guides/spec


;; Data Model
;;
;; Describes Data Model that exists in an application. Description includes:
;; * name — as a keyword to ensure uniqueness.
;; * requests – list of requests defined for this object. Requests later resolved to the actual methods with special map
;; 
;; Methods map includes:
;; * doc – for documentation
;; * ret, args – specs for return and arguments. Helps to validate return and later generate responses without actual server involved (reason behind this later.)
;; * impl – to specify method to implement. Note that you can use reader-conditionals if you want to share file between front-end and back-end (useful to do to get auto-docs.)
(def data-model
  {:resources {:project {:methods {:list  {:doc "Returns a list of available projects for authorized user"
                                           :ret (s/coll-of :project-spec/project)
                                           #?@(:clj [:impl project-list])}
                                   :fetch {:doc  "Returns one project details by its id"
                                           :args (s/spec (s/cat :id ::num-id))
                                           :ret  :project-spec/project
                                           #?@(:clj [:impl project-fetch])}} ;; how to add optional param validation?
                         :spec    :project-spec/project
                         :doc     "Some sample doc description"}
               :task    {:methods {:list     {:doc "Returns a list of tasks, optionally filtered by some project id"
                                              ;; todo add optional args (zero or more)
                                              :ret (s/coll-of :task-spec/task)
                                              #?@(:clj [:impl task-list])}
                                   :create!  {#?@(:clj [:impl task-create])}
                                   :complete {#?@(:clj [:impl task-complete])}
                                   :comments {#?@(:clj [:impl comments-for-tasks])}}
                         :spec    :task-spec/task
                         :doc     "Some sample doc description"}
               :user    {:methods {:fetch {#?@(:clj [:impl user-fetch])}}}}
   ; todo actions (make upload service as an example)
   })

(def api-doc (partial str-doc data-model))

;; Queries
;;
;; You'll need to create a handler with http-server framework of your choice (e.g. Ring). See 'query' function as example. 
;;
;; Then you can see examples of a queries that are supported. 
;; Start your query with ($cgql) and then add a map that contains actual query. Queries can be nested, but unlike GraphQL, you are free to nest any request underneath. Just use
;; $cgql-parent to refer to a parent response. Also, you can reference other queries by specifying their name in ($cgql-ref) parameter. There is a special parameter $cgql-context
;; that is filled up by a server when you need something from server-side (e.g. user-id from session)
;;
;; Params:
;; * name – name of a query, for internal reference (optional)
;; * query – method to query. Must reflect name inside data-model (see above)
;; * with – params for the method in query. Accepts any Clojure(Script) values as well as references like $cgql-ref, $cgql-parent, $cgql-context.
;; * return – list of fields to return. This one support nesting, just put a map instead of keyword with nested query to do nesting. Nesting can be unlimited (almost!). See examples.

(defn query
  [qry]
  (cgql-handler {:data-model data-model
                 :context    {:user-id 1}
                 :bind-to    result}
                qry
                (let [res result]
                  (println "Query is: " qry)
                  (println "Result is: " res)
                  (println)
                  (println)
                  res)))

;;; Query to fetch one resource with no params
(query '($cgql {:query [:project :list]}))

;;; Query with filtered fields
(query '($cgql {:query  [:project :list]
                :return [:id :name]}))
;
;;; Named query
(query '($cgql {:name  :project_1
                :query [:project :fetch]
                :with  [2]}))
;
;;; Query with control over the structure
(query {:project '($cgql {:name  :project_1
                          :query [:project :fetch]
                          :with  [1]})})
;
;;; Mutating query
(query '($cgql {:query [:task :create!]
                :with  [1 "name" "description" false]}))
;;; todo you can clearly see that validation of params needed
;
;;; Query to fetch related resources, passing result of one query to the other
(query {:project '($cgql {:name  :project_1
                          :query [:project :fetch]
                          :with  [7]})
        :tasks   '($cgql {:name   :tasks_list
                          :query  [:task :list]
                          :with   [(:id ($cgql-ref :project_1))]
                          :return [:id :name :status]})})
;
;;; Query that does some calculations on the server before sending response back.
;(query '(count ($cgql {:query [:project :list]})))


;; query with nested structures!
(query '($cgql {:query  [:project :list]
                :name   :project-query
                :return [:id
                         :name
                         {:tasks ($cgql {:query  [:task :list]
                                         :with   [(:id ($cgql-parent))]
                                         :return [:id
                                                  :name
                                                  ]}
                                        )}]}))

;; even more nesting!
(query '($cgql {:query  [:project :list]
                :name   :project-query
                :return [:id
                         :name
                         {:tasks ($cgql {:query  [:task :list]
                                         :with   [(:id ($cgql-parent))]
                                         :return [:id
                                                  :name
                                                  {:comments ($cgql {:query [:task :comments]
                                                                     :with  [(:id ($cgql-parent))]})}]}
                                        )}]}))


;; query with context
(query '($cgql {:query [:user :fetch]
                :with  [($cgql-context :user-id)]}))