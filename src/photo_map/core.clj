(ns photo-map.core
  (:require
   [hiccup.core :as hiccup]

   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.http-server :as http-server]
   [clj-common.path :as path]))

(def storage (atom {}))

(defn list-all-images []
  (vals (deref storage)))

(defn append-image [longitude latitude image-id image-path]
  (swap!
   storage
   assoc
   image-id
   {
    :id image-id
    :longitude longitude
    :latitude latitude
    :path image-path}))

(defn find-image [image-id]
  (get (deref storage) image-id))

;; add images ...
#_(doseq [image-path (filter
                    #(.endsWith (last %) ".jpg")
                    (fs/list
                     (path/string->path
                      "/Users/vanja/my-dataset-temp/photo-evidence-3055-3284/")))]
  (println "processing: " (path/path->string image-path))
  (let [metadata (com.drew.imaging.ImageMetadataReader/readMetadata
                  (new java.io.File (path/path->string image-path)))]
    (if-let [gpx-directory (first (.getDirectoriesOfType
                                   metadata
                                   com.drew.metadata.exif.GpsDirectory))]
      (append-image
       (.getLongitude (.getGeoLocation gpx-directory))
       (.getLatitude (.getGeoLocation gpx-directory))
       (.replace (last image-path) ".jpg" "")
       image-path))))

(http-server/create-server
 7076
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


