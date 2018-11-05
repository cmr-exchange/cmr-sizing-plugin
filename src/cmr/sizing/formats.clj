(ns cmr.sizing.formats
  (:require
    [clojure.string :as string]
    [cmr.exchange.common.util :as util]
    [taoensso.timbre :as log]))

;;; Note that all internal operations for estimating size are performed
;;; assuming bytes as the units.

(defn- read-number
  "If value is string, read-string.  Otherwise ignore."
  [value]
  (if (string? value)
    (read-string value)
    value))

(defn- get-measurement
  [variable]
  (let [dims (get-in variable [:umm :Dimensions])
        total-dimensionality (reduce * (map :Size dims))
        data-type (get-in variable [:umm :DataType])]
    (* total-dimensionality
       (util/data-type->bytes data-type))))

(defn- get-avg-gran-size
  "Gets SizeEstimation value for AverageSizeOfGranulesSampled and parses it to a number."
  [variable]
  (-> variable
      (get-in [:umm :SizeEstimation :AverageSizeOfGranulesSampled])
      read-number))

(defn- get-avg-compression-rate-ascii
  "Gets SizeEstimation value for AvgCompressionRateASCII and parses it to a number."
  [variable]
  (-> variable
      (get-in [:umm :SizeEstimation :AvgCompressionRateASCII])
      read-number))

(defn- get-avg-compression-rate-netcdf4
  "Gets SizeEstimation value for AvgCompressionRateNetCDF4 and parses it to a number."
  [variable]
  (-> variable
      (get-in [:umm :SizeEstimation :AvgCompressionRateNetCDF4])
      read-number))

(defn estimate-dods-size
  "Calculates the estimated size for DODS format."
  [granule-count variables]
  (let [compression 1
        metadata 0 ; included here for symmetry
        measurements (reduce + (map get-measurement variables))]
    (+ (* granule-count compression measurements) metadata)))

(defn- estimate-netcdf3-size
  "Calculates the estimated size for NETCDF3 format."
  [granule-count variables metadata]
  (let [compression 1
        measurements (reduce + (map get-measurement variables))]
    (+ (* granule-count compression measurements)
       (* granule-count metadata))))

(defn- estimate-netcdf4-or-ascii-size
  "Calculates the estimated size for ASCII or NETCDF4 format.
   total-granule-input-bytes is a value given by the client in the size estimate request."
  [granule-count variables params fmt]
  (reduce (fn [total-estimate variable]
            (let [avg-gran-size (get-avg-gran-size variable)
                  total-granule-input-bytes (read-string (:total-granule-input-bytes params))
                  avg-compression-rate (if (= fmt :nc4)
                                         (get-avg-compression-rate-netcdf4 variable)
                                         (get-avg-compression-rate-ascii variable))]
              (+ total-estimate
                 (* total-granule-input-bytes
                    avg-compression-rate
                    (/ (/ total-granule-input-bytes granule-count) avg-gran-size)))))
          0
          variables))

(defn estimate-size
  [fmt granule-count vars granule-metadata-size params]
  (case (keyword (string/lower-case fmt))
    :dods (estimate-dods-size granule-count vars)
    :nc (estimate-netcdf3-size granule-count vars granule-metadata-size)
    :nc4 (estimate-netcdf4-or-ascii-size granule-count vars params fmt)
    :ascii (estimate-netcdf4-or-ascii-size granule-count vars params fmt)
    (do
      (log/errorf "Cannot estimate size for %s (not implemented)." fmt)
      {:errors ["not-implemented"]})))