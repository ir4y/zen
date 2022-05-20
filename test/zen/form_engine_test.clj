(ns zen.form-engine-test
  (:require [matcho.core :as matcho]
            [zen.validation]
            [clojure.test :refer [deftest is testing]]
            [zen.effect]
            [zen.core]))

(def zen-sdc
  '{ns sdc

    doc  {:zen/tags #{zen/tag}}
    form-engine {:zen/tags #{zen/tag}}

    Document
    {:zen/tags #{zen/schema}
     :type zen/map}

    Form
    {:zen/tags #{zen/schema zen/tag}
     :type zen/map
     :zen/desc "form for document"
     :require #{:document :engine}
     :keys {:document {:type zen/symbol :tags #{doc}}
            :engine {:type zen/symbol :tags #{form-engine}}
            }}

    Hiccup
    {:zen/tags #{form-engine zen/schema zen/tag}
     :type zen/map
     :require #{:hiccup-layout :engine}
     :keys {
            :hiccup-layout {:type zen/any}
            :engine {:const {:value Hiccup}}
            }
     }
    })

(deftest define-form
  (def form '{ns my-form
              import #{sdc}

              MyDocument
              {:zen/tags #{zen/schema sdc/doc}
               :type zen/map
               :confirms #{sdc/Document}
               :keys {:name {:type zen/string}}}

              MyForm
              {:zen/tags #{sdc/Form sdc/Hiccup}
               :document MyDocument
               :engine sdc/Hiccup
               :hiccup-layout [:field {:bind :name}]}
              })

  (def tctx (zen.core/new-context))
  (do (zen.core/load-ns tctx zen-sdc)
      (zen.core/load-ns tctx form)
      nil)
  (matcho/match @tctx {:errors nil?})
  )
