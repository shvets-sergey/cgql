(ns bearz.cgql.core
  (:require [clojure.walk :refer [prewalk prewalk-replace]]
            [com.stuartsierra.dependency :as dep]
            [clojure.spec.alpha :as s]
            [clojure.repl :refer [doc]]
            [clojure.pprint :refer [pprint]]
            [clojure.string :refer [capitalize]]))

;; THIS IS A WORK IN PROGRESS, USE IT ON YOUR OWN RISK. I'M GOING TO UPDATE IT SOON, WITH MORE STRUCTURE AND INSTRUCTIONS
;; 
;; @author bearz

;; Specs
(s/def ::is-spec? (s/or :spec s/spec? :keyword keyword?))   ;; spec can be inline or be a link to keyword in registry

;; Data-model validation
(s/def :bearz.cgql.method-spec/doc string?)
(s/def :bearz.cgql.method-spec/args ::is-spec?)
(s/def :bearz.cgql.method-spec/ret ::is-spec?)
(s/def ::method-map (s/keys :req-un [:bearz.cgql.method-spec/ret]
                            :opt-un [:bearz.cgql.method-spec/doc :bearz.cgql.method-spec/args]))
(s/def :bearz.cgql.resource-spec/methods (s/map-of keyword? ::method-map))
(s/def ::resource-map (s/keys :req-un [:bearz.cgql.resource-spec/methods]))
(s/def ::data-model (s/map-of keyword? ::resource-map))

;; Resolve map
(s/def :bearz.cgql.resolve-map/name-method-map (s/map-of keyword? fn?))
(s/def ::resolve-map (s/map-of keyword? :bearz.cgql.resolve-map/name-method-map))


(defn- is-named-func-form?
  "Reads 'form' and checks that it is a function-call form and on eval will call function with 'func-name'"
  [func-name form]
  (and (list? form) (symbol? (first form)) (= (str (first form)) func-name)))


(defn- prewalk-replace-and-extract
  "Implements clojure's native prewalk functionality, finding and replacing in provided 'form' sub-forms
   that match 'predicate?' with result of 'placeholder-generator' applied to an optional function of preprocess-replaced.

   Returns list (updated-form, replaced-map), where
     - updated-form: the same structure that passed to 'form' but with sub-forms that match 'predicate?'
           replaced with a return value of 'placeholder-generator'
     - replaced-map: uses 'placeholder-generator's return value as key and replaced form, optionally,
           changed with 'preprocess-replaced' function, as a value"
  ([predicate? preprocess-form placeholder-generator form]
   (let [replaced-map (atom {})                             ;; todo can atom be replaced somehow? create map-prewalk?
         updated-form (prewalk (fn [form]
                                 (if (predicate? form)
                                   (let [form-transformed (preprocess-form form)
                                         placeholder (placeholder-generator form-transformed)]
                                     (swap! replaced-map assoc placeholder form-transformed)
                                     placeholder)
                                   form))
                               form)]
     (list updated-form @replaced-map)))

  ([predicate? placeholder-generator form]
   (prewalk-replace-and-extract predicate? (fn [val] val) placeholder-generator form)))


(defn- contains-cgql-query?
  "Checks if this clojure form contains cgql-query. Returns true/false"
  [form]
  (let [[_ replace-map] (prewalk-replace-and-extract
                          (partial is-named-func-form? "$cgql")
                          (fn [f] (gensym))
                          form)]
    (> (count replace-map) 0)))

(def unique-id (gensym "cgql"))

(defn- cgql-placeholder
  "Cgql do a lot of form transformation for a query. This is universal function to generate placeholder,
  that is unlikely to shadow request key"
  [name]
  (keyword (str unique-id "-" (clojure.core/name name))))


(defn- extract-cgql-ref
  "Takes a cgql form like ($cgql [:resource :method] [($cgql-ref :test)] and searches for references in it.
  If found, then replaces reference with a name of query to and saves replacement.
  Returns updated form and list of names of queries that current one depends on"
  [form]
  (let [[updated-form replaced-map] (prewalk-replace-and-extract
                                      (partial is-named-func-form? "$cgql-ref")
                                      #(cgql-placeholder (nth % 1))
                                      form)]
    (list updated-form (or (keys replaced-map) []))))


(defn- $cgql-impl
  "Maps and executes correct logic function to the requested cgql query"
  ;; todo document and clean up
  ;; todo merge to the "execute" of QueryActions
  ([data-model qry-ref params]
   (let [[resource method] qry-ref
         method-desc (get-in data-model [:resources resource :methods method])
         func (get method-desc :impl)
         params-spec (get method-desc :args nil)
         ret-spec (get method-desc :ret nil)]
     (if (and (not (nil? params-spec)) (not (s/valid? params-spec params)))
       (println "Args for function " method " don't conform to spec: " (s/describe params-spec))
       (let [result (apply func params)]
         (if (and (not (nil? ret-spec)) (not (s/valid? ret-spec result)))
           (println "Return from function " method " don't conform to spec: " (s/describe ret-spec))
           result))))))

(defn- replace-cgql-parent
  ;; todo document and validation
  [related-query-map result]
  (let [[[filter cgql-query]] (into [] related-query-map)  ;; little ugly, but does the job todo make better
        cgql-query-map (nth cgql-query 1)
        [updated-with _] (prewalk-replace-and-extract
                           (partial is-named-func-form? "$cgql-parent")
                           (fn [f] result)
                           (:with cgql-query-map))
        evaled-with (eval updated-with)]
    [filter (list '$cgql (assoc cgql-query-map :with evaled-with))]))

(defn- replace-context-params
  ;; todo document
  [context form]
  (let [[updated-form _] (prewalk-replace-and-extract
                           (partial is-named-func-form? "$cgql-context")
                           ;; todo how to validate context properly?
                           (fn [f] (get context (nth f 1)))
                           form)]
    updated-form))

(defn- apply-filter
  ;; todo document
  [result filter]
  ;; todo rewrite as a spec for filter! and then use case/conform
  (if (keyword? filter)
    [filter (get result filter)]
    (replace-cgql-parent filter result)))

(defn- get-filtered-result
  ;; todo document
  [filters-array result]
  (into {} (map (fn [filter] (apply-filter result filter)) filters-array)))

;; Query record and protocol
;; Serves a purpose of better structure for a code and avoiding ugly maps with wrappers around and lists
;; representing god knows what

(defprotocol QueryActions
  "Represents internal actions available on cgql queries that help implement turn query into result"
  (make-placeholder [this] "Generates a unique placeholder to put into structure and then replace later")
  (resolve-dependencies [this results-map]
    "Replaces dependency params with results resolving them to a value. Returns new instance of Query with params
    replaced or Query itself if nothing to replace.")
  (execute [this data-model] "Runs a query and returns itself with result field filled")
  (filter-result [this]
    "Returns result filtered by return-filter. Can be a structure with data or structure with
    nested query if has related queries"))

(defrecord Query [name method params dependencies result return-filter]

  QueryActions
  (make-placeholder [this]
    (cgql-placeholder name))

  (resolve-dependencies [this result-map]
    (loop [[dep-name & deps-remaining] dependencies
           resolved-results {}]
      (if-not (nil? dep-name)
        (let [dep-result (get result-map dep-name)]
          (recur deps-remaining
                 (if (nil? dep-result)
                   resolved-results
                   (assoc resolved-results dep-name (:result dep-result)))))
        (-> this
            (assoc :params (eval (prewalk-replace resolved-results (:params this)))) ;; todo is this eval dangerous?
            (assoc :dependencies (into #{} (remove (into #{} (keys resolved-results)) (:dependencies this))))))))

  (execute [this data-model]
    ;; todo implement
    (let [result (apply $cgql-impl [data-model method params])]
      (assoc this :result result)))

  (filter-result [this]
    ; todo validation that fields in filter do exist!
    (if (nil? return-filter)
      (:result this)
      (let [result (:result this)]
        (if (sequential? result)
          ;; todo validate that it conforms to a spec
          (into [] (map (partial get-filtered-result return-filter) result))
          (get-filtered-result return-filter result))))))

(defn- extract-query-form
  "Takes a query form and extracts it into a Query record."
  [form context]
  ;; todo validation?
  (let [qry-map (nth form 1)
        {name          :name
         method        :query
         params-pre    :with
         return-filter :return
         after-pre     :after
         :or           {name       (keyword (gensym "request"))
                        params-pre []
                        after-pre  []}} qry-map
        [params-replaced-ref params-deps] (extract-cgql-ref params-pre)
        params (replace-context-params context params-replaced-ref)
        after (map cgql-placeholder after-pre)
        dependencies (into #{} (concat params-deps after))]
    (->Query name method params dependencies nil return-filter)))


(defn- build-cgql-request-map
  "Takes any valid cgql query and splits it into response-structure (which is structure requested by client where
  queries are replaced with placeholders generated for them) and query-map, where keys are placeholders from
  response-structure and values contain corresponding query records.

   Example:
   {:project ($cgql :project_1 [:project :fetch] [7])
    :tasks ($cgql :tasks_list [:task :list] [(:id ($cgql-ref :project_1))])}

   Returns:
   ({:project :project_1, :tasks :tasks_list}
    {:project_1 #Query1
     :tasks_list #Query2})
   "
  [qry context]
  (prewalk-replace-and-extract
    (partial is-named-func-form? "$cgql")
    #(extract-query-form % context)
    make-placeholder
    qry))


(defn- order-requests
  ;; todo handle error when graph is circular
  "Takes a 'req-dep-map' that contains name of request as a key and list of names of requests that it depends on.
  Returns ordered seq in which requests should be executed to satisfy all dependencies"
  [req-dep-map]
  (let [fake-root (keyword (gensym))
        dep-graph (reduce
                    (fn [graph [node dep]]
                      (dep/depend graph node dep))
                    (dep/graph)
                    (mapcat
                      (fn [[qry-name deps]]
                        ;; add a fake root node to add all queries to dependency graph
                        (map (fn [dep-name] [qry-name dep-name]) (conj deps fake-root)))
                      req-dep-map))]
    (drop 1 (dep/topo-sort dep-graph))                      ;; drop fake-root added earlier
    ))


(defn- execute-subqueries
  "Takes 'qry-map' in a format that `build-cgql-request-map` accepts, figures out correct order and
  best way to execute each subquery. Returns a map with responses for each subquery using name of
  subquery as a key."
  [qry-map data-model]
  (println "Executing something!" qry-map)
  (let [qry-dependency-map (into {} (map (fn [[req-name query]] [req-name (:dependencies query)]) qry-map))
        requests-order (order-requests qry-dependency-map)]
    (reduce
      (fn [results-map req-name]
        (assoc results-map req-name
                           (-> (get qry-map req-name)
                               (resolve-dependencies results-map)
                               (execute data-model))))
      {}
      requests-order)))


(defn execute-query
  "Executes a cgql query. Requires data-model object describing API for this particular system, resolve-map that maps
   data to the actual business-logic functions on server-side and query itself.

   It is recommended to wrap this function with partial or add it as component or mount to not pass around data-model
   and resolve-map every time.

   Returns response in the format that query specified, executing cgql subqueries in the correct order and replacing
   them with values."
  ;; todo handle errors (with dire and slingshot, make a supervise function to abstract error handling logic)
  ;; we can and should implement a few error types to send more specific responses to client.
  ;; todo add specs
  ;; todo write with pipe or threading macros?
  ;; todo eval is a big security risk, look for some safe-eval.
  [data-model context query]
  (let [[response-structure query-map] (build-cgql-request-map query context)
        executed-query (execute-subqueries query-map data-model)
        filtered-results (into {} (map (fn [[key query]] [key (filter-result query)]) executed-query))
        response-candidate (prewalk-replace filtered-results response-structure)]
    (if (contains-cgql-query? response-candidate)
      (execute-query data-model context response-candidate)
      response-candidate)))


;; todo temporary macro to replace execute-query function with more pretty one. For now proving
;; concept of runtime checking
(defmacro cgql-handler
  [{:keys [data-model context bind-to]} query & body]
  ; todo return validation
  ;(if (not (s/valid? ::data-model (eval data-model)))
  ;  (throw (AssertionError.
  ;           (str "Invalid format for data-model: \n" (s/explain-str ::data-model (eval data-model))))))
  ;(if (not (s/valid? ::resolve-map (eval resolve-map)))
  ;  (throw (AssertionError. (str "Invalid format for resolve-map: \n"
  ;                               (s/explain-str ::resolve-map (eval resolve-map))))))
  `(let [~bind-to (execute-query ~data-model ~context ~query)]
     ~@body))

;(s/def ::cgql-handler-bind-map-spec (s/keys :req-un [::data-model ::resolve-map]))
;
;(s/fdef cgql-handler
;        :args (s/cat :bind-map map?
;                     :query symbol?))

;; API doc
;; set of functions to generate a nice looking api doc.
;; todo rewrite with pretty print
(defn- str-spec
  [spec]
  (if (nil? spec)
    ""
    (pr-str (s/describe spec))))

(defn- str-section
  [header doc]
  (if (empty? doc)
    ""
    (str (capitalize header) "\n" doc)))

(defn- str-method
  [method-keyword method-desc]
  (let [header (capitalize (name method-keyword))
        args-spec-str (str-spec (:args method-desc))
        ret-spec-str (str-spec (:ret method-desc))
        doc-str (:doc method-desc)]
    (str-section
      (str header "\n------------")
      (str doc-str "\n\n" (str-section "Arguments spec:" args-spec-str) "\n\n"
           (str-section "Returns" ret-spec-str)))))

(defn str-doc
  "Returns api doc from the description provided in data-model. Depends on arguments can provide api doc for all
  methods, resource or one particular method"
  ([data-model]
   (let [all-resources-str (reduce
                             (fn [doc resource] (str doc "\n\n" (str-doc data-model resource)))
                             ""
                             (keys (:resources data-model)))]
     (str "API:" all-resources-str)))

  ([data-model resource]
   (let [all-methods-str (reduce
                           (fn [doc [name method-desc]] (str doc "\n\n" (str-method name method-desc)))
                           ""
                           (get-in data-model [:resources resource :methods]))]
     (str "Methods for resource:" (capitalize (name resource)) "\n" all-methods-str)))

  ([data-model resource method]
   (str (capitalize (name resource)) ":" (str-method method (get-in data-model [:resources resource :methods method])))))
