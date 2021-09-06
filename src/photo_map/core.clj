(ns photo-map.core
  (:require
   [hiccup.core :as hiccup]

   [clj-common.as :as as]
   [clj-common.io :as io]
   [clj-common.hash :as hash]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.http-server :as http-server]
   [clj-common.path :as path]
   [clj-geo.math.tile :as tile-math]))

(def storage (atom {}))

#_(swap! storage (constantly {}))
#_(count (deref storage)) 

(defn list-all-images []
  (vals (deref storage)))

;; tag is used to distinguish iphone vs gopro, no name collision ( for image-id )
(defn append-image [longitude latitude image-id tag image-path]
  (swap!
   storage
   assoc
   image-id
   {
    :id image-id
    :tag tag
    :longitude longitude
    :latitude latitude
    :path image-path}))

(defn find-image [image-id]
  (get (deref storage) image-id))

;; add images ...

;; for iphone
#_(doseq [image-path (filter
                    #(.endsWith (last %) ".JPG")
                    (fs/list
                     (path/string->path
                      "/Users/vanja/dataset-local/raw-pending/icloud-stream/2021/05/11")))]
  (println "processing: " (path/path->string image-path))
  (let [metadata (com.drew.imaging.ImageMetadataReader/readMetadata
                  (new java.io.File (path/path->string image-path)))]
    (if-let [gpx-directory (first (.getDirectoriesOfType
                                   metadata
                                   com.drew.metadata.exif.GpsDirectory))]
      (append-image
       (.getLongitude (.getGeoLocation gpx-directory))
       (.getLatitude (.getGeoLocation gpx-directory))
       (.replace (last image-path) ".JPG" "")
       :iphone
       image-path))))

;; for gopro
#_(doseq [image-path (filter
                    #(.endsWith (last %) ".JPG")
                    (fs/list
                     (path/string->path
                      "/Volumes/dataset/raw/gopro/2021.07 - Nesa krstenje"
                      #_"/Users/vanja/dataset-local/raw-pending/gopro/2021.05 - Suvobor - Boljkovci bike/")))]
  (println "processing: " (path/path->string image-path))
  (let [metadata (com.drew.imaging.ImageMetadataReader/readMetadata
                  (new java.io.File (path/path->string image-path)))]
    (when-let [gpx-directory (first (.getDirectoriesOfType
                                   metadata
                                   com.drew.metadata.exif.GpsDirectory))]
      (when-let [geolocation (.getGeoLocation gpx-directory)]
        (append-image
         (.getLongitude geolocation)
        (.getLatitude geolocation)
        (.replace (last image-path) ".JPG" "")
        :gopro
        image-path)))))

;; go to http://localhost:7076/map

#_(swap! storage (constantly {}))

#_(count (deref storage))

;; search icloud images backed up with icloudpd
;; find directories containing
(def dataset-path ["Users" "vanja" "dataset-cloud" "photo-map" "icloud-storage"])
(def icloud-storage-path ["Volumes" "dataset" "raw" "icloud-stream"])
;; storage format YEAR/MONTH/DAY
(def path-seq
  (mapcat
   (fn [month-path]
     (fs/list month-path))
   (mapcat
    (fn [year-path]
      (fs/list year-path))
    (fs/list icloud-storage-path))))

(def ^:dynamic *output* false)

(defn convert-from-heic-to-jpg [path]
  (let [pb (new java.lang.ProcessBuilder ["/usr/local/bin/magick" "mogrify" "-monitor" "-format" "jpg" "*.HEIC"])]
    (.directory pb (new java.io.File (path/path->string path)))
    (.redirectErrorStream pb true)
    (let [process (.start pb)]
      (println "running" (path/path->string path))
      (doseq [line (io/input-stream->line-seq (.getInputStream process))]
        (when *output*
          (println "\t" line)))
      (.waitFor process)
      (println "done, status code: " (.exitValue process)))))

(def convert-path-seq
  (filter
   (fn [path]
     (let [image-set (into #{} (map last (fs/list path)))]
       (not
        (empty?
         (filter
          (fn [name]
            (let [jpg-name (.replace name ".HEIC" ".jpg")]
              (not (contains? image-set jpg-name))))
          (filter #(.endsWith % ".HEIC") image-set))))))
   path-seq))

#_(count path-seq) ;; 1900
#_(count convert-path-seq) ;; 369 ;; 397

#_(doseq [convert-path convert-path-seq]
  (convert-from-heic-to-jpg convert-path))

(defn extract-image-info
  [path]
  (let [metadata (com.drew.imaging.ImageMetadataReader/readMetadata
                  (new java.io.File (path/path->string path)))]
    (when-let [gpx-directory (first
                              (.getDirectoriesOfType
                               metadata
                               com.drew.metadata.exif.GpsDirectory))]
      (when-let [exif-subifd-directory (first
                                        (.getDirectoriesOfType
                                         metadata
                                         com.drew.metadata.exif.ExifSubIFDDirectory))]
       (when-let [geolocation (.getGeoLocation gpx-directory)]
         (when-let [date (.getDateOriginal exif-subifd-directory)]
           {
            :longitude (.getLongitude geolocation)
            :latitude (.getLatitude geolocation)
            :path path
            :md5 (hash/md5-bytes (io/input-stream->bytes (fs/input-stream path)))
            :timestamp (.getTime date)}))))))

#_(binding [*output* true]
  (with-open [os (fs/output-stream-by-appending (path/child dataset-path "log.json"))]
    (doseq [directory (take 20 path-seq)]
      (doseq [path (filter
                    #(or
                      (.endsWith (last %) ".JPG")
                      (.endsWith (last %) ".jpg"))
                    (fs/list directory))]
        (when *output*
          (println "processing: " (path/path->string path)))
        (if-let [info (extract-image-info path)]
          (json/write-to-line-stream info os)
          (println "failed: " (path/path->string path)))))))

#_(:timestamp (extract-image-info ["tmp" "IMG_0291.jpg"]))

(defn dotstore-fixed-create
  [root-path zoom]
  (fs/mkdirs root-path)
  (with-open [os (fs/output-stream (path/child root-path "info.json"))]
    (json/write-to-stream
     {
      :zoom zoom}
     os)))

(defn dotstore-fixed-append
  "Appends sequence of dots ( longitude, latitude required ) to fixed zoo
  dotstore, assumes dots are ordered for best performance, Once different tile
  is extracted previous file is closed and new opened. In case data for given
  longitude, latitude pair exists data is overwritten. Longitude and latitude
  are normalized"
  [root-path dot-seq]
  ;; todo add id to be able to display multiple photos on single location
  )

(def ^:dynamic *port* 7076)

(http-server/create-server
 *port*
 (compojure.core/routes
  (compojure.core/GET
   "/query"
   _
   (let [images (map
                 (fn [image]
                   {
                    :type "Feature"
                    :properties {
                                 "url" (str "/image/original/" (:id image))}
                    :geometry {
                               :type "Point"
                               :coordinates [(:longitude image) (:latitude image)]}})
                 (list-all-images))
         data {
               :type "FeatureCollection"
               :properties {}
               :features images}]
     {
      :status 200
      :body (json/write-to-string data)}))
  (compojure.core/GET
   "/tile/:tag/:zoom/:x/:y"
   [tag zoom x y]
   (let [tag (keyword tag)
         zoom (as/as-long zoom)
         x (as/as-long x)
         y (as/as-long y)
         tile [zoom x y]
         images (map
                 (fn [image]
                   {
                    :type "Feature"
                    :properties {
                                 "url" (str "/image/original/" (:id image))}
                    :geometry {
                               :type "Point"
                               :coordinates [(:longitude image) (:latitude image)]}})
                 (filter
                  #(and
                    (= (:tag %) tag)
                    (= tile (tile-math/zoom->location->tile zoom %)))
                  (list-all-images)))
         data {
               :type "FeatureCollection"
               :properties {}
               :features images}]
     {
      :status 200
      :body (json/write-to-string data)}))
  (compojure.core/GET
   "/map"
   _
   (let [images (map
                 (fn [image]
                   {
                    :type "Feature"
                    :properties {
                                 "url" (str "/image/original/" (:id image))}
                    :geometry {
                               :type "Point"
                               :coordinates [(:longitude image) (:latitude image)]}})
                 (list-all-images))]
     {
      :status 200
      :headers {
                "Content-Type" "text/html; charset=utf-8"}
      :body
      (hiccup/html
       [:head
        [:link {:rel "stylesheet" :href "https://unpkg.com/leaflet@1.3.4/dist/leaflet.css"}]
        [:script {:src "https://unpkg.com/leaflet@1.3.4/dist/leaflet.js"}]]
       [:html
        [:div {:id "map" :style "position: absolute;left: 0px;top: 0px;right: 0px;bottom: 0px;cursor: crosshair;"}]
        [:script {:type "text/javascript"}
         "var map = L.map('map', {maxBoundsViscosity: 1.0})\n"
         "map.setView([44.81667, 20.46667], 10)\n"
         "L.tileLayer(\n"
         "\t'https://tile.openstreetmap.org/{z}/{x}/{y}.png',\n"
         "\t{\n"
         "\t\tmaxZoom: 21, bounds:[[-90, -180], [90, 180]],\n"
         "\t\tnoWrap:true}).addTo(map)\n"]
        [:script {:type "text/javascript"}
         (str "var data = " (json/write-to-string images) "\n")
         "var pointToLayerFn = function(point, latlng) {\n"
         "\tvar marker = L.marker(latlng)\n"
         "\tmarker.bindPopup(\"<a href='\" + point.properties.url + \"' target='blank'><img src='\" + point.properties.url + \"' style='max-width: 300px;max-height: 300px'></img></a>\", {maxWidth: 'auto'})\n"
         "\treturn marker}\n"
         "var dataLayer = L.geoJSON(data, { pointToLayer: pointToLayerFn})\n"
         "dataLayer.addTo(map)\n"
         "map.fitBounds(dataLayer.getBounds())\n"]])}))
  (compojure.core/GET
   "/image/original/:id"
   [id]
   (let [image (find-image id)]
     (println "serving " id  (:path image))
     {
      :status 200
      :body (fs/input-stream (:path image))}))))
