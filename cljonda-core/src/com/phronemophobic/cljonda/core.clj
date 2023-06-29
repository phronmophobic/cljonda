(ns com.phronemophobic.cljonda.core
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import java.io.PushbackReader
           java.nio.file.Files
           java.nio.file.FileAlreadyExistsException))


(def ^:private system-os*
  (delay
    (let [os-name (System/getProperty "os.name")
          os (if (str/includes? (str/lower-case os-name)
                                "linux")
               "linux"
               "darwin")]
      os)))
(def ^:private system-arch*
  (delay
    (let [arch-name (System/getProperty "os.arch")]
      (case arch-name
        "amd64" "x86-64"
        "x86_64" "x86-64"
        "aarch64" "aarch64"
        "arm64" "arm64"))))
(def ^:private system*
  (delay
    (str @system-os* "-" @system-arch*)))
(defn system-arch []
  @system*)

(defn shared-lib-suffix []
  (case @system-os*
    "linux" "so"
    "darwin" "dylib"))

(def temp-dir
  (io/file "/tmp"
           "com.phronemophobic.cljonda"))

(def library-dir
  (io/file temp-dir "lib"))

(defn update-path [prop path]
  (System/setProperty prop
                      (if-let [old-path (System/getProperty prop)]
                        (str old-path ":" path)
                        path)))

(def init-library-paths
  (delay
    (let [lib-path (.getAbsolutePath library-dir)]
      (update-path "jna.library.path" lib-path)
      (update-path "java.library.path" lib-path))))

(defn extract-lib [package-name]
  @init-library-paths
  (let [package-file-resource (io/resource
                               (clojure.string/join "/"
                                                    ["com"
                                                     "phronemophobic"
                                                     "cljonda"
                                                     (munge package-name)
                                                     (str "package-files-"(system-arch) ".edn")]))
        package-files (with-open [is (io/input-stream package-file-resource)
                                  rdr (io/reader is)
                                  rdr (PushbackReader. rdr)]
                        (edn/read rdr))

        empty-attributes (into-array java.nio.file.attribute.FileAttribute [])]
    (doseq [{:keys [from to link?]} package-files
            :let [dest (io/file temp-dir to)]]
      (.mkdirs (.getParentFile dest))
      (if link?
        (try
          (Files/deleteIfExists (.toPath dest))
          (Files/createSymbolicLink (.toPath dest)
                                    (.toPath (io/file temp-dir from))
                                    empty-attributes)
          (catch FileAlreadyExistsException e
            nil))
        ;; copy file
        (let [resource-path (str/join "/"
                                      ["com"
                                       "phronemophobic"
                                       "cljonda"
                                       package-name
                                       (system-arch)
                                       from])
              resource (io/resource resource-path)]
          (when resource
            (with-open [is (io/input-stream resource)]
              (io/copy is dest))))))))
