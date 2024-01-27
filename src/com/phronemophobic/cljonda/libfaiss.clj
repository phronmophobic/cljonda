(ns com.phronemophobic.cljonda.libfaiss
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
                      :src-pom (-> (io/file "resources"
                                            "faiss-cpp-template-pom.xml")
                                   (.getCanonicalPath))
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

(defn prep-faiss [commit]
  (delete-tree build-dir true)
  (doto (io/file build-dir)
    (.mkdirs))
  (let [lib-dir (io/file build-dir "faiss")
        lib-path (.getCanonicalPath lib-dir)]
    (when (not (.exists lib-dir))
      (assert-sh "git" "clone"
                 "https://github.com/facebookresearch/faiss.git"
                 :dir (.getCanonicalPath (io/file lib-dir "../"))))
    
    (assert-sh "git" "checkout" commit
               :dir lib-path)

    (assert-sh "git" "submodule" "update" "--init"
               :dir lib-path)

    (let [cpp-build-dir lib-dir
          env (when (= "darwin" (cljonda/os))
                (merge
                 {;; "MACOSX_DEPLOYMENT_TARGET" "10.3.9"
                  }
                 (when (= (cljonda/arch) "aarch64")
                   {"CC" "/opt/local/bin/clang-mp-16"
                    "CMAKE_C_FLAGS" "-L/opt/local/lib"
                    "CMAKE_CXX_FLAGS" "-L/opt/local/lib"
                    "CXX" "/opt/local/bin/clang++-mp-16"})))
          flags ["-DBUILD_SHARED_LIBS=ON"
                 "-DBUILD_TESTING=OFF"
                 "-DFAISS_ENABLE_GPU=OFF"
                 "-DFAISS_ENABLE_PYTHON=OFF"
                 "-DFAISS_ENABLE_C_API=ON"
                 "-DFAISS_OPT_LEVEL=generic"
                 "-DCMAKE_INSTALL_LIBDIR=lib"
                 "-DCMAKE_BUILD_TYPE=Release"
                 "--install-prefix" (.getCanonicalPath
                                     (io/file lib-dir "build" "out"))]]
      (let [args (into []
                       cat
                       [["cmake" "-B" "build"]
                        flags
                        ["."]
                        [:env env
                         :dir cpp-build-dir]])]
        (apply assert-sh args))

      (assert-sh "make" "-C" "build"
                 :env env
                 :dir cpp-build-dir)
      (assert-sh "make" "-C" "build" "install"
                 :env env
                 :dir cpp-build-dir)
      
      (into []
            (map (fn [libname]
                   (let [lib-filename (str "lib" libname "." (cljonda/shared-lib-suffix))
                         target-file (io/file lib-dir lib-filename)
                         target-path (.getCanonicalPath target-file) ]
                     (b/copy-file {:src (.getCanonicalPath (io/file cpp-build-dir lib-filename)) 
                                   :target target-path})
                     target-file)))
            ["faiss"]))))

(defn jar-faiss [version lib-files]
  (create-cljonda-jar  "faiss"
                       version
                       lib-files)
)

(defn deploy-faiss [commit]
  (let [lib-files (prep-faiss commit)
        deploy-info (jar-faiss commit
                               lib-files)]
    (deploy-jar-pom deploy-info)))

(defn -main [commit]
  (try
    (prn (prep-faiss commit))
    #_(let [lib-files (prep-faiss commit)
            version commit
            deploy-info (jar-faiss version
                                   lib-files)]
        (deploy-jar-pom deploy-info))
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
  (prep-faiss "a30fd74333356e74b93536fd83126748743e90fa")


  ,
  )
