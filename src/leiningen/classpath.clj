(ns leiningen.classpath
  "Print the classpath of the current project."
  (:use [leiningen.core :only [read-project home-dir]]
        [leiningen.deps :only [do-deps]]
        [clojure.java.io :only [file]]
        [clojure.string :only [join]])
  (:require [lancet.core :as lancet]
            [clojure.string :as string])
  (:import (org.apache.tools.ant.types Path)))

(defn ^{:internal true} fileset-paths [fileset]
  (-> fileset
      (.getDirectoryScanner lancet/ant-project)
      (.getIncludedFiles)))

(defn- find-lib-jars [project]
  (.listFiles (file (:library-path project))))

(defn- find-local-repo-jars [project]
  ;; TODO: Shut up, ant. You are useless. Nobody cares about what you say.
  ;; Removing ant-project loggers and redirecting their output streams
  ;; does nothing. How to suppress output?
  (for [path (fileset-paths (do-deps project :dependencies))]
    (file (System/getProperty "user.home") ".m2" "repository" path)))

(defn ^:internal find-jars
  "Returns a seq of Files for all the jars in the project's library directory."
  [project]
  (filter #(.endsWith (.getName %) ".jar")
          (concat (if (:local-repo-classpath project)
                    (find-local-repo-jars project)
                    (find-lib-jars project))
                  ;; This must be hard-coded because it's used in
                  ;; bin/lein and thus can't be changed in project.clj.
                  (.listFiles (file (:root project) "lib/dev")))))

(defn- read-dependency-project [dep]
  (let [project (.getAbsolutePath (file dep "project.clj"))]
    (try (read-project project)
         (catch Exception e
           (throw (Exception. (format "Problem loading %s" project) e))))))

(defn- ensure-absolute [path root]
  (.getCanonicalPath
   (let [f (file path)]
     (if (.isAbsolute f)
       f
       (file root f)))))

(defn checkout-deps-paths [project]
  (apply concat (for [dep (.listFiles (file (:root project) "checkouts"))
                      ;; Note that this resets the leiningen.core/project var!
                      :let [proj (read-dependency-project dep)]
                      :when proj]
                  (for [d [:source-path :compile-path :resources-path]]
                    (ensure-absolute (proj d) dep)))))

(defn user-plugins []
  (for [jar (.listFiles (file (home-dir) "plugins"))
        :when (re-find #"\.jar$" (.getName jar))]
    (.getAbsolutePath jar)))

;; TODO: move to lancet?
(defn ^:internal make-path
  "Constructs an ant Path object from Files and strings."
  [& paths]
  (let [ant-path (Path. nil)]
    (doseq [path paths]
      (.addExisting ant-path (Path. nil (str path))))
    ant-path))

(defn get-classpath
  "Answer a list of classpath entries for PROJECT."
  [project]
  (concat [(:source-path project)
           (:test-path project)
           (:compile-path project)
           (:dev-resources-path project)
           (:resources-path project)]
          (:extra-classpath-dirs project)
          (checkout-deps-paths project)
          (find-jars project)
          (user-plugins)))

(defn get-classpath-string [project]
  (join java.io.File/pathSeparatorChar (get-classpath project)))

(defn classpath
  "Print the classpath of the current project.

Suitable for java's -classpath option."
  [project]
  (println (get-classpath-string project)))
