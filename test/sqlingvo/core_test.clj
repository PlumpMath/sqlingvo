(ns sqlingvo.core-test
  (:refer-clojure :exclude [distinct group-by update])
  (:require [clojure.java.io :refer [file]]
            [clojure.test :refer :all]
            [sqlingvo.core :refer :all]
            [sqlingvo.expr :refer :all]
            [sqlingvo.util :refer :all]
            [sqlingvo.test :refer [db sql= with-stmt]]))

(deftest test-from
  (let [[from stmt] ((from :continents) {})]
    (is (= [{:op :table :children [:name] :name :continents}] from))
    (is (= {:from [{:op :table, :children [:name] :name :continents}]} stmt))))

;; COMPOSE

(deftest test-compose
  (with-stmt
    ["SELECT \"id\", \"name\" FROM \"continents\" WHERE (\"id\" = 1) ORDER BY \"name\""]
    (compose (select db [:id :name]
               (from :continents))
             (where '(= :id 1))
             (order-by :name))))

(deftest test-compose-where-clause-using-and
  (with-stmt
    ["SELECT \"color\", \"num-sides\" FROM \"shapes\" WHERE ((\"num-sides\" = 3) and (\"color\" = ?))" "green"]
    (let [triangles (compose (select db [:color :num-sides] (from :shapes))
                             (where '(= :num-sides 3)))]
      (compose triangles (where '(= :color "green") :and)))))

(deftest test-compose-selects
  (with-stmt
    ["SELECT 3, 2, 1"]
    (compose (select db [1 2 3])
             (select db [3 2 1]))))

;; AS

(deftest test-as
  (are [args expected]
      (is (= expected (apply as args)))
    [:id :other]
    (assoc (parse-expr :id) :as :other)
    [:continents [:id :name]]
    [(assoc (parse-expr :continents.id) :as :continents-id)
     (assoc (parse-expr :continents.name) :as :continents-name)]
    [:public.continents [:id :name]]
    [(assoc (parse-expr :public.continents.id) :as :public-continents-id)
     (assoc (parse-expr :public.continents.name) :as :public-continents-name)]
    ['(count *) :count]
    (assoc (parse-expr '(count *)) :as :count)))

;; CAST

(deftest test-cast-int-as-text
  (with-stmt
    ["SELECT CAST(1 AS text)"]
    (select db [`(cast 1 :text)])))

(deftest test-cast-text-as-int
  (with-stmt
    ["SELECT CAST(? AS int)" "1"]
    (select db [`(cast "1" :int)])))

(deftest test-cast-with-alias
  (with-stmt
    ["SELECT CAST(? AS int) AS \"numeric-id\"" "1"]
    (select db [(as `(cast "1" :int) :numeric-id)])))

;; COPY

(deftest test-copy-stdin
  (with-stmt
    ["COPY \"country\" FROM STDIN"]
    (copy db :country []
      (from :stdin))
    (is (= :copy (:op stmt)))
    (is (= [:stdin] (:from stmt)))))

(deftest test-copy-country
  (with-stmt
    ["COPY \"country\" FROM ?" "/usr1/proj/bray/sql/country_data"]
    (copy db :country []
      (from "/usr1/proj/bray/sql/country_data"))
    (is (= :copy (:op stmt)))
    (is (= ["/usr1/proj/bray/sql/country_data"] (:from stmt)))))

(deftest test-copy-country-with-encoding
  (with-stmt
    ["COPY \"country\" FROM ? ENCODING ?" "/usr1/proj/bray/sql/country_data" "UTF-8"]
    (copy db :country []
      (from "/usr1/proj/bray/sql/country_data")
      (encoding "UTF-8"))
    (is (= :copy (:op stmt)))
    (is (= ["/usr1/proj/bray/sql/country_data"] (:from stmt)))))

(deftest test-copy-country-with-delimiter
  (with-stmt
    ["COPY \"country\" FROM ? DELIMITER ?" "/usr1/proj/bray/sql/country_data" " "]
    (copy db :country []
      (from "/usr1/proj/bray/sql/country_data")
      (delimiter " "))))

(deftest test-copy-country-columns
  (with-stmt
    ["COPY \"country\" (\"id\", \"name\") FROM ?" "/usr1/proj/bray/sql/country_data"]
    (copy db :country [:id :name]
      (from "/usr1/proj/bray/sql/country_data"))
    (is (= :copy (:op stmt)))
    (is (= ["/usr1/proj/bray/sql/country_data"] (:from stmt)))
    (is (= (map parse-column [:id :name]) (:columns stmt)))))

(deftest test-copy-from-expands-to-absolute-path
  (is (= ["COPY \"country\" FROM ?" (.getAbsolutePath (file "country_data"))]
         (sql (copy db :country [] (from (file "country_data")))))))

(deftest test-refresh-materialized-view
  (sql= (refresh-materialized-view db :order-summary)
        ["REFRESH MATERIALIZED VIEW \"order-summary\""])
  (sql= (refresh-materialized-view db :order-summary
                                   (concurrently true))
        ["REFRESH MATERIALIZED VIEW CONCURRENTLY \"order-summary\""])
  (sql= (refresh-materialized-view db :order-summary
                                   (with-data true))
        ["REFRESH MATERIALIZED VIEW \"order-summary\" WITH DATA"])
  (sql= (refresh-materialized-view db :order-summary
                                   (with-data false))
        ["REFRESH MATERIALIZED VIEW \"order-summary\" WITH NO DATA"])
  (sql= (refresh-materialized-view db :order-summary
                                   (concurrently true)
                                   (with-data false))
        ["REFRESH MATERIALIZED VIEW CONCURRENTLY \"order-summary\" WITH NO DATA"]))

(deftest test-drop-materialized-view
  (sql= (drop-materialized-view db :order-summary)
        ["DROP MATERIALIZED VIEW \"order-summary\""])
  (sql= (drop-materialized-view db :order-summary
          (if-exists true))
        ["DROP MATERIALIZED VIEW IF EXISTS \"order-summary\""])
  (sql= (drop-materialized-view db :order-summary
          (cascade true))
        ["DROP MATERIALIZED VIEW \"order-summary\" CASCADE"])
  (sql= (drop-materialized-view db :order-summary
          (restrict true))
        ["DROP MATERIALIZED VIEW \"order-summary\" RESTRICT"])
  (sql= (drop-materialized-view db :order-summary
          (if-exists true)
          (cascade true))
        ["DROP MATERIALIZED VIEW IF EXISTS \"order-summary\" CASCADE"]))

(deftest test-sql-placeholder-constant
  (let [db (assoc db :sql-placeholder sql-placeholder-constant)]
    (sql= (select db  [:*]
            (from :distributors)
            (where '(and (= :dname "Anvil Distribution")
                         (= :zipcode "21201"))))
          ["SELECT * FROM \"distributors\" WHERE ((\"dname\" = ?) and (\"zipcode\" = ?))"
           "Anvil Distribution" "21201"])))

(deftest test-sql-placeholder-count
  (let [db (assoc db :sql-placeholder sql-placeholder-count)]
    (sql= (select db  [:*]
            (from :distributors)
            (where '(and (= :dname "Anvil Distribution")
                         (= :zipcode "21201"))))
          ["SELECT * FROM \"distributors\" WHERE ((\"dname\" = $1) and (\"zipcode\" = $2))"
           "Anvil Distribution" "21201"])))

(deftest test-sql-placeholder-count-subselect
  (let [db (assoc db :sql-placeholder sql-placeholder-count)]
    (sql= (select db ["a" "b" :*]
            (from (as (select db ["c" "d"]) :x)))
          ["SELECT $1, $2, * FROM (SELECT $3, $4) AS \"x\"" "a" "b" "c" "d"])))
