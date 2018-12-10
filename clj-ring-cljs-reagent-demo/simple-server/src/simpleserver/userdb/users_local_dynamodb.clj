(ns simpleserver.userdb.users-local-dynamodb
  (:require
    [clojure.tools.logging :as log]
    [amazonica.aws.dynamodbv2 :as dynamodb]
    [environ.core :as environ]
    [simpleserver.userdb.users-service-interface :as ss-users-service-interface]
    [simpleserver.util.aws-utils :as ss-aws-utils])
  (:import (com.amazonaws.services.dynamodbv2.model AmazonDynamoDBException)))



;; NOTE: We don't use incremental user ids since it is a bit anti-pattern in DynamoDB (since email is the hash key). So, we create uuid for userid.
(defn uuid
  []
  (.toString (java.util.UUID/randomUUID)))

;; NOTE: We are skipping the pagination here since this is an exercise and
;; we know that the query results will always be less than 1MB.
;; See: https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Query.html#Query.Pagination
;; In real life we should anticipate pagination and also test it.

(defrecord Env-local-dynamodb [ssenv]
  ss-users-service-interface/UsersServiceInterface

  (email-already-exists?
    [ssenv email]
    (log/debug (str "ENTER email-already-exists?, email: " email))
    (let [my-env (environ/env :my-env)
          my-table (str "sseks-" my-env "-users")
          ret (dynamodb/query (ss-aws-utils/get-dynamodb-config)
                              :table-name my-table
                              :select "COUNT"
                              :key-conditions {:email {:attribute-value-list [email]
                                                       :comparison-operator  "EQ"}})
          count (ret :count)]
      (not (= count 0))))

  (add-new-user
    [ssenv email first-name last-name password]
    (log/debug (str "ENTER add-new-user, email: " email))
    (let [already-exists (ss-users-service-interface/email-already-exists? ssenv email)]
      (if already-exists
        (do
          (log/debug (str "Failure: email already exists: " email))
          {:email email, :ret :failed :msg "Email already exists"})
        (let [my-env (environ/env :my-env)
              my-table (str "sseks-" my-env "-users")
              hashed-password (str (hash password))
              ret (try
                    (dynamodb/put-item (ss-aws-utils/get-dynamodb-config)
                                       :table-name my-table
                                       :item {:userid    (uuid)
                                              :email     email
                                              :firstname first-name
                                              :lastname  last-name
                                              :hpwd      hashed-password})
                    (catch AmazonDynamoDBException e {:email email,
                                                      :ret   :failed
                                                      :msg   (str "Exception occured: " (.toString e))}))]
          ; If ret was empty then no errors.
          (if (empty? ret)
            {:email email, :ret :ok}
            ret)))))

  (credentials-ok?
    [ssenv email password]
    (log/debug (str "ENTER credentials-ok?, email: " email))
    (let [my-env (environ/env :my-env)
          my-table (str "sseks-" my-env "-users")
          ret (dynamodb/query (ss-aws-utils/get-dynamodb-config)
                              :table-name my-table
                              :select "ALL_ATTRIBUTES"
                              :key-conditions {:email {:attribute-value-list [email]
                                                       :comparison-operator  "EQ"}})
          users (ret :items)]
      (if (empty? users)
        false
        (let [hashed-password (:hpwd (first users))]
          (= hashed-password (str (hash password)))))))

  (get-users
    [ssenv]
    (log/debug (str "ENTER get-users"))
    (let [ret (dynamodb/scan (ss-aws-utils/get-dynamodb-config)
                             :table-name "sseks-dev-users")
          items (ret :items)]
      (reduce (fn [users user]
                (assoc users (user :userid)
                             {:userid          (user :userid)
                              :email           (user :email)
                              :first-name      (user :firstname)
                              :last-name       (user :lastname)
                              :hashed-password (user :hpwd)}))
              {}
              items))))



