(ns com.phronemophobic.cljonda.libwebgpu-native
  (:require [clojure.java.shell :as sh]
            [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            deps-deploy.deps-deploy
            [com.phronemophobic.cljonda.core
             :as cljonda]
            [babashka.fs :as fs]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]])
  (:import java.nio.file.Files))


(defn delete-tree
  "Deletes a file or directory."
  [f & [silently]]
  (when (.isDirectory f)
    (doseq [childf (.listFiles f)]
      (delete-tree childf silently)))
  (io/delete-file f silently))

(def build-dir (io/file "build"))

(defn sanitize-version [version]
  (str/replace version #"[^a-zA-Z0-9+_.]" "_"))

(defn create-cljonda-jar [package-name version lib-files ]
  (let [jar-file (io/file
                  build-dir
                  (str package-name ".jar"))

        package-build-dir
        (doto (io/file build-dir package-name)
          (.mkdirs))

        target-dir (io/file package-build-dir
                            (cljonda/system-arch))]
    (try
      ;; copy dylibs to the right spot
      (doseq [f lib-files
              :when (not (Files/isSymbolicLink (.toPath f)))
              :let [target-file (io/file target-dir
                                         (.getName f))
                    target-path (.getAbsolutePath target-file)]]
        (b/copy-file {:src (.getCanonicalPath f)
                      :target target-path}))

      ;; src dir with namespace
      (let [lib (symbol "com.phronemophobic.cljonda" (str package-name "-" (cljonda/system-arch)))
            class-dir (.getAbsolutePath package-build-dir)]
        (b/write-pom {:lib lib
                      :pom-data
                      [[:licenses
                        [:license
                         [:name "MIT License"]
                         [:url "https://github.com/gfx-rs/wgpu-native/blob/trunk/LICENSE.MIT"]]
                        [:license
                         [:name "Apache License 2.0"]
                         [:url "https://github.com/gfx-rs/wgpu-native/blob/trunk/LICENSE.APACHE"]]]]
                      :version version
                      :class-dir (.getAbsolutePath package-build-dir)
                      :basis {}})
        (b/jar {:class-dir class-dir
                :jar-file (.getAbsolutePath jar-file)})
        {:jar-file (.getAbsolutePath jar-file)
         :lib lib
         :pom-file (b/pom-path {:lib lib :class-dir class-dir})})
      
      (finally
        #_(delete-tree package-build-dir)
        ))))

(defn deploy-jar-pom [{:keys [jar-file pom-file]}]
  (try
    (println "deploying" jar-file)
    (deps-deploy.deps-deploy/deploy
     {:installer :remote
      :artifact jar-file
      :pom-file pom-file})
    (catch Exception e
      (if-not (str/includes? (ex-message e) "redeploying non-snapshots is not allowed")
        (throw e)
        (println "This release was already deployed.")))))


(defn assert-sh [& args]
  (prn "sh" args)
  (let [{:keys [exit out err]} (apply sh/sh args)]
    (when (not (zero? exit))
      (print out)
      (print err)
      (throw (ex-info "Shell Command Error"
                      {:type ::shell-command-error
                       :exit exit
                       :args args
                       :out out
                       :err err})))))



;; https://github.com/gfx-rs/wgpu-native/releases/download/v0.19.4.1/wgpu-macos-x86_64-release.zip

(defn prep-webgpu-native [version]
  (delete-tree build-dir true)
  (doto (io/file build-dir)
    (.mkdirs))
  (let [
        platform (case (cljonda/os)
                   "linux" "linux"
                   "darwin" "macos")
        arch (case (cljonda/arch)
               "x86-64" "x86_64"
               "aarch64" "aarch64")

        url (str "https://github.com/gfx-rs/wgpu-native/releases/download/"version "/wgpu-" platform "-" arch "-release.zip")
        zip-file (io/file build-dir "release.zip")
        release (io/file build-dir "release")]
    (with-open [is (io/input-stream (io/as-url url))
                os (io/output-stream zip-file)]
      (io/copy is os))
    (fs/unzip zip-file release)
    [(io/file release (str "libwgpu_native." (cljonda/shared-lib-suffix)))]))

(defn jar-webgpu-native [version lib-files]
  (create-cljonda-jar  "webgpu-native"
                       version
                       lib-files)
)

(defn deploy-webgpu-native [version]
  (let [lib-files (prep-webgpu-native version)
        deploy-info (jar-webgpu-native version
                                       lib-files)]
    (deploy-jar-pom deploy-info)))

(defn -main [version]
  (try

    (let [lib-files (prep-webgpu-native version)
          version (str version "-SNAPSHOT")
          deploy-info (jar-webgpu-native version
                                         lib-files)]
      #_(deploy-jar-pom deploy-info))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (case (:type data)

          ::shell-command-error
          (let [{:keys [exit args out err]} data]
            (println "args: " (pr-str args))
            (println "Exit: " exit)
            (println "---- out -----")
            (println out)
            (println "---- err -----")
            (println err)
            (println "--------------")
            (throw e))

          ;; else
          (println e)
          (throw e)
          ))
      )
    (catch Throwable e
      (println e)
      (throw e))))

(comment
  (-main "v0.19.4.1")
  

  ,
  )
