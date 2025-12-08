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
                                            "llama-cpp-template-pom.xml")
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

(defn prep-llama [commit]
  (delete-tree build-dir true)
  (doto (io/file build-dir)
    (.mkdirs))
  (let [lib-dir (io/file build-dir "llama.cpp")
        lib-path (.getCanonicalPath lib-dir)]
    (when (not (.exists lib-dir))
      (assert-sh "git" "clone"
                 ;; "https://github.com/phronmophobic/llama.cpp.git"
                 "https://github.com/ggerganov/llama.cpp"
                 :dir (.getCanonicalPath (io/file lib-dir "../"))))
    
    (assert-sh "git" "checkout" commit
               :dir lib-path)

    (let [cpp-build-dir (doto (io/file lib-dir "build")
                          (.mkdirs))
          env (when (= "darwin" (cljonda/os))
                {})]
      (if (= "darwin" (cljonda/os))
        ;; macosx, add metal
        (assert-sh "cmake" "-DBUILD_SHARED_LIBS=ON" "-DGGML_METAL_EMBED_LIBRARY=ON" "-DLLAMA_CURL=OFF" ".."
                   :env env
                   :dir cpp-build-dir)
        ;; linux
        (let [flags ["-DBUILD_SHARED_LIBS=ON" "-DLLAMA_CURL=OFF"]
              args (into []
                         cat
                         [["cmake"]
                          flags
                          [".."]
                          [:env env
                           :dir cpp-build-dir]])]
          (apply assert-sh args)))

      (assert-sh "cmake" "--build" "." "--config" "Release"
                 :env env
                 :dir cpp-build-dir)
      

      (let [target-file (io/file lib-dir (str "libllama-gguf." (cljonda/shared-lib-suffix)))
            target-path (.getCanonicalPath target-file) ]
        (b/copy-file {:src (.getCanonicalPath (io/file cpp-build-dir "bin" (str "libllama." (cljonda/shared-lib-suffix))))
                      :target target-path})
        ;; update install name/so name
        (if (= "darwin" (cljonda/os))
          (assert-sh "install_name_tool" "-id" (.getName target-file) (.getName target-file)
                     :dir (.getParentFile target-file))
          ;; linux
          (assert-sh "patchelf" "--set-soname" (.getName target-file) (.getName target-file)
                     :dir (.getParentFile target-file)))

        (let [ggml-libs (into []
                              (comp
                               (map (fn [libname]
                                      (io/file cpp-build-dir "bin" (str "lib" libname "." (cljonda/shared-lib-suffix)))))
                               (filter #(.exists %)))
                              ["ggml"
                               "ggml-cpu"
                               "ggml-blas"
                               "ggml-metal"
                               "ggml-base"])]
          (into [target-file] ggml-libs))))))

(defn jar-llama [version lib-files]
  (create-cljonda-jar  "llama-cpp-gguf"
                       version
                       lib-files)
)

(defn deploy-llama [commit]
  (let [lib-files (prep-llama commit)
        deploy-info (jar-llama commit
                               lib-files)]
    (deploy-jar-pom deploy-info)))

(defn -main [commit]
  (try
    (let [lib-files (prep-llama commit)
          version (str commit #_"-SNAPSHOT")
          deploy-info (jar-llama version
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
  (deploy-llama "c3f197912f1ce858ac114d70c40db512de02e2e0")
  (-main "c3f197912f1ce858ac114d70c40db512de02e2e0")

  (let [commit "b7325"
        lib-files (prep-llama commit )
        deploy-info (jar-llama commit
                               lib-files)]
    deploy-info)
  ,
  )
