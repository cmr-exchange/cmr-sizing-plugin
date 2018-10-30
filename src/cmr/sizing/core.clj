(ns cmr.sizing.core
  (:require
    [clojure.string :as string]
    [cmr.exchange.common.results.core :as results]
    [cmr.exchange.common.results.errors :as errors]
    [cmr.exchange.common.results.warnings :as warnings]
    [cmr.exchange.common.util :as util]
    [cmr.exchange.query.core :as query]
    [cmr.metadata.proxy.concepts.core :as concept]
    [cmr.metadata.proxy.concepts.granule :as granule]
    [cmr.metadata.proxy.concepts.variable :as variable]
    [cmr.metadata.proxy.results.errors :as metadata-errors]
    [cmr.ous.common :as ous-common]
    [cmr.ous.components.config :as config]
    [cmr.ous.impl.v2-1 :as ous]
    [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;   Support & Utility Functions   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;; Note that all internal operations for estimating size are performed
;;; assuming bytes as the units.

(defn get-measurement
  [variable]
  (let [dims (get-in variable [:umm :Dimensions])
        total-dimensionality (reduce * (map :Size dims))
        data-type (get-in variable [:umm :DataType])]
    (* total-dimensionality
       (util/data-type->bytes data-type))))

(defn estimate-dods-size
  [granule-count variables]
  (let [compression 1
        metadata 0 ; included here for symmetry
        measurements (reduce + (map get-measurement variables))]
    (+ (* granule-count compression measurements) metadata)))

(defn estimate-netcdf3-size
  [granule-count variables metadata]
  (let [compression 1
        measurements (reduce + (map get-measurement variables))]
    (+ (* granule-count compression measurements) metadata)))

(defn- -estimate-size
  [fmt granule-count vars granule-metadata-size]
  (case (keyword (string/lower-case fmt))
    :dods (estimate-dods-size granule-count vars)
    :nc (estimate-netcdf3-size granule-count vars granule-metadata-size)
    (do
      (log/errorf "Cannot estimate size for %s (not implemented)." fmt)
      {:errors ["not-implemented"]})))

;; XXX This function is nearly identical to one of the same name in
;;     cmr.ous.common -- we should put this somewhere both can use,
;;     after generalizing to take a func and the func's args ...
(defn process-results
  ([results start errs]
   (process-results results start errs {:warnings nil}))
  ([{:keys [params data-files tag-data vars format granule-metadata]}
    start errs warns]
   (log/trace "Got data-files:" (vec data-files))
   (log/trace "Process-results tag-data:" tag-data)
   (if errs
     (do
       (log/error errs)
       errs)
     (let [sample-granule-metadata-size (count (.getBytes granule-metadata))
           estimate-or-errs (-estimate-size
                             format
                             (count data-files)
                             vars
                             sample-granule-metadata-size)]
       ;; Error handling for post-stages processing
       (if (errors/erred? estimate-or-errs)
         (do
           (log/error estimate-or-errs)
           estimate-or-errs)
         (do
           (log/debug "Generated estimate:" estimate-or-errs)
           (results/create [{:bytes estimate-or-errs
                             :mb (/ estimate-or-errs (Math/pow 2 20))
                             :gb (/ estimate-or-errs (Math/pow 2 30))}]
                           :elapsed (util/timed start)
                           :warnings warns)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;   API   ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn estimate-size
  [component user-token raw-params]
  (let [start (util/now)
        search-endpoint (config/get-search-url component)
        ;; Stage 1
        [params bounding-box grans-promise coll-promise s1-errs]
        (ous-common/stage1
         component
         {:endpoint search-endpoint
          :token user-token
          :params raw-params})
        ;; Stage 2
        [params data-files service-ids vars s2-errs]
        (ous/stage2
         component
         coll-promise
         grans-promise
         {:endpoint search-endpoint
          :token user-token
          :params params})
        ;; Stage 3
        [services vars params bounding-info s3-errs s3-warns]
        (ous/stage3
         component
         service-ids
         vars
         bounding-box
         {:endpoint search-endpoint
          :token user-token
          :params params})
        warns s3-warns
        ;; XXX Note that this next `let` assignment will only work when
        ;;     supporting explicit granule concept ids passed in the request;
        ;;     as soon as we're supporting implicit granule concept ids in SES
        ;;     (i.e., granule concept ids obtained from the collection), this
        ;;     will break.
        sample-granule-id (first (:granules params))
        granule-metadata (concept/get-metadata
                          search-endpoint user-token
                          (assoc params :concept-id sample-granule-id))
        ;; Error handling for all stages
        errs (errors/collect
              params bounding-box grans-promise coll-promise s1-errs
              data-files service-ids vars s2-errs s3-errs
              granule-metadata
              {:errors (errors/check
                        [not data-files metadata-errors/empty-gnl-data-files])})
        fmt (:format params)]
    (log/trace "raw-params:" raw-params)
    (log/debug "Got format:" fmt)
    (log/debug "Got data-files:" (vec data-files))
    (log/debug "Got services:" services)
    (log/debug "Got vars:" vars)
    (process-results
      {:params params
       :data-files data-files
       :vars vars
       :format fmt
       :granule-metadata granule-metadata}
      start
      errs
      warns)))
