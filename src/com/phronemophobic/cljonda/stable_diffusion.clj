(ns com.phronemophobic.cljonda.stable-diffusion
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
                      :pom-data
                      [[:licenses
                        [:license
                         [:name "MIT License"]
                         [:url "https://github.com/leejet/stable-diffusion.cpp/blob/master/LICENSE"]]]]
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

(defn prep-stable-diffusion [commit]
  (delete-tree build-dir true)
  (doto (io/file build-dir)
    (.mkdirs))
  (let [lib-dir (io/file build-dir "stable-diffusion.cpp")
        lib-path (.getCanonicalPath lib-dir)]
    (when (not (.exists lib-dir))
      (assert-sh "git" "clone"
                 "https://github.com/leejet/stable-diffusion.cpp"
                 :dir (.getCanonicalPath (io/file lib-dir "../"))))
    
    (assert-sh "git" "checkout" commit
               :dir lib-path)

    (assert-sh "git" "submodule" "init"
               :dir lib-path)
    (assert-sh "git" "submodule" "update"
               :dir lib-path)
    

    (let [cpp-build-dir (doto (io/file lib-dir "build")
                          (.mkdirs))
          env (when (= "darwin" (cljonda/os))
                {})]
      (if (= "darwin" (cljonda/os))
        ;; macosx, add metal
        (assert-sh "cmake" "-DSD_METAL=ON" "-DSD_BUILD_SHARED_LIBS=ON" "-DGGML_METAL_EMBED_LIBRARY=ON" ".." 
                   :env env
                   :dir cpp-build-dir)
        ;; linux
        #_(let [flags ["-DBUILD_SHARED_LIBS=ON"]
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
      

      (let [lib-file (io/file cpp-build-dir "bin" (str "libstable-diffusion." (cljonda/shared-lib-suffix)))]
        (prn "lib file:" lib-file)
        [lib-file]))))

(defn jar-stable-diffusion [version lib-files]
  (create-cljonda-jar  "stable-diffusion-cpp"
                       version
                       lib-files)
)

(defn deploy-stable-diffusion [commit]
  (let [lib-files (prep-stable-diffusion commit)
        deploy-info (jar-stable-diffusion commit
                               lib-files)]
    (deploy-jar-pom deploy-info)))

(defn -main [commit]
  (try
    (let [lib-files (prep-stable-diffusion commit)
          version (str commit "-SNAPSHOT")
          deploy-info (jar-stable-diffusion version
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

  (let [commit "9c51d8787f78ef1bd0ead1e8f48b766d7ee7484d"
        lib-files (prep-stable-diffusion commit )
        deploy-info (jar-stable-diffusion commit
                                          lib-files)]
    deploy-info)
  ,
  )
