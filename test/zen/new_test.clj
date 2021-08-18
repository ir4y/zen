(ns zen.validation-test
  (:require [matcho.core :as matcho]
            [clojure.test :refer [deftest]]
            [clojure.walk]
            [clojure.string :as str]
            [zen.core]))

;; (defmacro match-schema [sch data errs]
;;   `(let [res# (sub/validate-schema ztx ~sch ~data)]
;;      (matcho/match (:errors res#) ~errs)
;;      (:errors res#)))

;; (defmacro valid-schema [sch data]
;;   `(let [res# (sub/validate-schema ztx ~sch ~data)]
;;      (is (empty? (:errors res#)))
;;      (:errors res#)))

(def replacements
  {'zen.test/empty? empty?
   'zen.test/nil? empty?})

(defn translate-to-matcho [match]
  (clojure.walk/postwalk (fn [x] (get replacements x x)) match))

;; (translate-to-matcho {:key 'zen.test/empty?})

(deftest test-validation
  (def ztx (zen.core/new-context {:unsafe true}))
  ;; (zen.core/read-ns ztx 'zen.all-tests)
  (zen.core/read-ns ztx 'zen.require-test)
  (zen.core/read-ns ztx 'zen.keys-test)
  (zen.core/read-ns ztx 'zen.schema-key-test)
  (zen.core/read-ns ztx 'zen.case-test)

  (doseq [case (zen.core/get-tags ztx 'zen.test/case)]
    (doseq [{desc :desc do :do match :match} (:steps case)]
      (let [res (zen.core/validate ztx #{(:schema do)} (:data do))]
        (let [matcho-res (matcho/match res (translate-to-matcho match))]
          (when-not (true? matcho-res)
            (println "## Case: " (or (:title case) (:id case)))
            (println "  validate: " desc " \n  "  (:schema do) "\n  " (:data do))
            (println (:message (last (get-in matcho-res [:results 'zen.validation-test 'test-validation]))))))))))
