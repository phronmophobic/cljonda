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
        (assert-sh "cmake" "-DLLAMA_STATIC=ON" "-DGGML_STATIC=ON" "-DGGML_METAL_EMBED_LIBRARY=ON" "-DLLAMA_CURL=OFF" "-DLLAMA_BUILD_COMMON=OFF" "-DBUILD_SHARED_LIBS=OFF" ".."
                   ;; :env env
                   :dir cpp-build-dir)
        ;; linux
        (let [flags ["-DLLAMA_STATIC=ON" "-DGGML_STATIC=ON" "-DLLAMA_CURL=OFF" "-DLLAMA_BUILD_COMMON=OFF" "-DBUILD_SHARED_LIBS=OFF" "-DCMAKE_POSITION_INDEPENDENT_CODE=ON" ".."]
              args (into []
                         cat
                         [["cmake"]
                          flags
                          [".."]
                          [:env env
                           :dir cpp-build-dir]])]
          (apply assert-sh args)))

      (assert-sh "cmake" "--build" "." "--config" "Release"
                 ;; :env env
                 :dir cpp-build-dir)

      ;; combine static libraries into single shared lib
      ;; named llama-gguf
      (if (= "darwin" (cljonda/os))
        (let [make-dylib-cmd ["clang++"
                              "-dynamiclib"
                              "-o" "libllama-gguf.dylib" 
                              "-install_name" "libllama-gguf.dylib" 
                              "-Wl,-force_load,build/src/libllama.a"
                              "-Wl,-force_load,build/ggml/src/libggml.a"
                              "-Wl,-force_load,build/ggml/src/libggml-base.a"
                              "-Wl,-force_load,build/ggml/src/ggml-blas/libggml-blas.a"
                              "-Wl,-force_load,build/ggml/src/libggml-cpu.a"
                              "-Wl,-force_load,build/ggml/src/ggml-metal/libggml-metal.a"
                              "-framework" "Accelerate"
                              "-framework" "Metal"
                              "-framework" "MetalKit"
                              "-framework" "Foundation"
                              :dir lib-dir]]
          (apply assert-sh make-dylib-cmd))
        ;; else linux
        (let [make-so-cmd ["g++"
                           "-shared"
                           "-Wl,-soname,libllama-gguf.so"
                           "-o"
                           "libllama-gguf.so"
                           "-Wl,--whole-archive"
                           "build/src/libllama.a"
                           "build/ggml/src/libggml.a"
                           "build/ggml/src/libggml-base.a"
                           "build/ggml/src/libggml-cpu.a"
                           "-Wl,--no-whole-archive"
                           "-lgomp"
                           :dir lib-dir]]
          (apply assert-sh make-so-cmd)))


      

      [(io/file lib-dir (str "libllama-gguf." (cljonda/shared-lib-suffix)))])))

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

(defn -main [commit subversion]
  (try
    (let [lib-files (prep-llama commit)
          version (str commit subversion)
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
