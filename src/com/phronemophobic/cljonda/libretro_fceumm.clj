(ns com.phronemophobic.cljonda.libretro_fceumm
  (:require [clojure.java.shell :as sh]
            [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            deps-deploy.deps-deploy
            [com.phronemophobic.cljonda.core
             :as cljonda]
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
    (do (delete-tree package-build-dir)
        (.mkdir package-build-dir))
    
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
  (let [{:keys [exit]} (apply sh/sh args)]
    (assert (zero? exit))))

(defn prep-libretro [commit]
  (doto (io/file build-dir)
    (.mkdirs))
  (let [lib-dir (io/file build-dir "libretro-fceumm")
        lib-path (.getCanonicalPath lib-dir)]
    (when (not (.exists lib-dir))
      (assert-sh "git" "clone" "https://github.com/libretro/libretro-fceumm.git"
                 :dir (.getCanonicalPath (io/file lib-dir "../"))))
    
    (assert-sh "git" "checkout" commit
               :dir lib-path)

    (assert-sh "make"
               :dir lib-path)
    
    (let [target-file (io/file lib-dir (str "libretro_fceumm." (cljonda/shared-lib-suffix)))
          target-path (.getCanonicalPath target-file) ]
      (b/copy-file {:src (.getCanonicalPath (io/file lib-dir (str "fceumm_libretro." (cljonda/shared-lib-suffix)))) 
                    :target target-path})
      [target-file])))

(defn jar-libretro [version lib-files]
  (create-cljonda-jar  "com.phronemophobic.cljonda.libretro_fceumm"
                       version
                       lib-files)
)

(defn deploy-libretro [commit]
  (let [lib-files (prep-libretro commit)
        deploy-info (jar-libretro (str commit "-SNAPSHOT")
                               lib-files)]
    (deploy-jar-pom deploy-info)))

