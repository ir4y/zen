(ns zen.v2-validation
  (:require
   [clojure.set]
   [clojure.string :as str]
   [zen.utils :as utils]))

;; TODO auto generate supported terms based on multi methods?
;; useful for introspection and documentation

(defn pretty-type [x]
  (if-let [tp (type x)]
    (str/lower-case (last (str/split (str tp) #"\.")))
    "nil"))

(def types-cfg
  {'zen/string {:fn string?
                :to-str "string"}

   'zen/number {:fn number?
                :to-str "number"}

   'zen/set {:fn set?
             :to-str "set"}

   'zen/map {:fn map?
             :to-str "map"}

   'zen/vector {:fn vector?
                :to-str "vector"}

   'zen/boolean {:fn boolean?
                 :to-str "boolean"}

   'zen/keyword {:fn keyword?
                 :to-str "keyword"}

   'zen/list {:fn list?
              :to-str "list"}

   'zen/integer {:fn integer?
                 :to-str "integer"}

   'zen/symbol {:fn symbol?
                :to-str "symbol"}

   'zen/any (constantly true)
   'zen/case (constantly true)

   ;; fn is implemented as a separate multimethod
   'zen/apply {:to-str "apply"}

   'zen/regex
   {:fn #(and (string? %) (re-pattern %))
    :to-str "regex"}})

(defmulti compile-key (fn [k ztx kfg] k))

(defmulti compile-type-check (fn [tp ztx] tp))

(defn *compile-schema [ztx schema]
  (let [rulesets (->> (dissoc schema :zen/tags :zen/desc :zen/file :zen/name)
                      (map (fn [[k kfg]] (compile-key k ztx kfg)))
                      (reduce (fn [acc {w :when rs :rules}]
                                (update acc w into rs)) {}))]
    (fn [vtx data opts]
      (->> rulesets
           (reduce (fn [vtx [pred rules]]
                     (if (or (nil? pred) (pred data))
                       (->> rules
                            (reduce (fn [vtx r] (r (assoc vtx ::type (:type schema)) data opts))
                                    vtx))
                       vtx))
                   vtx)))))

;; TODO precompile schemas tagged with specific tag on ns load
(defn get-cached [ztx schema]
  ;; TODO how performant is this call? maybe change to .hashCode
  (let [sh (hash schema)]
    (or (get-in @ztx [:compiled-schemas sh])
        (let [v (*compile-schema ztx schema)]
          (swap! ztx assoc-in [:compiled-schemas sh] v)
          v))))

(defn validate-schema [ztx schema data & [opts]]
  (let [v  (get-cached ztx schema)]
    (-> (v {:errors [] :path [] :schema [(:zen/name schema)]} data opts)
        (select-keys [:errors :effects]))))

;; TODO support schemas seq as arg
;; TODO convert errors to vector here?
(defn validate [ztx schemas data & [opts]]
  (let [sch (utils/get-symbol ztx (first schemas))]
    (-> (validate-schema ztx sch data)
        #_(update :errors #(sort-by :path %)))))

(defn add-err [vtx sch-key err & data-path]
  (let [err-type
        (if (not (contains? err :type))
          (if-let [type-str (get-in types-cfg [(::type vtx) :to-str])]
            (str  type-str "." (name sch-key))
            "primitive-type")
          (:type err))

        err*
        (-> err
            (assoc :path (into (:path vtx) data-path))
            (assoc :type err-type)
            (assoc :schema (conj (:schema vtx) sch-key)))]
    (update vtx :errors conj err*)))

(defn add-fx [vtx sch-key fx & data-path]
  (let [fx*
        (-> fx
            (assoc :path (conj (:path vtx) sch-key)))]
    (update vtx :effects conj fx*)))

(defn into* [acc v]
  (cond
    (vector? v) (into acc v)
    :else (conj acc v)))

(defn node-vtx
  ([vtx sch-path]
   (node-vtx vtx sch-path [] :no-log))
  ([vtx sch-path path]
   (node-vtx vtx sch-path path :no-log))
  ([vtx sch-path path opt]
   (cond-> {:errors []
            :path (into* (:path vtx) path)
            :schema (into* (:schema vtx) sch-path)}
     (= opt :log-visited)
     (update ::visited
             (fn [vis] (conj (or vis #{})
                             (into* (:path vtx) path)))))))

(defn merge-vtx [global-vtx *node-vtx]
  (cond-> global-vtx
    :always
    (update :errors into (:errors *node-vtx))

    (contains? *node-vtx ::visited)
    (update ::visited
            (fn [vis] (into (or vis #{}) (::visited *node-vtx))))))

(defn type-fn [sym]
  (let [type-cfg (get types-cfg sym)
        type-pred (if (fn? type-cfg) type-cfg (:fn type-cfg))]
    (fn [vtx data _]
      (if (type-pred data)
        vtx
        (let [error-msg
              {:message (str "Expected type of '" (or (:to-str type-cfg) sym)
                             ", got '" (pretty-type data))}]
          (add-err vtx :type error-msg))))))

(defmethod compile-type-check 'zen/string [_ _] (type-fn 'zen/string))
(defmethod compile-type-check 'zen/number [_ _] (type-fn 'zen/number))
(defmethod compile-type-check 'zen/set [_ _] (type-fn 'zen/set))
(defmethod compile-type-check 'zen/map [_ _] (type-fn 'zen/map))
(defmethod compile-type-check 'zen/vector [_ _] (type-fn 'zen/vector))
(defmethod compile-type-check 'zen/boolean [_ _] (type-fn 'zen/boolean))
(defmethod compile-type-check 'zen/list [_ _] (type-fn 'zen/list))
(defmethod compile-type-check 'zen/keyword [_ _] (type-fn 'zen/keyword))
(defmethod compile-type-check 'zen/any [_ _] (type-fn 'zen/any))
(defmethod compile-type-check 'zen/integer [_ _] (type-fn 'zen/integer))
(defmethod compile-type-check 'zen/symbol [_ _] (type-fn 'zen/symbol))
(defmethod compile-type-check 'zen/regex [_ _] (type-fn 'zen/regex))
(defmethod compile-type-check 'zen/case [_ _] (type-fn 'zen/case))

(defmethod compile-type-check 'zen/apply
  [tp ztx]
  (fn [vtx data opts]
    (cond
      (not (list? data))
      (add-err vtx :type {:message (str "Expected fn call '(fn-name args-1 arg-2), got '"
                                        (pretty-type data))})

      (not (symbol? (first data)))
      (add-err vtx :apply {:message (str "Expected symbol, got '" (first data))
                           :type "apply.fn-name"})

      :else
      (let [sch-sym (first data)
            {:keys [zen/tags args] :as sch} (utils/get-symbol ztx sch-sym)]
        (cond
          (nil? sch)
          (add-err vtx :apply {:message (str "Could not resolve fn '" sch-sym)
                               :type "apply.fn-name"})

          (not (contains? tags 'zen/fn))
          (add-err vtx :apply {:message (format "fn definition '%s should be tagged with 'zen/fn, but '%s" sch-sym tags)
                               :type "apply.fn-tag"})

          :else
          (let [v (get-cached ztx args)]
            (merge-vtx vtx (v (node-vtx vtx [sch-sym :args]) (rest data) opts))))))))

(defmethod compile-key :type
  [_ ztx tp]
  {:rules [(compile-type-check tp ztx)]})

(defmethod compile-key :case
  [_ ztx cases]
  (let [vs (map (fn [{:keys [when then]}]
                  (cond-> {:when (get-cached ztx when)}
                    (not-empty then) (assoc :then (get-cached ztx then))))
                cases)]
    {:rules
     [(fn [vtx data opts]
        (loop [[{wh :when th :then :as v} & rest] vs
               item-idx 0]
          (if (nil? v)
            (add-err vtx
                     :case
                     {:message (format "Expected one of the cases to be true") :type "case"})
            (let [vtx* (node-vtx vtx [:case item-idx :when])
                  {errs :errors} (wh vtx* data opts)]
              (cond
                (and (empty? errs) th)
                (->> (th (node-vtx vtx [:case item-idx :then]) data opts)
                     (merge-vtx vtx))

                (empty? errs) vtx

                :else (recur rest (inc item-idx)))))))]}))

(defmethod compile-key :enum
  [_ ztx values]
  (let [values* (set (map :value values))]
    {:rules
     [(fn [vtx data opts]
        (if-not (contains? values* data)
          (add-err vtx :enum {:message (str "Expected '" data "' in " values*) :type "enum"})
          vtx))]}))

(defmethod compile-key :min
  [_ ztx min]
  {:when number?
   :rules
   [(fn [vtx data opts]
      (if (< data min)
        (add-err vtx :min {:message (str "Expected >= " min ", got " data)})
        vtx))]})

(defmethod compile-key :max
  [_ ztx max]
  {:when number?
   :rules
   [(fn [vtx data opts]
      (if (> data max)
        (add-err vtx :max {:message (str "Expected <= " max ", got " data)})
        vtx))]})

(defmethod compile-key :minLength
  [_ ztx min-len]
  {:when string?
   :rules
   [(fn [vtx data opts]
      (if (< (count data) min-len)
        (add-err vtx
                 :minLength
                 {:message (str "Expected length >= " min-len ", got " (count data))})
        vtx))]})

(defmethod compile-key :maxLength
  [_ ztx max-len]
  {:when string?
   :rules
   [(fn [vtx data opts]
      (if (> (count data) max-len)
        (add-err vtx
                 :maxLength
                 {:message (str "Expected length <= " max-len ", got " (count data))})
        vtx))]})

(defmethod compile-key :minItems
  [_ ztx items-count]
  {:when #(or (sequential? %) (set? %))
   :rules
   [(fn [vtx data opts]
      (if (< (count data) items-count)
        (add-err vtx
                 :minItems
                 {:message (str "Expected >= " items-count ", got " (count data))})
        vtx))]})

(defmethod compile-key :maxItems
  [_ ztx items-count]
  {:when #(or (sequential? %) (set? %))
   :rules
   [(fn [vtx data opts]
      (if (> (count data) items-count)
        (add-err vtx
                 :maxItems
                 {:message (str "Expected <= " items-count ", got " (count data))})
        vtx))]})

(defmethod compile-key :const
  [_ ztx {:keys [value]}]
  {:rules
   [(fn [vtx data opts]
      (if (not= value data)
        (add-err vtx :const
                 {:message (str "Expected '" value "', got '" data "'")
                  :type "schema"})
        vtx))]})

(defmethod compile-key :keys
  [_ ztx ks]
  (let [key-rules
        (mapv (fn [[k sch]]
                (let [v (get-cached ztx sch)]
                  (fn [vtx data opts]
                    (if-let [d (contains? data k)]
                      (->> (v (node-vtx vtx k k #_:log-visited)
                              (get data k)
                              opts)
                           (merge-vtx vtx))
                      vtx))))
              ks)

        unknown-rule
        (fn [vtx data opts]
          vtx)]

    {:when map?
     :rules (conj key-rules unknown-rule)}))

(defmethod compile-key :values
  [_ ztx sch]
  (let [v (get-cached ztx sch)]
    {:when map?
     :rules
     [(fn [vtx data opts]
        (reduce (fn [vtx* [key value]]
                  (merge-vtx vtx* (v (node-vtx vtx* :values key) value opts)))
                vtx
                data))]}))

(defmethod compile-key :every
  [_ ztx sch]
  (let [v (get-cached ztx sch)]
    {:when #(or (sequential? %) (set? %))
     :rules
     [(fn [vtx data opts]
        (let [err-fn
              (fn [idx item]
                (v (node-vtx vtx [:every idx] idx) item opts))

              data*
              (if-let [indices (not-empty (:indices opts))]
                (map err-fn indices data)
                (map-indexed err-fn data))]

          (reduce merge-vtx vtx data*)))]}))

(defmethod compile-key :subset-of
  [_ ztx superset]
  {:when set?
   :rules
   [(fn [vtx data opts]
      (if-not (clojure.set/subset? data superset)
        (add-err vtx :subset-of {:type "set"})
        vtx))]})

(defmethod compile-key :superset-of
  [_ ztx subset]
  {:when set?
   :rules
   [(fn [vtx data opts]
      (if-not (clojure.set/subset? subset data)
        (add-err vtx :superset-of {:type "set"})
        vtx))]})

(defmethod compile-key :regex
  [_ ztx regex]
  {:when string?
   :rules
   [(fn [vtx data opts]
      (if (not (re-find (re-pattern regex) data))
        (add-err vtx :regex
                 {:message (str "Expected match /" (str regex) "/, got \"" data "\"")})
        vtx))]})

(defmethod compile-key :confirms
  [_ ztx ks]
  (let [apply-fn
        (fn [[schema-name v] [vtx data opts]]
          (list
           (merge-vtx vtx (v (node-vtx vtx [:confirms schema-name]) data opts)) data opts))

        comp-fn
        (->> ks
             (map #(utils/get-symbol ztx %))
             (map (fn [sch] (list (:zen/name sch) (get-cached ztx sch))))
             (map #(partial apply-fn %))
             (apply comp))]
    {:rules
     [(fn [vtx data opts]
        (-> (list vtx data opts)
            comp-fn
            first))]}))

(defmethod compile-key :require
  [_ ztx ks]
  (let [one-of-fn
        (fn [data vtx s]
          (let [reqs (->> (select-keys data s) (remove nil?))]
            (if (empty? reqs)
              ;; TODO add message
              (add-err vtx :require {:type "map.require"})
              vtx)))

        all-keys-fn
        (fn [vtx mk]
          (add-err vtx
                   :require
                   {:type "require"
                    :message (str mk " is required")}
                   mk))]

    {:when map?
     :rules
     [(fn [vtx data opts]
        (->> (filter set? ks)
             (reduce (partial one-of-fn data) vtx)))

      (fn [vtx data opts]
        (->> (-> (remove set? ks)
                 set
                 (clojure.set/difference (into #{} (keys data))))
             (reduce all-keys-fn vtx)))]}))

(defmethod compile-key :schema-key
  [_ ztx {sk :key sk-ns :ns sk-tags :tags}]
  {:when map?
   :rules
   [(fn [vtx data opts]
      (if-let [sch-nm (get data sk)]
        (let [sch-symbol (if sk-ns (symbol sk-ns (name sch-nm)) (symbol sch-nm))
              {tags :zen/tags :as sch} (utils/get-symbol ztx sch-symbol)]
          (cond
            (nil? sch)
            (add-err vtx :schema-key
                     {:message (str "Could not find schema " sch-symbol)
                      :type "schema"})

            (not (contains? tags 'zen/schema))
            (add-err vtx :schema-key
                     {:message (str "'" sch-symbol " should be tagged with zen/schema, but " tags)
                      :type "schema"})

            (and sk-tags (not (clojure.set/subset? sk-tags tags)))
            (add-err vtx :schema-key
                     {:message (str "'" sch-symbol " should be tagged with " sk-tags ", but " tags)
                      :type "schema"})

            :else
            (let [v (get-cached ztx sch)
                  node-vtx* (node-vtx vtx [:schema-key sch-symbol])]
              (->> (v node-vtx* data opts) (merge-vtx vtx)))))
        vtx))]})

(defmethod compile-key :schema-index
  [_ ztx {si :index si-ns :ns}]
  {:when sequential?
   :rules
   [(fn [vtx data opts]
      (if-let [sch-nm (or (get data si) (nth data si))]
        (let [sch-symbol (if si-ns (symbol si-ns (name sch-nm)) sch-nm)
              sch (utils/get-symbol ztx sch-symbol)]
          (cond
            (nil? sch)
            (add-err vtx
                     :schema-index
                     {:message (format "Could not find schema %s" sch-symbol)
                      :type "schema"})

            :else
            (let [v (get-cached ztx sch)]
              (merge-vtx vtx (v (node-vtx vtx [:schema-index sch-symbol]) data opts)))))
        vtx))]})

(defmethod compile-key :nth
  [_ ztx cfg]
  (let [schemas (map (fn [[index v]] [index (get-cached ztx v)]) cfg)]
    {:when sequential?
     :rules
     [(fn [vtx data opts]
        (reduce (fn [vtx* [index v]]
                  (if-let [nth-el (get data index)]
                    (let [node-vtx* (v (node-vtx vtx* [:nth index] index) nth-el opts)]
                      (merge-vtx vtx* node-vtx*))
                    vtx))
                vtx
                schemas))]}))

(defmethod compile-key :keyname-schemas
  [_ ztx {:keys [tags]}]
  {:rules
   [(fn [vtx data opts]
      (let [rule-fn
            (fn [vtx* [schema-key data*]]
              (if-let [sch (and (qualified-ident? schema-key) (utils/get-symbol ztx (symbol schema-key)))]
                ;; TODO add test on nil case
                (if (or (nil? tags)
                        (clojure.set/subset? tags (:zen/tags sch)))
                  (->> (-> (node-vtx vtx* [:keyname-schemas schema-key] schema-key)
                           ((get-cached ztx sch) data* opts))
                       (merge-vtx vtx*))
                  vtx*)
                vtx*))]
        (reduce rule-fn vtx data)))]})

(defmethod compile-key :default [schema-key ztx sch-params]
  ;; it is assumed that if no compile key impl found then effect is emitted
  (let [{:keys [zen/tags] :as sch}
        (and (qualified-ident? schema-key)
             (utils/get-symbol ztx (symbol schema-key)))]
    {:rules
     [(fn [vtx data opts]
        (if (contains? tags 'zen/schema-fx)
          (add-fx vtx (:zen/name sch)
                  {:name (:zen/name sch)
                   :params sch-params
                   :data data})
          vtx))]}))

(defn is-exclusive? [group data]
  (->> group
       (filter #(->> (if (set? %) % #{%})
                     (select-keys data)
                     seq))
       (bounded-count 2)
       (> 2)))

(defmethod compile-key :exclusive-keys
  [_ ztx groups]
  (let [err-fn
        (fn [group [vtx data]]
          (if (is-exclusive? group data)
            (list vtx data)
            (let [err-msg
                  (format "Expected only one of keyset %s, but present %s"
                          (str/join " or " group)
                          (keys data))
                  vtx*
                  (add-err vtx :exclusive-keys
                           {:message err-msg
                            :type "map.exclusive-keys"})]
              (list vtx* data))))

        comp-fn
        (->> groups
             (map #(partial err-fn %))
             (apply comp))]

    {:rules
     [(fn [vtx data opts]
        (-> (list vtx data)
            comp-fn
            first))]}))

(defmethod compile-key :key
  [_ ztx sch]
  (let [v (get-cached ztx sch)]
    {:rules
     [(fn [vtx data opts]
        (reduce (fn [vtx* [k _]]
                  (merge-vtx vtx* (v (node-vtx vtx* :key k) k opts)))
                vtx
                data))]}))

(defmethod compile-key :tags
  [_ ztx sch-tags]
  {:when #(or (symbol? %) (list? %))
   :rules
   [(fn [vtx data opts]
      (let [sym (if (list? data) (first data) data)
            {:keys [zen/tags] :as sch} (utils/get-symbol ztx sym)]
        (if (not (clojure.set/superset? tags sch-tags))
          (add-err vtx :tags
                   {:message (format "Expected symbol '%s tagged with '%s, but only %s"
                                     (str sym) (str sch-tags) (or tags #{}))
                    ;; currently :tags implements two different usecases
                    :type (if (list? data) "apply.fn-tag" "symbol")})
          vtx)))]})

(defmethod compile-key :slicing
  [_ ztx {slices :slices rest-schema :rest}]
  (let [schemas
        (->> slices
             (map (fn [[slice-name {:keys [schema]}]]
                    [slice-name (get-cached ztx schema)]))
             (into {}))

        rest-fn
        (when (not-empty rest-schema)
          (get-cached ztx rest-schema))

        slice-fns
        (map (fn [[slice-name slice-schema]]
               ;; TODO support other slicing engines
               (if-let [v (get-cached ztx (get-in slice-schema [:filter :zen]))]
                 (fn [vtx el opts]
                   (let [vtx* (v (node-vtx vtx :slicing) el opts)]
                     (when (empty? (:errors vtx*))
                       slice-name)))
                 (constantly nil)))
             slices)

        slices-templ
        (->> slices
             (map (fn [[slice-name _]]
                    [slice-name []]))
             (into {}))

        err-fn
        (fn [vtx opts [slice-name slice]]
          (let [data* (mapv second slice)
                indices (map first slice)]
            (cond
              (and (nil? slice-name) (empty? rest-schema))
              (node-vtx vtx :slicing)

              (and (nil? slice-name) (not-empty rest-schema))
              (rest-fn (node-vtx vtx [:slicing :slicing/rest] (str "[" :slicing/rest "]"))
                       data*
                       (assoc opts :indices indices))

              :else
              (let [v (get schemas slice-name)]
                (v (node-vtx vtx [:slicing slice-name] (str "[" slice-name "]"))
                   data*
                   (assoc opts :indices indices))))))]

    {:when sequential?
     :rules
     [(fn [vtx data opts]
        (->> data
             (map-indexed vector)
             (group-by (fn [[_ datum]]
                         (some #(apply % [vtx datum opts]) slice-fns)))
             (merge slices-templ)
             (map (partial err-fn vtx opts))
             (reduce merge-vtx vtx)))]}))
