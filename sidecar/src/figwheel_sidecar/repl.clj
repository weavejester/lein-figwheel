(ns figwheel-sidecar.repl
  (:require
   [cljs.repl]
   [cljs.util]
   [cljs.analyzer :as ana]
   [cljs.compiler]
   [cljs.stacktrace]
   [cljs.env :as env]
   [clojure.stacktrace :as trace]
   [clojure.pprint :as p]   
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.core.async :refer [chan <!! <! put! alts!! timeout close! go go-loop]]
   [cemerick.pomegranate :refer [add-dependencies]]
   [clojure.tools.nrepl.server :as nrepl-serv]
   [clojure.tools.nrepl.middleware.interruptible-eval :as nrepl-eval]
   [figwheel-sidecar.core :as fig]
   [figwheel-sidecar.config :as config]
   [figwheel-sidecar.auto-builder :as autobuild]
   [clojurescript-build.core :as cbuild]   
   [clojurescript-build.auto :as auto]))

(def ^:dynamic *autobuild-env* false)

;; slow but works
;; TODO simplify in the future
(defn resolve-repl-println []
  (let [opts (resolve 'cljs.repl/*repl-opts*)]
    (or (and opts (:print @opts))
        println)))

(defn repl-println [& args]
  (apply (resolve-repl-println) args))

(defn eval-js [{:keys [browser-callbacks] :as figwheel-server} js]
  (let [out (chan)
        callback (fn [result]
                   (put! out result)
                   (go
                     (<! (timeout 2000))
                     (close! out)))]
    (fig/send-message! figwheel-server :repl-eval {:code js :callback callback})
    (let [[v ch] (alts!! [out (timeout 8000)])]
      (if (= ch out)
        v
        {:status :exception
         :value "Eval timed out!"
         :stacktrace "No stacktrace available."}))))

(defn connection-available?
  [connection-count build-id]
  (not
   (zero?
    (+ (or (get @connection-count build-id) 0)
       (or (get @connection-count nil) 0)))))

;; limit how long we wait?
(defn wait-for-connection [{:keys [connection-count build-id]}]
  (when-not (connection-available? connection-count build-id)
    (loop []
      (when-not (connection-available? connection-count build-id)
        (Thread/sleep 500)
        (recur)))))

(defn add-repl-print-callback! [{:keys [browser-callbacks]}]
  (let [pr-fn (resolve-repl-println)]
    (swap! browser-callbacks assoc "figwheel-repl-print"
           (fn [args] (apply pr-fn args)))))

(defn valid-stack-line? [{:keys [function file url line column]}]
  (and (not (nil? function))
       (not= "NO_SOURCE_FILE" file)))

(defn extract-host-and-port [base-path]
  (let [[host port] (-> base-path
                      string/trim
                      (string/replace-first #".*:\/\/" "")
                      (string/split #"\/")
                      first
                      (string/split #":"))]
    (if host
      (if-not port
        {:host host}
        {:host host :port (Integer/parseInt port)})
      {})))

(defrecord FigwheelEnv [figwheel-server]
  cljs.repl/IJavaScriptEnv
  (-setup [this opts]
    (add-repl-print-callback! figwheel-server)
    (wait-for-connection figwheel-server)
    (Thread/sleep 500)) ;; just to help with setup latencies
  (-evaluate [_ _ _ js]
    (wait-for-connection figwheel-server)
    (eval-js figwheel-server js))
      ;; this is not used for figwheel
  (-load [this ns url]
    (wait-for-connection figwheel-server)
    (eval-js figwheel-server (slurp url)))
  (-tear-down [_] true)
  cljs.repl/IParseStacktrace
  (-parse-stacktrace [repl-env stacktrace error build-options]
    (cljs.stacktrace/parse-stacktrace (merge repl-env
                                             (extract-host-and-port (:base-path error)))
                                      (:stacktrace error)
                                      {:ua-product (:ua-product error)}
                                      build-options))
  cljs.repl/IPrintStacktrace
  (-print-stacktrace [repl-env stacktrace error build-options]
    (doseq [{:keys [function file url line column] :as line-tr}
            (filter valid-stack-line? (cljs.repl/mapped-stacktrace stacktrace build-options))]
      (repl-println "\t" (str function " (" (str (or url file)) ":" line ":" column ")")))))

(defn repl-env
  ([figwheel-server {:keys [id build-options] :as build}]
   (assoc (FigwheelEnv. (merge figwheel-server
                               (if id {:build-id id} {})
                               (select-keys build-options [:output-dir :output-to])))
          :cljs.env/compiler (:compiler-env build)))
  ([figwheel-server]
   (FigwheelEnv. figwheel-server)))

;; add some repl functions for reloading local clj code

(defmulti start-cljs-repl (fn [protocol figwheel-env opts]
                            protocol))

(defmethod start-cljs-repl :nrepl
  [_ figwheel-env opts]
  (try
    (require 'cemerick.piggieback)
    (let [cljs-repl (resolve 'cemerick.piggieback/cljs-repl)
          special-fns (or (:special-fns opts) cljs.repl/default-special-fns)
          output-dir (or (:output-dir opts) "out")]
      (try
        ;; Piggieback version 0.2+
        (cljs-repl figwheel-env :special-fns special-fns :output-dir output-dir)
        (catch Exception e
          ;; Piggieback version 0.1.5
          (cljs-repl :repl-env figwheel-env :special-fns special-fns :output-dir output-dir))))
    (catch Exception e
      (println "INFO: nREPL connection found but unable to load piggieback. Starting default REPL")
      (start-cljs-repl :default figwheel-env opts))))

(defmethod start-cljs-repl :default
  [_ figwheel-env opts]
  (cljs.repl/repl* figwheel-env opts))

(defn require? [symbol]
  (try (require symbol) true (catch Exception e false)))

(defn repl
  ([build figwheel-server]
   (repl build figwheel-server {}))
  ([build figwheel-server opts]
   (let [opts (merge (assoc (or (:compiler build) (:build-options build))
                            :warn-on-undeclared true)
                     opts)
         figwheel-repl-env (repl-env figwheel-server build)
         repl-opts (assoc opts :compiler-env (:compiler-env build))
         protocol (if (thread-bound? #'nrepl-eval/*msg*)
                    :nrepl
                    :default)]
     (start-cljs-repl protocol figwheel-repl-env repl-opts))))

(defn namify [arg]
  (if (seq? arg)
    (when (= 'quote (first arg))
      (str (second arg)))
    (name arg)))

(defn make-special-fn [f]
  (fn self
    ([a b c] (self a b c nil))
    ([_ _ [_ & args] _]
     ;; are we only accepting string ids?
     (f (keep namify args)))))


(defn add-dep* [dep]
  (binding [*err* *out*]
    (add-dependencies :coordinates [dep]
                      :repositories (merge cemerick.pomegranate.aether/maven-central
                                           {"clojars" "http://clojars.org/repo"}))))

(defn add-dep
  ([a b c] (add-dep a b c nil))
  ([_ _ [_ dep] _] (add-dep* dep)))

(defn doc-help
  ([repl-env env form]
   (doc-help repl-env env form nil))
  ([repl-env env [_ sym :as form] opts]
   (if (not (symbol? sym))
     (repl-println "Must provide bare var to get documentation i.e. (doc clojure.string/join)")
     (cljs.repl/evaluate-form repl-env
                              (assoc env :ns (ana/get-namespace ana/*cljs-ns*))
                              "<cljs repl>"
                              (with-meta
                                `(cljs.repl/doc ~sym)
                                {:merge true :line 1 :column 1})
                              identity opts))))

(defn validate-build-ids [ids all-builds]
  (let [bs (set (keep :id all-builds))]
    (vec (keep #(if (bs %) % (repl-println "No such build id:" %)) ids))))

(defn get-ids [ids focus-ids all-builds]
  (or (and (empty? ids) focus-ids)
      (validate-build-ids ids all-builds)))

(defn display-focus-ids [ids]
  (when (not-empty ids)
    (repl-println "Focusing on build ids:" (string/join ", " ids))))

(defn builder-running? [state-atom]
  (not (nil? (:autobuilder @state-atom))))

(defn filter-builds* [ids focus-ids all-builds]
  (let [bs (set (get-ids ids focus-ids all-builds))]
    (filter #(bs (:id %)) all-builds)))

(defn focused-builds [ids]
  (filter-builds* (map name ids)
                  (:focus-ids @(:state-atom *autobuild-env*))
                  (:all-builds *autobuild-env*)))

;; API

(defn analyze-build [build]
  ;; put some guards here for expectations
  (when (and (:compiler-env build)
             (:source-paths build))
    (env/with-compiler-env (:compiler-env build)
      (let [files
            (filter
             (fn [f] (not (= (.getName f) "deps.cljs")))
             (map :source-file
                  (cbuild/files-like [".cljs" ".cljc"]
                                     (:source-paths build))))
            ;; TODO refactor this or clause repeated to many times
            opts (or (:build-options build) (:compiler build))]
        (doseq [f files] (ana/analyze-file f opts))
        nil))))

(defn analyze-builds [ids]
  (doseq [build (focused-builds ids)]
    (analyze-build build)))

(defn analyze-core-cljs [ids]
  (doseq [build (focused-builds ids)]
    (env/with-compiler-env (:compiler-env build)
        (let [opts (or (:build-options build) (:compiler build))]
          (cljs.compiler/with-core-cljs opts (fn []))))))

(defn build-once-builder [figwheel-server]
  (-> cbuild/build-source-paths*
    (autobuild/default-build-options {:recompile-dependents false})
    (autobuild/insert-figwheel-connect-script figwheel-server)
    (auto/warning (auto/warning-message-handler
              #(when-let [msg (:message %)] 
                 (println msg))))
    auto/time-build
    (auto/after auto/compile-success)
    (auto/error auto/compile-fail)
    (auto/before auto/compile-start)))

(defn build-once [ids]
  (let [build-it-once (build-once-builder (get *autobuild-env* :figwheel-server))
        builds (->> (focused-builds ids)
                 (map #(assoc % :reload-clj-files false)))]
    (display-focus-ids (map :id builds))
    (doseq [build builds]
      (build-it-once build))))

(defn clean-builds [ids]
  (let [builds (focused-builds ids)]
    (display-focus-ids (map :id builds))
    (mapv cbuild/clean-build (map :build-options builds))
    (repl-println "Deleting ClojureScript compilation target files.")))

(defn run-autobuilder-helper [build-ids]
  (let [{:keys [all-builds figwheel-server state-atom logfile-path output-writer error-writer]} *autobuild-env*]
    (if-let [errors (not-empty (autobuild/check-autobuild-config all-builds build-ids figwheel-server))]
      (do
        (display-focus-ids build-ids)
        (mapv repl-println errors))
      (when-not (builder-running? state-atom)
        (analyze-core-cljs build-ids)
        
        (build-once build-ids)
        ;; this is kinda crap but we need it for now
        ;; build-once can short circuit and not analyze all the files
        ;; but we need to call it so it does some stateful voodoo
        ;; then we force analyzation on all files
        (analyze-builds build-ids)
        
        ;; kill some undeclared warnings, hopefully?
        (Thread/sleep 300)
        (when-let [abuild
                   (binding [*out* output-writer
                             *err* error-writer]
                     (autobuild/autobuild-ids
                      { :all-builds all-builds
                        :build-ids build-ids
                        :figwheel-server figwheel-server }))]
          (if logfile-path
            (repl-println "Started Figwheel autobuilder see:" logfile-path)
            (repl-println "Started Figwheel autobuilder"))
          (reset! state-atom { :autobuilder abuild
                               :focus-ids build-ids}))))))

(defn get-project-config []
  (when (.exists (io/file "project.clj"))
    (try
      (into {} (map vec (partition 2 (drop 3 (read-string (slurp "project.clj"))))))
      (catch Exception e
        {}))))

(defn get-project-cljs-builds []
  (let [p (get-project-config)
        builds (or
                (get-in p [:figwheel :builds])
                (get-in p [:cljsbuild :builds]))]
    (when (> (count builds) 0)
      (config/prep-builds builds))))

(defn build-with-id [builds id]
  (first (filter #(= (:id %) id) builds)))

(declare initial-build-ids)

;;; ewww this is nasty, never planned for dynamic reloading of config
; begs for higher level of abstraction
(defn reload-builds! []
  (when-let [builds (get-project-cljs-builds)]
    (let [current-builds (:all-builds *autobuild-env*)
          builds' (mapv (fn [b] (if-let [cb (build-with-id current-builds (:id b))]
                                 (merge b (select-keys cb [:dependency-mtimes :compiler-env]))
                                 (auto/prep-build b)))
                        builds)]
      (println "Reloading Build Configuration")
      (let [build-ids (initial-build-ids builds' [])]
        (set! *autobuild-env* (assoc *autobuild-env*
                                     :build-ids  build-ids
                                     :all-builds builds'))
        (swap! (:state-atom *autobuild-env*) assoc :focus-ids build-ids)))))

(defn stop-autobuild
  ([] (stop-autobuild nil))
  ([_]
   (let [{:keys [state-atom]} *autobuild-env*]
     (if (builder-running? state-atom)
       (do
         (auto/stop-autobuild! (:autobuilder @state-atom))
         (swap! state-atom assoc :autobuilder nil)
         (repl-println "Stopped Figwheel autobuild"))
       (repl-println "Autobuild not running.")))))

(defn start-autobuild [ids]
  (let [{:keys [state-atom all-builds]} *autobuild-env*
        ids (map name ids)]
    (if-not (builder-running? state-atom)
      (when-let [build-ids' (not-empty (get-ids ids
                                                (:focus-ids @state-atom)
                                                all-builds))]
        (run-autobuilder-helper build-ids')
        nil)
      (repl-println "Autobuilder already running."))))

(defn switch-to-build [ids]
  (let [ids (map name ids)]
    (when-not (empty? ids)
      (stop-autobuild [])
      (start-autobuild ids))))

(defn reset-autobuild
  ([] (reset-autobuild nil))
  ([_]
   (stop-autobuild [])
   (clean-builds [])
   (let [{:keys [state-atom]} *autobuild-env*]
     (start-autobuild (:focus-ids @state-atom)))))

(defn reload-config
  ([] (reload-config nil))
  ([_]
   (stop-autobuild [])
   (clean-builds [])
   (reload-builds!)     
   (let [{:keys [state-atom]} *autobuild-env*]
     (start-autobuild (:focus-ids @state-atom)))))

(defn status
  ([] (status nil))
  ([_]
   (let [connection-count (get-in *autobuild-env* [:figwheel-server :connection-count])]
     (repl-println "Figwheel System Status")
     (repl-println "----------------------------------------------------")
     (repl-println "Autobuilder running? :" (builder-running? (:state-atom *autobuild-env*)))
     (display-focus-ids (:focus-ids @(:state-atom *autobuild-env*)))
     (repl-println "Client Connections")
     (when connection-count
       (doseq [[id v] @connection-count]
         (repl-println "\t" (str (if (nil? id) "any-build" id) ":")
                  v (str "connection" (if (= 1 v) "" "s")))))
     (repl-println "----------------------------------------------------"))))

;; end API methods

(def repl-control-fns
  { 'stop-autobuild  stop-autobuild
    'start-autobuild start-autobuild
    'switch-to-build switch-to-build
    'reset-autobuild reset-autobuild
    'reload-config   reload-config
    'build-once      build-once
    'fig-status      status
    'clean-builds    clean-builds})

(def figwheel-special-fns 
  (let [special-fns' (into {} (map (fn [[k v]] [k (make-special-fn v)]) repl-control-fns))]
    (merge cljs.repl/default-special-fns special-fns' {'add-dep add-dep
                                                       'doc doc-help})))

(defn repl-function-docs  []
  "Figwheel Controls:
          (stop-autobuild)                ;; stops Figwheel autobuilder
          (start-autobuild [id ...])      ;; starts autobuilder focused on optional ids
          (switch-to-build id ...)        ;; switches autobuilder to different build
          (reset-autobuild)               ;; stops, cleans, and starts autobuilder
          (reload-config)                 ;; reloads build config and resets autobuild
          (build-once [id ...])           ;; builds source one time
          (clean-builds [id ..])          ;; deletes compiled cljs target files
          (fig-status)                    ;; displays current state of system
          (add-dep [org.om/om \"0.8.1\"]) ;; add a dependency. very experimental
  Switch REPL build focus:
          :cljs/quit                      ;; allows you to switch REPL to another build
    Docs: (doc function-name-here)
    Exit: Control+C or :cljs/quit
 Results: Stored in vars *1, *2, *3, *e holds last exception object")

(defn get-build-choice [choices]
  (let [choices (set (map name choices))]
    (loop []
      (print (str "Choose focus build for CLJS REPL (" (clojure.string/join ", " choices) ") or quit > "))
      (flush)
      (let [res (read-line)]
        (cond
          (nil? res) false
          (choices res) res          
          (= res "quit") false
          (= res "exit") false
          :else
          (do
            (println (str "Error: " res " is not a valid choice"))
            (recur)))))))

(defn initial-repl-build [all-builds build-ids]
  (first (config/narrow-builds* all-builds build-ids)))

(defn initial-build-ids [all-builds build-ids]
  (let [repl-build (initial-repl-build all-builds build-ids)]
    (or (not-empty build-ids) [(:id repl-build)])))

(defn start-repl [build]
  (let [{:keys [figwheel-server build-ids state-atom]} *autobuild-env*]
    (when-not (builder-running? state-atom)
      (start-autobuild build-ids))
    (newline)
    (print "Launching ClojureScript REPL")
    (when-let [id (:id build)] (println " for build:" id))
    (println (repl-function-docs))
    (println "Prompt will show when figwheel connects to your application")
    (repl build figwheel-server {:special-fns figwheel-special-fns})))

(defn cljs-repl
  ([] (cljs-repl nil))
  ([id]
   (let [{:keys [state-atom figwheel-server all-builds build-ids]} *autobuild-env*
         opt-none-builds (set (keep :id (filter config/optimizations-none? all-builds)))
         build-id (first (not-empty (get-ids (if id [(name id)] [])
                                             (:focus-ids @state-atom)
                                             all-builds)))
         build-id (or build-id (first build-ids))
         build (first (filter #(and
                                (opt-none-builds (:id %))
                                (= build-id (:id %)))
                             all-builds))]
     (if build
       (start-repl build)
       (if id
         (println "No such build found:" (name id))
         (println "No build found to start CLJS REPL for."))))))

;;; This will not work in an nrepl env!!!
(defn repl-switching-loop
  ([] (repl-switching-loop nil))
  ([start-build]
   (loop [build start-build]
     (cljs-repl (:id build))
     (let [{:keys [all-builds]} *autobuild-env*]
       (let [chosen-build-id (get-build-choice
                              (keep :id (filter config/optimizations-none? all-builds)))]
         (if (false? chosen-build-id)
           false ;; quit
           (let [chosen-build (first (filter #(= (name (:id %)) chosen-build-id) all-builds))]
             (recur chosen-build))))))))

(defn start-nrepl-server [figwheel-options autobuild-options]
  (when (:nrepl-port figwheel-options)
    (let [middleware (or
                      (:nrepl-middleware figwheel-options)
                      ["cemerick.piggieback/wrap-cljs-repl"])
          resolve-mw (fn [name]
                       (let [s (symbol name)
                             ns (symbol (namespace s))]
                         (if (and
                              (require? ns)
                              (resolve s))
                           (let [var (resolve s)
                                 val (deref var)]
                             (if (vector? val)
                               (map resolve val)
                               (list var)))
                           (println (format "WARNING: unable to load \"%s\" middleware" name)))))
          middleware (mapcat resolve-mw middleware)]
      (nrepl-serv/start-server
       :port (:nrepl-port figwheel-options)
       :bind (:nrepl-host figwheel-options)
       :handler (apply nrepl-serv/default-handler middleware)))))

(defn create-autobuild-env [{:keys [figwheel-options all-builds build-ids]}]
  (let [logfile-path (or (:server-logfile figwheel-options) "figwheel_server.log")
        _ (config/mkdirs logfile-path)
        log-writer       (if (false? (:repl figwheel-options))
                           *out*
                           (io/writer logfile-path :append true)) 
        state-atom        (atom {:autobuilder nil
                                 :focus-ids  build-ids})
        all-builds        (mapv auto/prep-build all-builds)
        build-ids         (initial-build-ids all-builds build-ids)
        figwheel-server   (figwheel-sidecar.core/start-server figwheel-options)]
    {:all-builds all-builds
     :build-ids build-ids
     :figwheel-server figwheel-server
     :state-atom state-atom
     :output-writer log-writer
     :error-writer log-writer}))

(defn start-figwheel!
  [{:keys [figwheel-options all-builds build-ids] :as autobuild-options}]
  (let [env (create-autobuild-env
             {:figwheel-options (config/prep-options figwheel-options)
              :all-builds (config/prep-builds all-builds)
              :build-ids (map name build-ids)})]
    (binding [*autobuild-env* env]
      (start-autobuild (:build-ids env))
      (start-nrepl-server figwheel-options env))
    env))

(defn start-figwheel-and-cljs-repl! [autobuild-options]
  (binding [*autobuild-env* (start-figwheel! autobuild-options)]
    (repl-switching-loop)))

(defn stop-figwheel! [autobuild-env]
  (when autobuild-env
    (binding [*autobuild-env* autobuild-env]
      (stop-autobuild))
    (Thread/sleep 100)
    (when-let [{:keys [figwheel-server]} autobuild-env]
      (fig/stop-server figwheel-server)
      (Thread/sleep 200))))

(defn run-autobuilder [{:keys [figwheel-options all-builds build-ids] :as options}]
  (binding [*autobuild-env* (create-autobuild-env options)]
    (start-autobuild (:build-ids *autobuild-env*))
    (start-nrepl-server figwheel-options *autobuild-env*)
    (if (false? (:repl figwheel-options))
      (loop [] (Thread/sleep 30000) (recur))
      (repl-switching-loop))))
