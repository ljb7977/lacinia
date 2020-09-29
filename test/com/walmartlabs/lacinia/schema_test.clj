; Copyright (c) 2017-present Walmart, Inc.
;
; Licensed under the Apache License, Version 2.0 (the "License")
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns com.walmartlabs.lacinia.schema-test
  "Tests schema functions."
  (:require
    [clojure.test :refer [deftest testing is are try-expr do-report]]
    [com.walmartlabs.test-reporting :refer [reporting]]
    [clojure.spec.alpha :as s]
    [com.walmartlabs.lacinia.schema :as schema]
    [com.walmartlabs.lacinia.executor :as executor]
    [com.walmartlabs.lacinia.util :as util]
    [com.walmartlabs.test-utils :refer [is-thrown expect-exception execute]]
    [clojure.string :as str]
    [clojure.pprint :as pprint]))

(defmacro is-error?
  [form]
  `(let [tuple# (try-expr "Invoking enforcer." ~form)]
     (when-not (-> tuple# second some?)
       (do-report {:type    :fail
                   :message "Expected some errors in the resolved tuple"}))))

(deftest schema-shape
  (testing "schema with not required field"
    (let [s {:objects
             {:person
              {:fields {:foo {:type 'String}}
               :bar "I'm extra"}}}]
      (is (seq (schema/compile s))
          "should compile schema without any problems"))))

(def schema-object-references-unknown-interface
  {:interfaces
   {:fred
    {}

    :barney
    {}}

   :objects
   {:dino
    {:implements [:fred :barney :bam_bam :pebbles]
     :fields {}}}})

(def schema-references-unknown-type
  {:interfaces
   {:fred
    {}

    :barney
    {}}

   :objects
   {:dino
    {:implements [:fred :barney]
     :fields {:dinosaur {:type :raptor}}}}})

(def schema-generated-data
  [:one :two :three])

(defn schema-generated-resolver [context args value]
  (keys (executor/selections-tree context)))

(def schema-generated-lists
  {:objects
   (into {}
     (for [f schema-generated-data]
       [f {:fields {:name {:type 'String}}}]))
   :queries
   (into {}
     (for [f schema-generated-data]
       [f {:type `(~'list ~f)
           :resolve :schema-generated-resolver}]))})

(deftest invalid-schemas
  (expect-exception
    "Object `dino' extends interface `pebbles', which does not exist."
    {:object {:category :object
              :fields {}
              :implements [:fred
                           :barney
                           :bam_bam
                           :pebbles]
              :type-name :dino}
     :schema-types {:interface [:barney
                                :fred]
                    :object [:Mutation
                             :Query
                             :Subscription
                             :dino]
                    :scalar [:Boolean
                             :Float
                             :ID
                             :Int
                             :String]}}
    (schema/compile schema-object-references-unknown-interface))

  (expect-exception
    "Field `dino/dinosaur' references unknown type `raptor'."
    {:field-name :dino/dinosaur
     :schema-types {:interface [:barney
                                :fred]
                    :object [:Mutation
                             :Query
                             :Subscription
                             :dino]
                    :scalar [:Boolean
                             :Float
                             :ID
                             :Int
                             :String]}}
    (schema/compile schema-references-unknown-type))

  (is (schema/compile
        (util/attach-resolvers
          schema-generated-lists
          {:schema-generated-resolver schema-generated-resolver}))))

(deftest printing-support
  (let [compiled-schema (schema/compile {})
        as-map (into {} compiled-schema)]
    (is (= "#CompiledSchema<>"
           (pr-str compiled-schema)))

    (is (= "#CompiledSchema<>"
           (pprint/write compiled-schema :stream nil)))

    (binding [schema/*verbose-schema-printing* true]
      (is (= (pr-str as-map)
             (pr-str compiled-schema)))

      (is (= (pprint/write as-map :stream nil)
             (pprint/write compiled-schema :stream nil))))))

(defmacro is-compile-exception
  [schema expected-message]
  `(is-thrown [e# (schema/compile ~schema)]
     (let [msg# (.getMessage e#)]
       (reporting {:message msg#}
         (is (str/includes? msg# ~expected-message))))))

(deftest types-must-be-valid-ids
  (is-compile-exception
    {:objects {:not-valid-id {:fields {:id {:type :String}}}}}
    "must be a valid GraphQL identifier"))

(deftest field-names-must-be-valid-ids
  (is-compile-exception
    {:queries {:invalid-field-name {:type :String
                              :resolve identity}}}
    "must be a valid GraphQL identifier"))

(deftest enum-values-must-be-valid-ids
  (is-compile-exception
    {:enums {:episode {:values [:new-hope :empire :return-of-jedi]}}}
    "must be a valid GraphQL identifier"))

(deftest requires-resolve-on-operation
  (is-compile-exception
    {:queries {:hopeless {:type :String}}}
    "should contain key: :resolve"))

(deftest typename-at-root
  (let [compiled-schema (schema/compile {})]
    (is (= {:data {:__typename :Query}}
           (execute compiled-schema "{ __typename }")))

    (is (= {:data {:__typename :Mutation}}
           (execute compiled-schema "mutation { __typename }")))))

