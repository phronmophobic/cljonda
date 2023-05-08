(ns com.phronemophobic.cljonda
  (:require [libpython-clj2.python :refer [py. py.. py.-] :as py]
            deps-deploy.deps-deploy
            loom.alg
            loom.graph
            [clojure.java.shell :as sh]
            [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]])
  (:import java.nio.file.Files))

(def build-dir (io/file "build"))
(def prefix  "/Users/adrian/miniconda3/envs/cljonda")

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

(comment
  (def all-dylibs
    (->> (io/file "/Users/adrian/miniconda3/envs/cljonda/lib/")
        (.listFiles)
        
        (filter #(str/ends-with? (.getName %) ".dylib"))
        (map #(.getAbsolutePath %))))

  (pprint
   (into []
         (map (juxt identity
                    install-name))
         all-dylibs))

  (def roots
    #{"/Users/adrian/miniconda3/envs/cljonda/lib/libexpat.dylib"
      "/Users/adrian/miniconda3/envs/cljonda/lib/libgd.dylib"
      "/Users/adrian/miniconda3/envs/cljonda/lib/libpango-1.0.0.dylib"
      "/Users/adrian/miniconda3/envs/cljonda/lib/libpango-1.0.dylib"
      "/Users/adrian/miniconda3/envs/cljonda/lib/libpangocairo-1.0.0.dylib"
      "/Users/adrian/miniconda3/envs/cljonda/lib/libpangocairo-1.0.dylib"
      "/Users/adrian/miniconda3/envs/cljonda/lib/libgts.dylib"
      "/Users/adrian/miniconda3/envs/cljonda/lib/libgvc.dylib"})


  (def dtree (deps-tree roots))
  (def ordered (loom.alg/topsort (loom.graph/digraph dtree)))

  (transitive-deps "/Users/adrian/miniconda3/envs/cljonda/lib/libgvc.dylib")

  (require '[com.phronemophobic.clj-graphviz :refer [render-graph]])

  (def real-dylibs
    (->> ordered
         (filter #(str/starts-with? %
                                    "/Users/adrian/miniconda3/envs/cljonda"))
         (map #(.getCanonicalPath (io/file %)))
         (distinct)
         reverse
         (into [])))

  (def size
    (transduce (map #(.length (io/file %)))
               +
               0
               real-dylibs))

  (defn filename [s]
    (-> s
        io/file
        .getName))

  
  ,)


(py/initialize! :python-executable "/Users/adrian/miniconda3/envs/cljonda-meta/bin/python"
                  :library-path "/Users/adrian/miniconda3/envs/cljonda-meta/lib/libpython3.11.dylib")
(require '[libpython-clj2.require :refer [require-python]])

(require-python '[conda.api :as conda-api])


(defn conda-installed-packages [prefix]
  (let [solver (conda-api/Solver prefix [])
        solution (py. solver "solve_final_state")]
    (py/->jvm solution)))



(defn temp-dir []
  (.toFile
   (java.nio.file.Files/createTempDirectory
    "cljonda-"
    (into-array java.nio.file.attribute.FileAttribute []))))

(defn package-name->sym [package-name]
  (symbol (str "com.phronemophobic.cljonda." package-name)))

(defn libname->sym [filename]
  (symbol
   (-> filename
       (str/replace #"^lib" "")
       (str/replace #".dylib" "")
       (str/replace #"\." "_"))))

(defn normalize-lib-name [filename]
  (-> filename
      (str/replace #"^lib" "")
      (str/replace #".dylib" "")
      (str/replace #"\..*" "")))

(defn normalize-lib-path [filename]
  (str (subs filename 0 (str/index-of filename "."))
       ".dylib"))

(defn system-arch []
  "macosx-aarch64")

(defn create-cljonda-jar [prefix versions package]
  (let [package-name (get package "name")]
    (let [jar-file (io/file
                    build-dir
                    (str package-name "-" (get package "version") ".jar"))

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
                              "darwin-aarch64")

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
              (into ['[com.phronemophobic.cljonda.core :refer [extract-lib]]]
                    (map package-name->sym)
                    package-dependencies)
              
              package-name (get package "name")
              ns-code (list 'ns (package-name->sym package-name)
                            `(:require ~@requires))


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
              [`(def ~'package-files ~package-files)
               (list 'extract-lib package-name 'package-files)]]
          (let [ns-filename (str (str/replace package-name #"-" "_") ".clj")
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

          (let [lib (symbol "com.phronemophobic.cljonda" (str package-name "-" (system-arch)))
                class-dir (.getAbsolutePath package-build-dir)]
            (b/write-pom {:lib lib
                          :version (str (get package "version") "-SNAPSHOT")
                          :class-dir (.getAbsolutePath package-build-dir)
                          :basis {:libs (into '{com.phronemophobic/cljonda-core {:mvn/version "1.0-SNAPSHOT"}}
                                              (map (fn [package-name]
                                                     [(symbol "com.phronemophobic.cljonda" (str package-name "-" (system-arch)))
                                                      {:mvn/version (str (versions package-name)
                                                                         "-SNAPSHOT")}]))
                                              package-dependencies)}})
            (b/jar {:class-dir class-dir
                    :jar-file (.getAbsolutePath jar-file)})
            {:jar-file (.getAbsolutePath jar-file)
             :lib lib
             :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
        
        (finally
          #_(delete-tree package-build-dir)
          )))))

(defn deploy [{:keys [jar-file pom-file]}]
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

(defn export-packages [prefix]
  (let [packages (conda-installed-packages prefix)
        versions (into {}
                       (map (fn [package]
                              [(get package "name")
                               (get package "version")]))
                       packages)]
    (delete-tree build-dir true)
    (doseq [package packages]
      (println (get package "name"))
      (prn (create-cljonda-jar prefix versions package)))))



(defn deploy-prefix [prefix]
  (delete-tree build-dir true)
  (let [packages (conda-installed-packages prefix)
        versions (into {}
                       (map (fn [package]
                              [(get package "name")
                               (get package "version")]))
                       packages)
        deploys (into []
                      (map #(create-cljonda-jar prefix versions %))
                      packages)]
    (doseq [dp deploys
            ;;:when (= 'com.phronemophobic.cljonda/lerc-macosx-aarch64 (:lib dp))
            ]
      (prn "deploying" dp)
      (deploy dp))))

(comment
  (create-cljonda-jar prefix
                      (constantly "0.99")
                      (some #(when (= "graphviz"
                                      (get % "name")) %)
                            (conda-installed-packages prefix)))
  
  ,)

(defn -main [& args]
  (let []
    (deploy-prefix "/Users/adrian/miniconda3/envs/cljonda/"))
  #_(deploy-prefix "/Users/adrian/miniconda3/envs/cljonda-glfw/"))

(comment
  (export-prefix "/Users/adrian/miniconda3/envs/cljonda-glfw/")
  (deploy-prefix "/Users/adrian/miniconda3/envs/cljonda-glfw/")

  (export-packages
   "/Users/adrian/miniconda3/envs/cljonda-ffmpeg/"
   (conda-installed-packages "/Users/adrian/miniconda3/envs/cljonda-ffmpeg/"))

  (export-packages
   prefix
   (conda-installed-packages prefix))

  ,)
