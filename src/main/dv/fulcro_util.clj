(ns dv.fulcro-util
  (:require
    [clojure.java.io :as io]
    [clojure.spec.alpha :as s]
    [clojure.edn :as edn]
    [com.fulcrologic.fulcro.components :as c :refer [defsc]]
    [com.fulcrologic.fulcro.mutations :refer [defmutation]]
    [com.fulcrologic.guardrails.core :refer [>defn => |]])
  (:import
    [java.util UUID]
    [java.io PushbackReader IOException]))

(defn error [& msg]
  (RuntimeException. (apply str msg)))

(s/def ::props (s/coll-of simple-symbol? :count 1))

(comment (s/conform ::props '[params]))

(s/def ::arrow #(= '=> %))
(comment (s/conform ::arrow '=>))

(s/def ::thread-forms (s/* any?))
(comment
  (s/conform ::thread-forms '(update-math* (assoc-math* :math/game-state :not-running)))
  (s/conform ::thread-forms '((assoc-math* :math/game-state :not-running)))
  (s/explain ::thread-forms '((assoc-math* :math/game-state :not-running))))

(s/def ::body (s/cat :thread-forms ::thread-forms))
(s/def ::params (s/cat :name simple-symbol? :props ::props :body ::body))

(defn id? [id]
  (or (keyword? id) (uuid? id)))

;; [prop val]
(s/def ::ident (s/tuple qualified-keyword? id?))
(s/def ::coll-of-idents (s/coll-of ::ident :kind vector?))
(defn coll-of-idents? [v] (s/valid? ::coll-of-idents v))

(defn ref?
  ([v] (s/valid? ::ident v))
  ([kw-id v]
   (and (s/valid? ::ident v)
     (= (first v) kw-id))))

(>defn ->ident
  "Given a kw that is the id prop, and a map or id, return ident."
  ([kw]
   [keyword? => fn?]
   (fn [m] (->ident kw m)))
  ([kw v]
   [keyword? (s/or :id id? :m map?) => ::ident]
   [kw (if (map? v) (kw v) v)]))

(defn ref->id
  "ident [:prop id] => id"
  [v] (cond-> v (s/valid? ::ident v) second))

(defn ident->id
  "ident [:prop id] => id"
  [v] (cond-> v (s/valid? ::ident v) second))

(comment
  (def body '(=> [params] (assoc-math* :math/game-state :not-running)))
  (s/conform ::body body)
  (s/explain ::body body))

(defn conj-vec
  "Returns a map
   adds an element val to the given entity at the prop fkw"
  [entity fkw val]
  (update entity fkw #(conj (or % []) val)))

(defn conj-set [entity fkw val]
  (update entity fkw #(conj (or (set %) #{}) val)))

(defmacro defm
  "define a mutation for the simple case of threading state through forms.
  (defm pause-game [_] (assoc-math* :math/game-state :not-running))"
  [name props & body]
  (let [conformed (s/conform ::body body)
        {:keys [thread-forms]} conformed
        props     (s/assert ::props props)]
    `(com.fulcrologic.fulcro.mutations/defmutation ~name ~props
       (~'action ~'[{:keys [state]}]
         (cljs.core/swap! ~'state
           (cljs.core/fn [s#] (-> s# ~@thread-forms)))))))

(s/fdef defm :args ::params :ret any?)

(comment
  (macroexpand-1 '(defm pause-game [_] (assoc-math* :math/game-state :not-running)))
  (macroexpand-1 '(defm pause-game [_]
                    (assoc-math* :math/game-state :not-running)
                    (assoc-math* :math/game-state :not-running)))
  )

(s/def ::state simple-symbol?)

(defmacro ->s!
  "(->s! state
     (assoc )
     (update)
     etc
     "
  [state & thread-forms]
  `(swap! ~state
     (fn [s#] (-> s# ~@thread-forms))))

(s/def ::->params (s/cat :state ::state :thread-forms ::thread-forms))
(s/fdef ->s! :args ::->params :ret any?)
(comment
  (macroexpand-1
    '(->s! state
       reset-cells*
       (assoc-math* :math/game-state :running)
       (assoc-math* :math/time-left countdown-seconds))))

(defmacro deflocalmutation
  "name and body, fn that takes state map and props + env in one map
  defs a fn that performs a transact!! this for you"

  [name f]
  `(let []
     (defmutation ~name
       [params#]
       (~'action [{:keys [~'state ~'ref] :as env#}]
         (do
           ;(log/info "IN MUTATION " ~name " params: " params#)
           (swap! ~'state
             (fn [s#]
               (~f s# (merge env# params#)))))))

     (defn ~(symbol (str name "!"))
       ([this#]
        (c/transact!! this# [(~name)]))
       ([this# props#]
        (assert (map? props#))
        (c/transact!! this# [(~name props#)])))))

;; todo the idea here is to generate a component for the usual form save interaction
;; components that ask for all the props and activate state machines that handle the logic.
;; needs more time in the oven
(defmacro def-return-entity
  [name ident fields]
  `(defsc ~name [_# _#]
     {:query (fn [_#]
               (conj ~fields :server/message :server/error?))
      :ident ~ident}))

(comment
  (macroexpand-1
    '(def-return-entity TaskItemReturn
       [:component/id :task-item-return]
       app/all-task-fields)))



(defn server-error [msg]
  {:server/message msg
   :server/error?  true})

(>defn uuid
  "Without args gives random UUID.
  With args, builds UUID based on input (useful in tests)."
  ([] [=> uuid?] (UUID/randomUUID))
  ([int-or-str]
   [(s/or :i int? :s string?) => uuid?]
   (if (int? int-or-str)
     (UUID/fromString
       (format "ffffffff-ffff-ffff-ffff-%012d" int-or-str))
     (UUID/fromString int-or-str))))

(defn load-edn
  "Load edn from an io/reader source
  Tries to read as resource first then filename."
  [source]
  (try
    (with-open [r (io/reader (io/resource source))] (edn/read (PushbackReader. r)))
    (catch IOException e
      (try
        ;; try just a file read
        (with-open [r (io/reader source)] (edn/read (PushbackReader. r)))
        (catch IOException e
          (printf "Couldn't open '%s': %s\n" source (.getMessage e)))
        (catch RuntimeException e
          (printf "Error parsing edn file '%s': %s\n" source (.getMessage e))))
      (printf "Couldn't open '%s': %s\n" source (.getMessage e)))
    (catch RuntimeException e
      (printf "Error parsing edn file '%s': %s\n" source (.getMessage e)))))


(defmacro validity-check
  "Used in pathom resolvers and mutations."
  [& args]
  `(when-let
     [msg# (cond ~@args)]
     (server-error msg#)))

(comment
  ;(macroexpand-1 '(validity-check
  ;                  existing-habit? "A habit with that description already exists"
  ;                  (not current-user) "You must be logged in to create a habit."
  ;                  (not (s/valid? ::goals/habit props)) "Habit is invalid"
  ;                  (not (every? #(s/valid? ::goals/task %) tasks)) "Invalid tasks for habit."))
  )
