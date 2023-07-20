(ns com.phronemophobic.cljonda.libllama
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

(defn prep-llama [commit]
  (doto (io/file build-dir)
    (.mkdirs))
  (let [lib-dir (io/file build-dir "llama.cpp")
        lib-path (.getCanonicalPath lib-dir)]
    (when (not (.exists lib-dir))
      (assert-sh "git" "clone" "https://github.com/ggerganov/llama.cpp"
                 :dir (.getCanonicalPath (io/file lib-dir "../"))))
    
    (assert-sh "git" "checkout" commit
               :dir lib-path)

    (let [cpp-build-dir (doto (io/file lib-dir "build")
                          (.mkdirs))
          env (when (= "darwin" (cljonda/os))
                {:env {"MACOSX_DEPLOYMENT_TARGET" "10.1"}})]
      (if (= "darwin" (cljonda/os))
        ;; macosx, add metal
        (assert-sh "cmake" "-DLLAMA_METAL=ON" "-DBUILD_SHARED_LIBS=ON" ".."
                   :env env
                   :dir cpp-build-dir)
        ;; linux
        (assert-sh "cmake" "-DBUILD_SHARED_LIBS=ON" ".."
                   :env env
                   :dir cpp-build-dir))

      (assert-sh "cmake" "--build" "." "--config" "Release"
                 :env env
                 :dir cpp-build-dir)
      

      (let [target-file (io/file lib-dir (str "libllama." (cljonda/shared-lib-suffix)))
            target-path (.getCanonicalPath target-file) ]
        (b/copy-file {:src (.getCanonicalPath (io/file cpp-build-dir (str "libllama." (cljonda/shared-lib-suffix)))) 
                      :target target-path})
        [target-file]))))

(defn jar-llama [version lib-files]
  (create-cljonda-jar  "llama-cpp"
                       version
                       lib-files)
)

(defn deploy-llama [commit]
  (let [lib-files (prep-llama commit)
        deploy-info (jar-llama (str commit "-VERSION")
                               lib-files)]
    (deploy-jar-pom deploy-info)))

(defn -main [commit]
  (try
    (deploy-llama commit)
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
  (deploy-llama "294f424554c1599784ac9962462fc39ace92d8a5")
  (-main "294f424554c1599784ac9962462fc39ace92d8a5")
  )
