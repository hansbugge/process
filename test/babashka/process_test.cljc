(ns babashka.process-test
  (:require [babashka.fs :as fs]
            [babashka.process :refer [tokenize process check sh $ pb start] :as p]
            [babashka.process.test-utils :as u]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing use-fixtures]]))

(defn print-env [f]
  (u/print-test-env)
  (println "- testing clojure version:" (clojure-version))
  (f))

(use-fixtures :once print-env)

(defn- resolve-exe
  "For the purposes of these tests, we sometimes need to expect how babashka process will resolve an exe for :cmd."
  [exe]
  (if (fs/windows?)
    (-> exe fs/which str)
    exe))

(deftest tokenize-test
  (is (= [] (tokenize "")))
  (is (= ["hello"] (tokenize "hello")))
  (is (= ["hello" "world"] (tokenize "  hello   world ")))
  (is (= ["foo   bar" "a" "b" "c" "the d"] (tokenize "\"foo   bar\"    a b c \"the d\"")))
  (is (= ["foo \"  bar" "a" "b" "c" "the d"] (tokenize "\"foo \\\"  bar\"    a b c \"the d\"")))
  (is (= ["echo" "foo bar"] (tokenize "echo 'foo bar'")))
  (is (= ["echo" "{\"AccessKeyId\":\"****\",\"SecretAccessKey\":\"***\",\"Version\":1}"]
         (tokenize "echo '{\"AccessKeyId\":\"****\",\"SecretAccessKey\":\"***\",\"Version\":1}'")))
  (is (= ["c:\\Users\\borkdude\\bin\\graal.bat" "1" "2" "3"]
         (tokenize "c:\\Users\\borkdude\\bin\\graal.bat 1 2 3")))
  (is (= ["\\foo"]
         (tokenize "\"\\foo\"")))
  (is (= ["\\foo"]
         (tokenize "\"\\foo\"")))
  (is (= ["\\foo"]
         (tokenize "\"\\foo\"")))
  (is (= ["xxx[dude]xxx"]
         (tokenize "xxx[\"dude\"]xxx")))
  (is (= ["some=something else"]
         (tokenize "some=\"something else\"")))
  (is (= ["bash" "-c" "echo 'two words' | wc -w"]
         (tokenize "bash -c \"echo 'two words' | wc -w\""))))

#?(:bb nil
   :clj
   (deftest parse-args-test
     (when-let [bb (u/find-bb)]
       (let [norm (p/parse-args [(p/process (format "%s %s :out hello" bb u/wd)) "cat"])]
         (is (instance? babashka.process.Process (:prev norm)))
         (is (= ["cat"] (:cmd norm))))
       (let [norm (p/parse-args [(p/process (format "%s %s :out hello" bb u/wd))
                                 ["cat"] {:out :string}])]
         (is (instance? babashka.process.Process (:prev norm)))
         (is (= ["cat"] (:cmd norm)))
         (is (= {:out :string} (:opts norm))))
       (testing "cmd + prev"
         (let [parsed (p/parse-args [{:cmd ["echo" "hello"]
                                      :prev @(process {:out :string} (format "%s %s :ls ." bb u/wd))}])]
           (is (= ["echo" "hello"] (:cmd parsed)) (:out (:prev parsed))))))
     (is (= ["foo" "bar" "baz"] (:cmd (p/parse-args ["foo bar" "baz"]))))
     (let [norm (p/parse-args [{:out :string} "foo bar" "baz"])]
       (is (= ["foo" "bar" "baz"] (:cmd norm)))
       (is (= {:out :string} (:opts norm))))
     (testing "existing file invocation"
       (let [args ["README.md" "a" "b" "c"]]
         (is (= args (:cmd (p/parse-args args))))))
     (testing "prev may be nil"
       (is (= ["echo" "hello"] (:cmd (p/parse-args [nil ["echo hello"]])))))))

(deftest process-wait-realize-test
  (testing "By default process returns string out and err, returning the exit
  code in a delay. Waiting for the process to end happens through realizing the
  delay. Waiting also happens implicitly by not specifying :stream, since
  realizing :out or :err needs the underlying process to finish."
    (when-let [bb (u/find-bb)]
      (let [res (process [bb u/wd ":out" "hello"])
            out (slurp (:out res))
            err (slurp (:err res))
            checked (check res) ;; check should return process with :exit code
            ;; populated
            exit (:exit checked)]
        (is (= (u/ols "hello\n") out))
        (is (string? err))
        (is (str/blank? err))
        (is (number? exit))
        (is (zero? exit))))))

(deftest process-wait-realize-with-stdin-test
  (testing "When specifying :out and :err both a non-strings, the process keeps
  running. :in is the stdin of the process to which we can write. Calling close
  on that stream closes stdin, so a program like cat will exit. We wait for the
  process to exit by realizing the exit delay."
    (when-let [bb (u/find-bb)]
      (let [res (process [(symbol bb) (symbol u/wd) ':upper] {:err :inherit})
            _ (is (true? (.isAlive (:proc res))))
            in (:in res)
            w (io/writer in)
            _ (binding [*out* w]
                (println "hello"))
            _ (.close in)
            exit (:exit @res)
            _ (is (zero? exit))
            _ (is (false? (.isAlive (:proc res))))
            out-stream (:out res)]
        (is (= (u/ols "HELLO\n") (slurp out-stream)))))))

(deftest process-copy-input-from-string-test
  (when-let [bb (u/find-bb)]
    (let [proc (process [(symbol bb) (symbol u/wd) ':upper] {:in "foo"})
          out (:out proc)
          ret (:exit @proc)]
      (is (= 0 ret))
      (is (= (u/ols "FOO\n") (slurp out))))))

(deftest process-redirect-err-out-test
  (when-let [bb (u/find-bb)]
    (let [test-cmd (format "%s -cp '' %s :out :to-stdout :err :to-stderr"
                           bb u/wd)]
      (testing "baseline"
        (let [res @(process {:out :string :err :string} test-cmd)]
          (is (= (u/ols ":to-stdout\n") (:out res)))
          (is (= (u/ols ":to-stderr\n") (:err res)))))
      (testing "redirect"
        (let [res @(process {:out :string :err :out} test-cmd)
              out-string (:out res)
              err-null-input-stream (:err res)]
          (is (= (u/ols ":to-stdout\n:to-stderr\n") out-string))
          (is (instance? java.io.InputStream err-null-input-stream))
          (is (= 0 (.available err-null-input-stream)))
          (is (= -1 (.read err-null-input-stream))))))))

(deftest process-copy-to-out-test
  (when-let [bb (u/find-bb)]
    (let [s (with-out-str
              @(process [(symbol bb) (symbol u/wd) ':upper] {:in "foo" :out *out*}))]
      (is (= (u/ols "FOO\n") s)))))

(deftest process-copy-stderr-to-out-test
  (when-let [bb (u/find-bb)]
    (let [s (with-out-str
              (-> (process [(symbol bb) (symbol u/wd) ':err 'foo] {:err *out*})
                  deref :exit))]
      (is (= (u/ols "foo\n") s)))))

(deftest process-chaining-test
  (when-let [bb (u/find-bb)]
    (is (= (u/ols "README.md\n")
           (-> (process [bb u/wd ":out" "foo" ":out" "README.md" ":out" "bar"])
               (process [bb u/wd ":grep" "README.md"]) :out slurp)))
    (is (= (u/ols "README.md\n")
           (-> (sh [bb u/wd ":out" "foo" ":out" "README.md" ":out" "bar"])
               (sh [bb u/wd ":grep" "README.md"]) :out)))))

(deftest process-dir-option-test
  ;; It is not practical to use bb for this test (bb will not be on the PATH when
  ;; testing under babashka)
  ;; It would be nice to use clojure, but on Windows the official install
  ;; is still currently a PowerShell Module, which cannot be spawned directly.
  ;; So we'll use java. This test assumes that java and javac are on the PATH."
  (let [test-dir "target/process-dir-option-test"
        java (fs/which "java")
        java-dir (-> java fs/parent fs/canonicalize str)
        java-src (-> (fs/file test-dir "UserDir.java") str)
        args ["-cp" (str (fs/absolutize test-dir)) "UserDir"]
        subdir (str (fs/file test-dir "foo/bar/baz"))
        subdir-absolute (-> subdir fs/canonicalize str)]
    (fs/create-dirs subdir)
    (spit java-src
          (str/join "\n" ["class UserDir {"
                          "  public static void main( String []args ) {"
                          "    System.out.println( System.getProperty (\"user.dir\"));"
                          "  }"
                          "}"]))
    (p/shell {:dir test-dir} "javac" "UserDir.java") ;; typically under 0.5s to compile
    (testing "program is absolute"
      (is (= (u/ols (str subdir-absolute "\n"))
             (-> (apply process {:dir subdir}
                        (str (fs/file java-dir "java")) args)
                 :out
                 slurp))))
    (when (fs/windows?)
      (testing "program with ext is absolute (windows)")
      (is (= (u/ols (str subdir-absolute "\n"))
             (-> (apply process {:dir subdir}
                        (str (fs/canonicalize java)) args)
                 :out
                 slurp))))
    (testing "program is on path"
      (is (= (u/ols (str subdir-absolute "\n"))
             (-> (apply process {:dir subdir}
                        "java" args)
                 :out
                 slurp))))
    (when (fs/windows?)
      (testing "program with ext is on path (windows)"
        (is (= (u/ols (str subdir-absolute "\n"))
               (-> (apply process {:dir subdir}
                          (fs/file-name java) args)
                   :out
                   slurp)))))
    (testing "program is relative"
      (is (= (u/ols (str java-dir "\n"))
             (-> (apply process {:dir java-dir}
                        (str "./java") args)
                 :out
                 slurp))))
    (when (fs/windows?)
      (testing "program with ext is relative (windows)"
        (is (= (u/ols (str java-dir "\n"))
               (-> (apply process {:dir java-dir}
                          (str "./" (fs/file-name java)) args)
                   :out
                   slurp)))))
    (fs/delete-tree test-dir)))

(deftest process-env-option-test
  (when-let [bb (u/find-bb)]
    (testing "request an empty env"
      ;; using -cp "" for bb here, otherwise it will expect JAVA_HOME env var to be set
      (let [vars (-> (process [bb "-cp" "" u/wd ":env"] {:env {}})
                     :out
                     slurp
                     edn/read-string)
            expected-vars (u/always-present-env-vars)]
        (is (= expected-vars (keys vars)))))
    (testing "add to existing env"
      (let [out (-> (sh (format "%s %s :env" bb u/wd) {:extra-env {:FOO "BAR"}})
                    :out)]
        (is (str/includes? out "PATH"))
        (is (str/includes? out "\"FOO\" \"BAR\""))))
    (testing "request a specific env"
      ;; using -cp "" for bb here, otherwise it will expect JAVA_HOME env var to be set
      (let [vars (-> (process [bb "-cp" "" u/wd ":env"]
                              {:env {"SOME_VAR" "SOME_VAL"
                                     :keyword_var "KWVARVAL"
                                     "keyword_val" :keyword-val}})
                     :out
                     slurp
                     edn/read-string)
            added-vars (apply dissoc vars (u/always-present-env-vars))]
        (is (= {"SOME_VAR" "SOME_VAL"
                "keyword_val" ":keyword-val"
                "keyword_var" "KWVARVAL"}
               added-vars))))))

(deftest process-check-throws-on-non-zero-exit-test
  (when-let [bb (u/find-bb)]
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"error123"
          (-> (process (format "%s %s :err error123 :exit 1" bb u/wd))
              (check)))
        "with :err string")
    (is (thrown?
          clojure.lang.ExceptionInfo #"failed"
          (-> (process (format "%s %s :exit 1" bb u/wd))
              (check)))
        "With no :err string")
    (is (thrown?
          clojure.lang.ExceptionInfo #"failed"
          (-> (process {:err *err*} (format "%s %s :exit 1" bb u/wd))
              (check)))
        "With :err set to *err*")
    (testing "and the exception"
      (let [command [bb u/wd ":exit" "1"]]
        (try
          (-> (process command)
              (check))
          (catch clojure.lang.ExceptionInfo e
            (testing "contains the process arguments"
              (is (= (assoc command 0 (-> command first resolve-exe))
                     (:cmd (ex-data e)))))
            (testing "and contains a babashka process type"
              (is (= :babashka.process/error (:type (ex-data e)))))))))))

#_{:clj-kondo/ignore [:unused-binding]}
(deftest process-dollar-macro-test
  (when-let [bb (u/find-bb)]
    (let [config {:a 1}]
      (is (= (u/ols "{:a 1}\n") (-> ($ ~(symbol bb) ~(symbol u/wd) :out ~config) :out slurp)))
      (let [sw (java.io.StringWriter.)]
        (is (= (u/ols "{:a 1}\n") (do (-> ^{:out sw}
                                        ($ ~(symbol bb) ~(symbol u/wd) :out ~config)
                                        deref)
                                    (str sw)))))
      (let [sw (java.io.StringWriter.)]
        (is (= (u/ols "{:a 1}\n") (do (-> ($ ~{:out sw} ~(symbol bb) ~(symbol u/wd) :out ~config)
                                        deref)
                                    (str sw)))))
      (let [sw (java.io.StringWriter.)]
        (is (= (u/ols "{:a 1}\n") (do (-> ($ {:out sw} ~(symbol bb) ~(symbol u/wd) :out ~config)
                                        deref)
                                    (str sw))))))))

(deftest process-same-as-pb-start-test
  (when-let [bb (u/find-bb)]
    (let [cmd [bb u/wd ":ls" "."]
          out (-> (process cmd) :out slurp)]
      (is (and (string? out) (not (str/blank? out))))
      (is (str/includes? out "README.md"))
      (is (= out (-> (pb cmd) (start) :out slurp))))))

(deftest process-out-to-string-test
  (when-let [bb (u/find-bb)]
    (is (= (u/ols "hello\n") (-> (process [bb u/wd ":out" "hello"] {:out :string})
                                 check
                                 :out)))))

(deftest process-tokenization-test
  (when-let [bb (u/find-bb)]
    (is (= (u/ols "hello\n") (-> (process (format "%s %s :out hello" bb u/wd) {:out :string})
                               check
                               :out)))
    ;; This bit of awkwardness might be avoidable.
    ;; But if we needing to test ($ "literal string") maybe not.
    (is (= (u/ols "hello\n") (-> (case bb
                                 "bb"
                                 (case u/wd
                                   "script/wd.clj" ^{:out :string} ($ "bb script/wd.clj :out hello")
                                   "process/script/wd.clj" ^{:out :string} ($ "bb process/script/wd.clj :out hello"))
                                 "./bb"
                                 (case u/wd
                                   "script/wd.clj" ^{:out :string} ($ "./bb script/wd.clj :out hello")
                                   "process/script/wd.clj" ^{:out :string} ($ "./bb process/script/wd.clj :out hello")))
                               check
                               :out)))
    (is (= (u/ols "hello\n") (-> (sh (format "%s %s :out hello" bb u/wd))
                               :out)))))

(deftest process-space-in-cmd-test
  (when-let [bb (u/find-bb)]
    (let [proc @(p/process [(str bb " ") u/wd ":out" "hello"] {:out :string})]
      (is (= (u/ols "hello\n") (:out proc)))
      (is (zero? (:exit proc))))))

#?(:bb nil ;; skip longer running test when running form babashka proper
   :clj
   (deftest process-deref-timeout-test
     (when-let [bb (u/find-bb)]
       (is (= ::timeout (deref (process [bb u/wd ":sleep" "500"]) 250 ::timeout)))
       (is (= 0 (:exit (deref (process [bb u/wd]) 250 nil)))))))

(deftest shell-test
  (when-let [bb (u/find-bb)]
    (is (str/includes? (:out (p/shell {:out :string} (format "%s %s :out hello" bb u/wd))) "hello"))
    (is (str/includes? (-> (p/shell {:out :string} (format "%s %s :out hello" bb u/wd))
                           (p/shell {:out :string } (format "%s %s :upper" bb u/wd))
                           :out)
                       "HELLO"))
    (is (= 1 (do (p/shell {:continue true} (format "%s %s :exit 1" bb u/wd)) 1)))))


#_{:clj-kondo/ignore [:unused-binding]}
(deftest dollar-pipe-test
  (when-let [bb (u/find-bb)]
    (is (= (u/ols "HELLO\n")
           (-> ($ ~(symbol bb) ~(symbol u/wd) :out hello)
               ($ {:out :string} ~(symbol bb) ~(symbol u/wd) :upper) deref :out)))
    (is (= (u/ols "HELLO\n")
           (-> ($ ~(symbol bb) ~(symbol u/wd) :out hello)
               ^{:out :string} ($ ~(symbol bb) (symbol u/wd) :upper) deref :out)))
    (is (= (u/ols "hello\n")
           (-> ($ ~(symbol bb) ~(symbol u/wd) :out goodbye :out hello)
               ($ ~(symbol bb) ~(symbol u/wd) :grep hello) deref :out slurp)))))

(deftest redirect-file-test
  (when-let [bb (u/find-bb)]
    (fs/with-temp-dir [tmp {}]
      (let [out (fs/file tmp "out.txt")]
        @(p/process (format "%s %s :out hello" bb u/wd)
                    {:out :write :out-file out})
        (is (= (u/ols "hello\n") (slurp out)))
        @(p/process (format "%s %s :out goodbye" bb u/wd)
                    {:out :append :out-file out})
        (is (= (u/ols "hello\ngoodbye\n") (slurp out)))))
    (fs/with-temp-dir [tmp {}]
      (let [out (fs/file tmp "err.txt")]
        @(p/process (format "%s %s :err 'err,hello'" bb u/wd)
                    {:err :write :err-file out})
        (is (= (u/ols "err,hello\n") (slurp out)))
        @(p/process (format "%s %s :err 'grrr-oodbye'" bb u/wd)
                    {:err :append :err-file out})
        (is (= (u/ols "err,hello\ngrrr-oodbye\n") (slurp out)))))))

(deftest pprint-test
  ;; #?(:bb nil ;; in bb we already required the babashka.process.pprint namespace
  ;;    :clj
  ;;    (testing "calling pprint on a process without requiring pprint namespace causes exception (ambiguous on pprint/simple-dispatch multimethod)"
  ;;      (is (thrown-with-msg? IllegalArgumentException #"Multiple methods in multimethod 'simple-dispatch' match dispatch value"
  ;;                            (-> (process "cat missing-file.txt") pprint)))))
  (when-let [bb (u/find-bb)]
    (testing "after requiring pprint namespace, process gets pprinted as a map"
      (do
        (require '[babashka.process] :reload '[babashka.process.pprint] :reload)
        (is (str/includes? (with-out-str (-> (process (format "%s %s :out hello" bb u/wd)) pprint)) ":proc"))))))

(deftest pre-start-fn-test
  (when-let [bb (u/find-bb)]
    (testing "a print fn option gets executed just before process is started"
      (let [p {:pre-start-fn #(apply println "Running" (:cmd %))}
            resolved-bb (resolve-exe bb)]
        (is (= (u/ols (format "Running %s %s :out hello1\n" resolved-bb u/wd))
               (with-out-str (process (format "%s %s :out hello1" bb u/wd) p))))
        (is (= (u/ols (format "Running %s %s :out hello2\n" resolved-bb u/wd))
               (with-out-str (-> (pb [bb u/wd ":out" "hello2"] p) start))))
        (is (= (u/ols (format "Running %s %s :exit 32\n" resolved-bb u/wd))
               (with-out-str (sh (format "%s %s :exit 32" bb u/wd) p))))))))

(defmacro ^:private jdk9+ []
  (if (identical? ::pre-jdk9
                  (try (import 'java.lang.ProcessHandle)
                       (catch Exception _ ::pre-jdk9)))
    '(do
       (require '[babashka.process :refer [pipeline]])
       (deftest pipeline-prejdk9-test
         (when-let [bb (u/find-bb)]
           (testing "pipeline returns processes nested with ->"
             (let [resolved-bb (resolve-exe bb)]
               (is (= [[resolved-bb u/wd ":out" "foo"]
                       [resolved-bb u/wd ":upper"]]
                      (map :cmd (pipeline (-> (process [bb u/wd ":out" "foo"])
                                              (process [bb u/wd ":upper"])))))))))))
    '(do
       (require '[babashka.process :refer [pipeline pb]])
       (deftest inherit-test
         (when-let [bb (u/find-bb)]
           (let [proc (process (format "%s %s :out ''" bb u/wd) {:shutdown p/destroy-tree
                                                               :inherit true})
                 null-input-stream-class (class (:out proc))
                 null-output-stream-class (class (:in proc))]
             (is (= null-input-stream-class (class (:err proc))))
             (let [x (process [bb u/wd ":upper"] {:shutdown p/destroy-tree
                                                :inherit true
                                                :in "foo"})]
               (is (not= null-output-stream-class (class (:in x))))
               (is (= null-input-stream-class (class (:out x))))
               (is (= null-input-stream-class (class (:err x)))))
             (let [x (process [bb u/wd ":upper"] {:shutdown p/destroy-tree
                                                :inherit true
                                                :out :string})]
               (is (= null-output-stream-class (class (:in x))))
               (is (not= null-input-stream-class (class (:out x))))
               (is (= null-input-stream-class (class (:err x)))))
             (let [x (process [bb u/wd ":upper"] {:shutdown p/destroy-tree
                                                :inherit true
                                                :err :string})]
               (is (= null-output-stream-class (class (:in x))))
               (is (= null-input-stream-class (class (:out x))))
               (is (not= null-input-stream-class (class (:err x))))))))
       (deftest pipeline-test
         (when-let [bb (u/find-bb)]
           (testing "pipeline returns processes nested with ->"
             (let [resolved-bb (resolve-exe bb)]
               (is (= [[resolved-bb u/wd ":out" "foo"]
                       [resolved-bb u/wd ":upper"]]
                      (map :cmd (pipeline (-> (process [bb u/wd ":out" "foo"])
                                              (process [bb u/wd ":upper"]))))))))
           (testing "pipeline returns processes created with pb"
             (let [resolved-bb (resolve-exe bb)]
               (is (= [[resolved-bb u/wd ":out" "foo"]
                       [resolved-bb u/wd ":upper"]]
                      (map :cmd (pipeline (pb [bb u/wd ":out" "foo"])
                                          (pb [bb u/wd ":upper"])))))))
           (testing "pbs can be chained with ->"
             (let [chain (-> (pb [bb u/wd ":out" "hello"])
                             (pb [bb u/wd ":upper"] {:out :string}) start deref)
                   resolved-bb (resolve-exe bb)]
               (is (= (u/ols "HELLO\n") (slurp (:out chain))))
               (is (= [[resolved-bb u/wd ":out" "hello"]
                       [resolved-bb u/wd ":upper"]] (map :cmd (pipeline chain))))))))
       (deftest exit-fn-test
         (when-let [bb (u/find-bb)]
           (let [exit-code (promise)]
             (process [bb u/wd ":exit" "42"]
                      {:exit-fn (fn [proc] (deliver exit-code (:exit proc)))})
             (is (= 42 @exit-code))))))))

(jdk9+)

(deftest alive-lives-test
  (when-let [bb (u/find-bb)]
    (let [{:keys [in] :as res} (process [(symbol bb) (symbol u/wd) ':upper])]
      (is (true? (p/alive? res)))
      (.close in)
      @res
      (is (false? (p/alive? res))))))
