(ns com.phronemophobic.cljonda
  (:require [libpython-clj2.python :refer [py. py.. py.-] :as py]
            deps-deploy.deps-deploy
            loom.alg
            loom.graph
            [clojure.java.shell :as sh]
            [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [com.phronemophobic.cljonda.core
             :as cljonda]
            [clojure.string :as str]
            [com.rpl.specter :as specter]
            [clojure.pprint :refer [pprint]])
  (:import java.nio.file.Files))

(def build-dir (io/file "build"))

(defn fix-python-cycles [packages]
  (->> packages
       (specter/setval [specter/ALL
                        (fn [package]
                          (#{"setuptools" "wheel" "pip"} (get package "name")))
                        (specter/keypath "depends")
                        specter/ALL
                        #(str/starts-with? % "python")]
                       specter/NONE)))

(defn delete-tree
  "Deletes a file or directory."
  [f & [silently]]
  (when (.isDirectory f)
    (doseq [childf (.listFiles f)]
      (delete-tree childf silently)))
  (io/delete-file f silently))

(defn relative-path [rootf childf]
  (let [child-path (.getCanonicalPath childf)
        root-path (.getAbsolutePath rootf)
        _ (assert (str/starts-with? child-path root-path))]
    (subs child-path
          (inc (count root-path)))))

(defn prefix-for-env [env-name]
  (.getAbsolutePath
   (io/file (System/getProperty "user.home")
            "miniconda3"
            "envs"
            env-name)))

(defn parse-otool-output [s]
  (let [lines (str/split-lines s)]
    (into #{}
          (comp (drop 1)
                (map str/trim)
                (remove empty?)
                (map  (fn [s]
                        (subs s 0 (str/index-of s "("))))
                (map str/trim))
          lines)))

(defn install-name [path]
  (let [output (:out (sh/sh "otool" "-D" (.getAbsolutePath (io/file path))))]
    (second (str/split-lines output))))

(defn dylib-deps [path]
  (let [f (io/file path)
        output (:out (sh/sh "otool" "-L" (.getAbsolutePath f)))
        deps (parse-otool-output output)
        parent-dir-path (-> f
                            (.getParentFile)
                            (.getAbsolutePath))
        deps (into (empty deps)
                   (comp (map (fn [s]
                                (str/replace s #"@rpath" parent-dir-path))))
                   deps)]
    deps))

(defn transitive-deps [path]
  (loop [visited #{}
         deps (dylib-deps path)]
    (if-let [path (first (set/difference deps visited))]
      (do
        (prn path)
       (recur (conj visited path)
              (into deps (dylib-deps path))))
      deps)))

(defn deps-tree [paths]
  (loop [visited #{}
         to-visit (set paths)
         deps {}]
    (if-let [path (first (set/difference to-visit visited))]
      (let [path-deps (disj (dylib-deps path)
                            path)
            visited (conj visited path)
            to-visit (set/difference (into to-visit path-deps)
                                     visited)
            deps (assoc deps path path-deps)]
        (recur visited
               to-visit
               deps))
      deps)))


(py/initialize! :python-executable (str (prefix-for-env "cljonda-meta")
                                        "/bin/python")
                :library-path (str (prefix-for-env "cljonda-meta")
                                   (str "lib/libpython3.11." (cljonda/shared-lib-suffix))))
(require '[libpython-clj2.require :refer [require-python]])

(require-python '[conda.api :as conda-api])
(require-python '[conda.cli.python_api :as conda-cli])


(defn conda-installed-packages [prefix]
  (let [solver (conda-api/Solver prefix [])
        solution (py. solver "solve_final_state")]
    (py/->jvm solution)))

(defn temp-dir []
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "cljonda-"
    (into-array java.nio.file.attribute.FileAttribute []))))

(defn sanitize-package-name [package-name]
  (-> package-name
      munge
      (str/replace #"[^a-zA-Z0-9+_]" "_")))

(defn package-name->sym [package-name]
  (symbol (str "com.phronemophobic.cljonda." package-name)))

(defn sanitize-version [version]
  (str/replace version #"[^a-zA-Z0-9+_.]" "_"))

(def cljonda-version "0.9.4")

(defn create-cljonda-jar [prefix versions package]
  (let [package-name (sanitize-package-name (get package "name"))]
    (let [jar-file (io/file
                    build-dir
                    (str package-name ".jar"))

          package-build-dir
          (doto (io/file build-dir (get package "name"))
            (.mkdirs))

          lib-files
          (into #{}
                (filter #(str/starts-with? % "lib/"))
                (get package "files"))

          target-dir (io/file package-build-dir
                              "com"
                              "phronemophobic"
                              "cljonda"
                              package-name
                              (cljonda/system-arch))

          package-dependencies
          (into []
                (map (fn [depend-str]
                       (let [version-delimiter-index (str/index-of depend-str " ")
                             package-name (if version-delimiter-index
                                            (subs depend-str
                                                  0 version-delimiter-index)
                                            depend-str)]
                         package-name)))
                (get package "depends"))
          
          ]
      (do (delete-tree package-build-dir)
          (.mkdir package-build-dir))
      
      (try
        ;; copy dylibs to the right spot
        (doseq [filename lib-files
                :let [f (io/file prefix filename)]
                :when (not (Files/isSymbolicLink (.toPath f)))
                :let [target-file (io/file target-dir
                                           filename)
                      target-path (.getAbsolutePath target-file)]]
          (b/copy-file {:src (.getCanonicalPath f)
                        :target target-path}))

        ;; src dir with namespace
        (let [requires
              (into []
                    (comp (map sanitize-package-name)
                          (map package-name->sym))
                    package-dependencies)
              
              ns-code (list 'ns (package-name->sym package-name)
                            ;;`(:require ~@requires)
                            (list :require '[com.phronemophobic.cljonda.core :refer [extract-lib]]))


              package-files (into []
                                  (map
                                   (fn [path]
                                     (let [f (io/file prefix path)]
                                       {:from (relative-path (io/file prefix)
                                                             f)
                                        :link? (Files/isSymbolicLink (.toPath f))
                                        :to path})))
                                  lib-files)

              extract-code
              [;; `(def ~'package-files ~package-files)
               (list 'extract-lib package-name)]]

          ;; write package files
          (let [package-file (io/file
                              package-build-dir
                              "com"
                              "phronemophobic"
                              "cljonda"
                              package-name
                              (str "package-info-" (cljonda/system-arch) ".edn"))]
            (.mkdirs (.getParentFile package-file))
            (with-open [w (io/writer package-file)]
              (binding [*out* w
                        *print-length* nil
                        *print-level* nil
                        *print-dup* false
                        *print-meta* false
                        *print-readably* true]
                (pr {:files package-files
                     :requires requires}))))

          ;; generate lib namespace
          (let [ns-filename (str package-name ".clj")
                src-file (io/file package-build-dir
                                  "com"
                                  "phronemophobic"
                                  "cljonda"
                                  ns-filename)]
            (.mkdirs (.getParentFile src-file))
            (with-open [w (io/writer src-file)]
              (pprint ns-code w)
              
              (binding [*out* w
                        *print-length* nil
                        *print-level* nil
                        *print-dup* false
                        *print-meta* false
                        *print-readably* true]

                (println)
                (println)
                (doseq [top-level-form extract-code]
                  (prn top-level-form)
                  (println)))))

          (let [lib (symbol "com.phronemophobic.cljonda" (str package-name "-" (cljonda/system-arch)))
                class-dir (.getAbsolutePath package-build-dir)]
            (b/write-pom {:lib lib
                          :version (str (sanitize-version
                                         (get package "version"))
                                        "-"
                                        cljonda-version)
                          :class-dir (.getAbsolutePath package-build-dir)
                          :basis {:libs (into `{com.phronemophobic/cljonda-core {:mvn/version ~cljonda-version}}
                                              (map (fn [package-name]
                                                     [(symbol "com.phronemophobic.cljonda"
                                                              (str (sanitize-package-name package-name)
                                                                   "-" (cljonda/system-arch)))
                                                      {:mvn/version (str (sanitize-version
                                                                          (versions package-name))
                                                                         "-" cljonda-version)}]))
                                              package-dependencies)}})
            (b/jar {:class-dir class-dir
                    :jar-file (.getAbsolutePath jar-file)})
            {:jar-file (.getAbsolutePath jar-file)
             :lib lib
             :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
        
        (finally
          #_(delete-tree package-build-dir)
          )))))

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

(defn export-prefix [prefix]
  (let [packages (-> (conda-installed-packages prefix)
                     (fix-python-cycles))
        versions (into {}
                       (map (fn [package]
                              [(get package "name")
                               (get package "version")]))
                       packages)]
    (delete-tree build-dir true)
    (into []
          (map #(doto (create-cljonda-jar prefix versions %)
                  prn))
          packages)))

(defn deploy-prefix [prefix]
  (let [packages (conda-installed-packages prefix)
        versions (into {}
                       (map (fn [package]
                              [(get package "name")
                               (get package "version")]))
                       packages)
        jar-poms (export-prefix prefix)]
    (prn "exported")
    (prn jar-poms)
    (doseq [jar-pom jar-poms]
      (deploy-jar-pom jar-pom))))

(defn -main [& args]
  (deploy-prefix "/Users/adrian/miniconda3/envs/cljonda/")
  #_(export-prefix "/Users/adrian/miniconda3/envs/cljonda/"))


(defn create-env [env-name packages]
  (let [[stdout stderr ret]
        (apply conda-cli/run_command
               "create"
               "-c" "conda-forge"
               "-p" (prefix-for-env env-name)
               packages)]
    (println stdout)
    (println stderr)
    (assert (zero? ret)))
    (prn "created!"))

(defn remove-env [env-name]
  (let [[stdout stderr ret]
        (conda-cli/run_command
         "remove"
         "--all"
         "-p" (prefix-for-env env-name))]
    (println stdout)
    (println stderr)))

(defn export [{:keys [packages]}]
  (create-env "cljonda" packages)
  (export-prefix (prefix-for-env "cljonda")))

(defn deploy [{:keys [packages]
               :as opts}]
  (remove-env "cljonda")
  (create-env "cljonda" packages)
  (deploy-prefix (prefix-for-env "cljonda")))

(comment

  (create-env "cljonda" ["graphviz==2.50.0"])
  (export-prefix (prefix-for-env "cljonda"))


  ,)
