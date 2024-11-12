(ns com.phronemophobic.cljonda.libwhisper
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
                         [:url "https://mit-license.org/"]]]]
                      
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

(defn prep-whisper [commit]
  (delete-tree build-dir true)
  (doto (io/file build-dir)
    (.mkdirs))
  (let [lib-dir (io/file build-dir "whisper.cpp")
        lib-path (.getCanonicalPath lib-dir)]
    (when (not (.exists lib-dir))
      (assert-sh "git" "clone"
                 "https://github.com/ggerganov/whisper.cpp"
                 :dir (.getCanonicalPath (io/file lib-dir "../"))))
    
    (assert-sh "git" "checkout" commit
               :dir lib-path)

    (assert-sh "git" "submodule" "update" "--init"
               :dir lib-path)

    (let [cpp-build-dir (doto (io/file lib-dir "build")
                          (.mkdirs))
          env (when (= "darwin" (cljonda/os))
                {:env {"MACOSX_DEPLOYMENT_TARGET" "10.1"}})]
      (if (= "darwin" (cljonda/os))
        ;; macosx, add metal
        (assert-sh "cmake" "-DBUILD_SHARED_LIBS=ON"
                   "-DGGML_USE_ACCELERATE=1" "-DGGML_USE_METAL=1" "-DGGML_METAL_EMBED_LIBRARY=1"
                   ".."
                   :env env
                   :dir cpp-build-dir)
        ;; linux
        (let [flags ["-DBUILD_SHARED_LIBS=ON"]
              args (into []
                         cat
                         [["cmake"]
                          flags
                          [".."]
                          [:env env
                           :dir cpp-build-dir]])]
          (apply assert-sh args)))

      (assert-sh "make"
                 :env env
                 :dir cpp-build-dir)
      (let [whisper-lib (io/file lib-dir "build" "src" (str "libwhisper." (cljonda/shared-lib-suffix)))
            src (.getCanonicalPath
                            (.toFile
                             (-> whisper-lib
                                 .toPath
                                 (.toRealPath (into-array java.nio.file.LinkOption [])))))]
        (prn src)
        (.delete whisper-lib)
        ;; resolve symbolic link
        (b/copy-file {:src  src
                      :target (.getCanonicalPath whisper-lib)})
        [whisper-lib
         (io/file lib-dir "build" "ggml" "src" (str "libggml." (cljonda/shared-lib-suffix)))]))))

(defn jar-whisper [version lib-files]
  (create-cljonda-jar  "whisper-cpp"
                       version
                       lib-files)
)

(defn deploy-whisper [commit]
  (let [lib-files (prep-whisper commit)
        deploy-info (jar-whisper commit
                               lib-files)]
    (deploy-jar-pom deploy-info)))

(defn -main [commit]
  (try
    (let [lib-files (prep-whisper commit)
          version (str commit "-SNAPSHOT")
          deploy-info (jar-whisper version
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


  (deploy-whisper "8f348725271db67517de871dea4a4e8a159e664f")
  (-main "8f348725271db67517de871dea4a4e8a159e664f")

  (let [commit "c96906d84dd6a1c40ea797ad542df3a0c47307a3"
        lib-files (prep-whisper commit )
        deploy-info (jar-whisper commit
                               lib-files)]
    deploy-info)
  ,
  )
