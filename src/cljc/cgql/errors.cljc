(ns bearz.cgql.errors
  (:require [clojure.spec.alpha :as s]))

(defprotocol CgqlError
  "Protocol for representing cgql errors. Extend this protocol whenever you want to define an error
  for cgql query.

  General idea for handling cgql errors is to fire them using slingshot's throw+ for any exception situation
  during processing a request. 'execute-query' function (or its wrapper) should be wrapped in try+/catch statement
  and error processing can be isolated to this small piece of code and at the same time keep our functions simple
  doing one thing and not passing around some nil values, making some hidden logic or doing monads.
  See `http://michaeldrogalis.tumblr.com/post/40181639419/trycatch-complects-we-can-do-so-much-better` for the
  background behind this.

  Cgql also provides some default errors that should cover most of the cases, plus cgql errors itself."

  (http-error-code [this]
    "Provides error code for this type of error. Needed for compatibility with client-side
     libraries that rely on http error codes")

  (human-readable-error [this]
    "Returns a human readable error. Target message for the client-side consumer of your error, not the end-customer
    of someone's service. Developer of a service will want to control language of error presented to end-user.")

  (error-data [this]
    "Provides data for error. Mostly makes sense to use when sending specs data for validation or if you want to
    do a self-correction."))


(defn cgql-error?
  "Predicate to check if object satisfies CgqlError protocol"
  [type]
  (satisfies? CgqlError type))


(defrecord SpecMalformedError [object spec]

  CgqlError
  (http-error-code [this]
    400)

  (human-readable-error [this]
    (str "Object in request fails spec validation:\n" (s/explain-str spec object)))

  (error-data [this]
    (s/explain-data spec object)))


;; todo define more error types
;; MethodNotExist 405 – when someone calls a method that isn't defined inside the data-model or resolver doesn't exist
;; ObjectNotFound 404 — when params are valid, but some object in request wasn't found.
;; RequestConflictError 409 – general error to process any conflicts during processing of a request.



