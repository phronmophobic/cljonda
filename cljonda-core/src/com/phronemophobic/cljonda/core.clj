(ns com.phronemophobic.cljonda.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import java.nio.file.Files))


(defn temp-dir []
  (doto (io/file "/tmp"
                 "com.phronemophobic.cljonda"
                 "lib")
    (.mkdirs)))

(defonce library-dir
  (delay (temp-dir)))

(def init-library-paths
  (delay
    (let [dir @library-dir
          new-path (if-let [library-path (System/getProperty "jna.library.path")]
                     (str library-path ":" (.getAbsolutePath dir))
                     (.getAbsolutePath dir))]
      (System/setProperty "jna.library.path" new-path)
      (System/setProperty "java.library.path"
                          (str
                           (System/getProperty "java.library.path")
                           ":"
                           (.getAbsolutePath dir))))))

(defn extract-lib [package-name package-files]
  @init-library-paths
  (let [dir @library-dir
        empty-attributes (into-array java.nio.file.attribute.FileAttribute [])]
    (doseq [{:keys [from to link?]} package-files
            :let [dest (io/file dir to)]]
      (.mkdirs (.getParentFile dest))
      (if link?
        (Files/createSymbolicLink (.toPath dest)
                                  (.toPath (io/file dir from))
                                  empty-attributes)
        ;; copy file
        (let [resource-path (str/join "/"
                                      ["com"
                                       "phronemophobic"
                                       "cljonda"
                                       package-name
                                       "darwin-aarch64"
                                       from])
              resource (io/resource resource-path)]
          (with-open [is (io/input-stream resource)]
            (io/copy is dest)))))))
