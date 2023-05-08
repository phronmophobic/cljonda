(ns com.phronemophobic.cljonda.core
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))


(defn temp-dir []
  (doto (io/file "/tmp"
                 "com.phronemophobic.cljonda")
    (.mkdirs)))

(defonce library-dir
  (delay (temp-dir)))


(def init-library-paths
  (delay
    (let [dir (temp-dir)
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
  (doseq [filename package-files
          :let [path (str/join "/"
                               ["com"
                                "phronemophobic"
                                "cljonda"
                                package-name
                                "darwin-aarch64"
                                filename])
                _ (prn path)
                resource (io/resource path)]]
    (let [dir @library-dir
          dest (io/file dir filename)]
      (.mkdirs (.getParentFile dest))
      (with-open [is (io/input-stream resource)]
        (io/copy is dest)))))
