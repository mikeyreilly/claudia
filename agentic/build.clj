(ns build
  (:require [clojure.java.io :as io]))

(defn compile-java [_]
  (let [compiler (javax.tools.ToolProvider/getSystemJavaCompiler)
        sources (filter #(.endsWith (.getName %) ".java") (file-seq (io/file "src/main/java")))]
    (when-not compiler (throw (ex-info "Building agentic requires a JDK." {})))
    (.mkdirs (io/file "target/classes"))
    (when-not (zero? (.run compiler nil nil nil
                          (into-array String (concat ["--release" "17" "-encoding" "UTF-8" "-proc:none"
                                                      "-classpath" "target/classes" "-d" "target/classes"]
                                                     (map str sources)))))
      (throw (ex-info "Compiling agentic failed." {})))))
