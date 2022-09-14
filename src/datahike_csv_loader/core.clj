(ns datahike-csv-loader.core
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [datahike-csv-loader.utils :as utils]
            [tablecloth.api :as tc]))

(defn- tc-to-datahike-types [datatype]
  (case datatype
    :float64 :db.type/double
    (:int16 :int32 :int64) :db.type/long
    (keyword "db.type" (name datatype))))

(defn- datahike-to-tc-types [datatype]
  (case datatype
    :db.type/double :float64
    :db.type/long :int64
    (keyword (name datatype))))

(defn- assoc-mismatched-ref-type [m k coltypes ref-type]
  (if (not= (k coltypes) ref-type)
    (assoc m k ref-type)
    m))

(defn- get-column-info [ds]
  (tc/info ds :columns))

(defn- filter-ref-cols [ref-cols col-names]
  (->> (filter (fn [[k v]] (v col-names))
               ref-cols)
       (into {})))

(defn- convert-ref-col-types [ds cols-info self-ref-cols other-ref-cols schema]
  (let [coltypes (zipmap (:name cols-info) (:datatype cols-info))
        self-ref-types (reduce (fn [m [k v]]
                                 (->> (v coltypes)
                                      (assoc-mismatched-ref-type m k coltypes)))
                               {}
                               self-ref-cols)
        other-ref-types (reduce (fn [m [k v]]
                                  (if (some? (v schema))
                                    (->> (:db/valueType (v schema))
                                         datahike-to-tc-types
                                         (assoc-mismatched-ref-type m k coltypes))
                                    (throw (IllegalArgumentException. "Foreign IDs must refer to attribute already in schema"))))
                                {}
                                other-ref-cols)
        conversion-map (merge self-ref-types other-ref-types)]
    (if (not-empty conversion-map)
      (tc/convert-types ds conversion-map)
      ds)))

(defn- add-tempid-col [ds]
  (let [range-end (- (+ (tc/row-count ds) 1))
        tempid-range (range -1 range-end -1)]
    (tc/add-column ds :db/id tempid-range)))

(defn- refs-to-tempids [ds self-ref-cols]
  (reduce-kv (fn [m k v]
               (let [non-nil-refs (->> (zipmap (v ds) (:db/id ds))
                                       (filter (fn [[k v]] (some? k)))
                                       (into {}))]
                 (assoc m k non-nil-refs)))
             {}
             self-ref-cols))

(defn- refs-to-eids [ds foreign-ref-cols db]
  (reduce-kv (fn [m k v]
               (let [non-nil-refs (filter some? (k ds))]
                 (->> (map (fn [ref-val] [v ref-val]) non-nil-refs)
                      (d/pull-many db '[:db/id])
                      (map :db/id)
                      (zipmap non-nil-refs)
                      (assoc m k))))
             {}
             foreign-ref-cols))

(defn- refs-to-ids [ds self-ref-cols foreign-ref-cols db]
  (merge (refs-to-tempids ds self-ref-cols)
         (refs-to-eids ds foreign-ref-cols db)))

(defn- update-ref-cols [ds ref-id-maps]
  (let [refcols (keys ref-id-maps)]
    (->> (map (fn [k]
                (partial map #((k ref-id-maps) %)))
              refcols)
         (tc/update-columns ds refcols))))

(defn- handle-ref-cols [ds cols-info self-ref-cols foreign-ref-cols db]
  (let [ds (->> (d/schema db)
                (convert-ref-col-types ds cols-info self-ref-cols foreign-ref-cols)
                add-tempid-col)]
    (update-ref-cols ds (refs-to-ids ds self-ref-cols foreign-ref-cols db))))

(defn- dataset-with-ref-cols [ds ref-cols db]
  (let [cols-info (get-column-info ds)
        self-ref-cols (filter-ref-cols ref-cols (set (:name cols-info)))
        schema (d/schema db)
        foreign-ref-cols (->> (remove (fn [[k v]] (k self-ref-cols))
                                      ref-cols)
                              (into {}))]
    (if (or (pos? (count self-ref-cols))
            (pos? (count foreign-ref-cols)))
      (handle-ref-cols ds cols-info self-ref-cols foreign-ref-cols db)
      ds)))

(defn- create-dataset
  ([csv] (create-dataset csv nil nil))
  ([csv ref-cols db] (cond-> (tc/dataset csv {:key-fn keyword})
                       (and (some? ref-cols)
                            (pos? (count ref-cols))) (dataset-with-ref-cols ref-cols db))))

(defn- column-info-maps [ds cols]
  (-> (tc/select-columns ds cols)
      get-column-info
      (tc/rows :as-maps)))

(defn- required-schema-attrs
  ([col-name cardinality-many?]
   (required-schema-attrs col-name cardinality-many? nil))
  ([col-name cardinality-many? col-dtype]
   {:db/ident       col-name
    :db/cardinality (if cardinality-many?
                      :db.cardinality/many
                      :db.cardinality/one)
    :db/valueType   (case col-dtype
                      (:db.type/ref :db.type/tuple) col-dtype
                      (tc-to-datahike-types col-dtype))}))

(defn- optional-schema-attrs [schema col-name required-attrs]
  (let [{:keys [unique-id unique-val index]} schema
        unique-id? (col-name unique-id)
        index? (and (col-name index) (not unique-id?))]
    (cond-> required-attrs
      (col-name unique-val) (assoc :db/unique :db.unique/value)
      ;; unique identity overrides unique value if both are specified
      unique-id? (assoc :db/unique :db.unique/identity)
      ;; :db/index true is not recommended for unique identity attribute
      index? (assoc :db/index true))))

(defn- column-schema-attrs
  ([schema col-name]
   (column-schema-attrs schema col-name nil))
  ([schema col-name col-dtype]
   (->> (required-schema-attrs col-name
                               (col-name (:cardinality-many schema))
                               col-dtype)
        (optional-schema-attrs schema col-name))))

(defn- extract-schema [col-schema ref-map tuple-map composite-tuple-map ds db-schema]
  (if-let [cardinality-many-attrs (:cardinality-many col-schema)]
    (when (> (count cardinality-many-attrs) 1)
      (throw (IllegalArgumentException. "Each file is allowed at most one cardinality-many attribute"))))
  (let [composite-tuple-schemas (map #(-> (column-schema-attrs col-schema % :db.type/tuple)
                                          (assoc :db/tupleAttrs (% composite-tuple-map)))
                                     (keys composite-tuple-map))
        tuple-schemas (map (fn [k]
                             (let [tuple-dtypes (->> (column-info-maps ds (k tuple-map))
                                                     (mapv #(tc-to-datahike-types (:datatype %))))
                                   tuple-schema (column-schema-attrs col-schema k :db.type/tuple)]
                               (if (apply = tuple-dtypes)
                                 (assoc tuple-schema :db/tupleType (first tuple-dtypes))
                                 (assoc tuple-schema :db/tupleTypes tuple-dtypes))))
                           (keys tuple-map))
        tuple-cols-to-drop (apply concat (vals tuple-map))
        include-cols (-> (filter #(% db-schema) (tc/column-names ds))
                         (into tuple-cols-to-drop)
                         (conj :db/id)
                         set
                         complement)]
    (->> (column-info-maps ds include-cols)
         (mapv #(let [col-name (:name %)
                      dtype (if (col-name ref-map) :db.type/ref (:datatype %))]
                  (column-schema-attrs col-schema col-name dtype)))
         (concat composite-tuple-schemas tuple-schemas))))

(defn- merge-entity-rows [rows merge-attr]
  (reduce (fn [vals row]
            (-> (merge vals (dissoc row merge-attr))
                (update merge-attr #(conj % (merge-attr row)))))
          (update (first rows) merge-attr vector)
          (rest rows)))

(defn- dataset-for-transact [ds {cardinality-many :db.cardinality/many :as rschema} tuple-map]
  (let [ds-to-tx (mapv #(let [init (utils/rm-empty-elements % (transient {}) true)]
                          (persistent! (utils/merge-tuple-cols tuple-map init true)))
                       (tc/rows ds :as-maps))]
    (if cardinality-many
      (let [id-attr (first (:db.unique/identity rschema))
            merge-attr (first cardinality-many)]
        (->> (vals (group-by id-attr ds-to-tx))
             (map #(merge-entity-rows % merge-attr))))
      ds-to-tx)))

(defn load-csv
  "Reads, parses, and loads data from CSV file named `csv-file` into the Datahike database having
  (optionally specified) config `cfg`, with likewise optional schema-related options for the
  corresponding attributes. Each column represents an attribute, with keywordized column name as
  attribute ident, or otherwise, an element in a heterogeneous or homogeneous tuple.

  If `cfg` is omitted, and the last argument:
  1. is also absent, or has empty `:schema`, `:ref-map`, and `:composite-tuple-map`, `cfg` is inferred to be `{:schema-flexibility :read}`.
  2. has a non-empty value for one or more of `:schema`, `:ref-map`, and `:composite-tuple-map`, `cfg` is inferred to be `{}`, i.e. the default value.

  `:schema` in the last argument can be specified in two ways:
  1. Full specification via the usual Datahike transaction data format, i.e. a vector of maps,
  each corresponding to an attribute.
  2. Partial specification via an abridged format like the map returned by `datahike.api/reverse-schema`,
  albeit with slightly different keys, each having a set of attribute idents as the corresponding value.
  Available options:

  | Key                 | Description   |
  |---------------------|---------------|
  | `:unique-id`        | `:db/unique` value `:db.unique/identity`
  | `:unique-val`       | `:db/unique` value `:db.unique/value`
  | `:index`            | `:db/index` value `true`
  | `:cardinality-many` | `:db/cardinality` value `:db.cardinality/many`

  Ref- and tuple-valued attributes, i.e. those with `:db/valueType` `:db.type/ref` or `:db.type/tuple`, are
  however specified separately, via `:ref-map`, `:tuple-map`, or `:composite-tuple-map`, each a map as follows:

  | Key                     | Description   |
  |-------------------------|---------------|
  | `:ref-map`              | `:db.type/ref` attribute idents to referenced attribute idents
  | `:composite-tuple-map`  | Composite `:db.type/tuple` attribute idents to constituent attribute idents
  | `:tuple-map`            | Other (homogeneous, heterogeneous) `:db.type/tuple` attribute idents to constituent attribute idents

  Unspecified schema attribute values are defaults or inferred from the data given.

  Example invocations:
  ``` clojure
  (load-csv csv-file)
  (load-csv csv-file dh-cfg)
  (load-csv csv-file dh-cfg {:schema [{:db/ident :name
                                       ...}
                                      ...]
                             :ref-map {...}
                             :tuple-map {...}
                             :composite-tuple-map {...}})
  (load-csv csv-file dh-cfg {:schema {:unique-id #{...}
                                      ...}
                             :ref-map {...}
                             :tuple-map {...}
                             :composite-tuple-map {...}})
  ```

  Please see README for more detail."
  ([csv-file]
   (load-csv csv-file nil {}))
  ([csv-file cfg]
   (load-csv csv-file cfg {}))
  ([csv-file cfg {:keys [schema ref-map tuple-map composite-tuple-map]}]
   (let [cfg (or cfg (if (and (empty? schema) (empty? ref-map) (empty? composite-tuple-map))
                       {:schema-flexibility :read}
                       {}))
         _ (if-not (d/database-exists? cfg)
             (d/create-database cfg))
         conn (d/connect cfg)
         db-schema (d/schema @conn)
         ds (create-dataset csv-file ref-map @conn)
         schema (if (map? schema)
                  (extract-schema schema ref-map tuple-map composite-tuple-map ds db-schema)
                  (remove #(% db-schema) (map :db/ident schema)))]
     (if (not-empty schema)
       (d/transact conn schema))
     (->> (dataset-for-transact ds (d/reverse-schema @conn) tuple-map)
          (d/transact conn)))))
