(ns figwheel-sidecar.repl
  (:require
   [cljs.analyzer :as ana]
   [cljs.env :as env]
   [cljs.repl]
   [cljs.stacktrace]
   [cljs.util :refer [debug-prn]]
   [clojure.core.async :refer [chan <!! <! put! alts!! timeout close! go go-loop]]
   [clojure.java.io :as io]
   [clojure.pprint :as pp]
   [clojure.string :as string]
   [figwheel-sidecar.cljs-utils.exception-parsing :as cljs-ex]
   [figwheel-sidecar.components.figwheel-server :as server]
   [figwheel-sidecar.utils :refer [require?]]
   [figwheel-sidecar.config :as config]
   [strictly-specking-standalone.ansi-util :refer [with-color-when color]])
  (:import [clojure.lang IExceptionInfo]))

(let [timeout-val (Object.)]
  (defn eval-js [{:keys [browser-callbacks repl-eval-timeout] :as figwheel-server} js]
    (let [out (promise)
          repl-timeout (or repl-eval-timeout 8000)]
      (server/send-message-with-callback figwheel-server
                                         (:build-id figwheel-server)
                                         {:msg-name :repl-eval
                                          :code js}
                                         (partial deliver out))
      (let [v (deref out repl-timeout timeout-val)]
        (if (= timeout-val v)
          {:status :exception
           :value "Eval timed out!"
           :stacktrace "No stacktrace available."}
          v)))))

(defn connection-available?
  [figwheel-server build-id]
  (let [connection-count (server/connection-data figwheel-server)]
    (not
     (zero?
      (+ (or (get connection-count build-id) 0)
         (or (get connection-count nil) 0))))))

;; limit how long we wait?
(defn wait-for-connection [{:keys [build-id] :as figwheel-server}]
  (when-not (connection-available? figwheel-server build-id)
    (loop []
      (when-not (connection-available? figwheel-server build-id)
        (Thread/sleep 500)
        (recur)))))

(defn add-repl-print-callback! [{:keys [browser-callbacks repl-print-chan]}]
  ;; relying on the fact that we are running one repl at a time, not so good
  ;; we could create a better id here, we can add the build id at least
  (swap! browser-callbacks assoc "figwheel-repl-print"
         (fn [print-message] (put! repl-print-chan print-message))))

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

(defn clean-stacktrace [stack-trace]
  (map #(update-in % [:file]
                   (fn [x]
                     (when (string? x)
                       (first (string/split x #"\?")))))
       stack-trace))

(defrecord FigwheelEnv [figwheel-server repl-opts]
  cljs.repl/IReplEnvOptions
  (-repl-options [this]
    repl-opts)
  cljs.repl/IJavaScriptEnv
  (-setup [this opts]
    ;; we need to print in the same thread as
    ;; the that the repl process was created in
    ;; thank goodness for the go loop!!

    ;; TODO I don't think we need this anymore
    ;; if we miss extraneous prints it's not a big deal
    (reset! (::repl-writers figwheel-server) (get-thread-bindings))
    (go-loop []
      (when-let [{:keys [stream args]}
                 (<! (:repl-print-chan figwheel-server))]
        (with-bindings @(::repl-writers figwheel-server)
          (if (= stream :err)
            (binding [*out* *err*]
              (apply println args)
              (flush))
            (do
              (apply println args)
              (flush))))
        (recur)))
    (add-repl-print-callback! figwheel-server)
    (wait-for-connection figwheel-server)
    (Thread/sleep 500)) ;; just to help with setup latencies
  (-evaluate [_ _ _ js]
    (reset! (::repl-writers figwheel-server) (get-thread-bindings))
    (wait-for-connection figwheel-server)
    (let [{:keys [out] :as result} (eval-js figwheel-server js)]
      (when (not (string/blank? out))
        (println (string/trim-newline out)))
      result))
      ;; this is not used for figwheel
  (-load [this ns url]
    (reset! (::repl-writers figwheel-server) (get-thread-bindings))
    (wait-for-connection figwheel-server)
    (let [{:keys [out] :as result} (eval-js figwheel-server (slurp url))]
      (when (not (string/blank? out))
        (println (string/trim-newline out)))
      result))
  (-tear-down [_]
    (close! (:repl-print-chan figwheel-server))
    true)
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
            (filter valid-stack-line?
                    (cljs.repl/mapped-stacktrace (clean-stacktrace stacktrace)
                                                 build-options))]
      (println "  " (str function " (" (str (or url file)) ":" line ":" column ")")))
    (flush)))

(defn repl-env
  ([figwheel-server {:keys [id build-options] :as build} repl-opts]
   (assoc (FigwheelEnv. (merge figwheel-server
                               (if id {:build-id id} {})
                               {::repl-writers (atom {:out *out*
                                                      :err *err*})}
                               (select-keys build-options [:output-dir :output-to]))
                        repl-opts)
          :cljs.env/compiler (:compiler-env build)))
  ([figwheel-server build]
   (repl-env figwheel-server build nil))
  ([figwheel-server]
   (FigwheelEnv. figwheel-server nil)))

;; add some repl functions for reloading local clj code

(defn bound-var? [sym]
  (when-let [v (resolve sym)]
    (thread-bound? v)))

(defmulti start-cljs-repl (fn [protocol figwheel-env]
                            protocol))

(defmethod start-cljs-repl :nrepl
  [_ figwheel-env]
  (try
    (cond
      (and (require? 'cider.piggieback)
           (bound-var? 'cider.piggieback/*cljs-repl-env*))
      (let [cljs-repl (resolve 'cider.piggieback/cljs-repl)
            opts' (:repl-opts figwheel-env)]
        (apply cljs-repl figwheel-env (apply concat opts')))
      (and (require? 'cemerick.piggieback)
           (bound-var? 'cemerick.piggieback/*cljs-repl-env*))
      (let [cljs-repl (resolve 'cemerick.piggieback/cljs-repl)
            opts' (:repl-opts figwheel-env)]
        (apply cljs-repl figwheel-env (apply concat opts')))
      :else (throw (ex-info "Unable to load a ClojureScript nREPL middleware library" {})))
    (catch Exception e
      (println "!!!" (.getMessage e))
      (let [message "Failed to launch Figwheel CLJS REPL: nREPL connection found but unable to load piggieback.
This is commonly caused by
 A) not providing piggieback as a dependency and/or
 B) not adding piggieback middleware into your nrepl middleware chain.

example profile.clj code:
-----
:profiles {:dev {:dependencies [[cider/piggieback <current-version>]
                                [org.clojure/tools.nrepl  <current-version>]]
                 :repl-options {:nrepl-middleware [cider.piggieback/wrap-cljs-repl]}}}
-----
Please see the documentation for piggieback here https://github.com/clojure-emacs/piggieback#installation

Note: Cider will inject this config into your project.clj.
This can cause confusion when your are not using Cider."]
        (throw (Exception. message))))))

(defmethod start-cljs-repl :default
  [_ figwheel-env]
  (let [prompt (:prompt (:repl-opts figwheel-env))
        prompt-fn' (fn [] (with-out-str (prompt)))]
    (cond (and
           (require? 'rebel-readline.cljs.repl)
           (require? 'rebel-readline.commands)
           (resolve  'rebel-readline.cljs.repl/repl*)
           (resolve  'rebel-readline.commands/add-command))
          (let [rebel-cljs-repl* (resolve 'rebel-readline.cljs.repl/repl*)
                add-command      (resolve 'rebel-readline.commands/add-command)
                docs             (resolve 'figwheel-sidecar.system/repl-function-docs)]
            (when (and add-command docs @add-command @docs)
              (add-command
               :repl/help-figwheel
               #(println @docs)
               "Displays the help docs for the Figwheel REPL"))
            (try
              (rebel-cljs-repl* figwheel-env (:repl-opts figwheel-env))
              (catch clojure.lang.ExceptionInfo e
                (if (-> e ex-data :type (= :rebel-readline.jline-api/bad-terminal))
                  (do (println (.getMessage e))
                      (println "Falling back to REPL without terminal readline functionality!")
                      (cljs.repl/repl* figwheel-env (:repl-opts figwheel-env)))
                  (throw e)))))
          :else
          (cljs.repl/repl* figwheel-env (:repl-opts figwheel-env)))))

(defn in-nrepl-env? []
  (or
   (bound-var? 'nrepl.middleware.interruptible-eval/*msg*)
   (bound-var? 'clojure.tools.nrepl.middleware.interruptible-eval/*msg*)))

(defn catch-exception
  ([e repl-env opts form env]
   (if (and (instance? IExceptionInfo e)
            (#{:js-eval-error :js-eval-exception} (:type (ex-data e))))
     (cljs.repl/repl-caught e repl-env opts)
     (with-color-when (config/use-color? (:figwheel-server repl-env))
       (cljs-ex/print-exception e (cond-> {:environment :repl
                                           :current-ns ana/*cljs-ns*}
                                    form (assoc :source-form form)
                                    env  (assoc :compile-env env))))))
  ([e repl-env opts]
   (catch-exception e repl-env opts nil nil)))

(defn warning-handler [repl-env form opts]
  (fn [warning-type env extra]
    (when-let [warning-data (cljs-ex/extract-warning-data warning-type env extra)]
      (debug-prn (with-color-when (config/use-color? (:figwheel-server repl-env))
                   (cljs-ex/format-warning (assoc warning-data
                                                  :source-form form
                                                  :current-ns ana/*cljs-ns*
                                                  :environment :repl)))))))

(defn- wrap-fn [form]
  (cond
    (and (seq? form)
         (#{'ns 'require 'require-macros
            'use 'use-macros 'import 'refer-clojure} (first form)))
    identity

    ('#{*1 *2 *3 *e} form)
    (fn [x] `((if (and
                   (cljs.core/exists? js/figwheel)
                   (cljs.core/exists? js/figwheel.client.repl_result_pr_str))
                js/figwheel.client.repl_result_pr_str
                cljs.core.pr-str)
              ~x))
    :else
    (fn [x]
      `(try
         ((if (and
               (cljs.core/exists? js/figwheel)
               (cljs.core/exists? js/figwheel.client.repl_result_pr_str))
            js/figwheel.client.repl_result_pr_str
            cljs.core.pr-str)
          (let [ret# ~x]
            (set! *3 *2)
            (set! *2 *1)
            (set! *1 ret#)
            ret#))
         (catch :default e#
           (set! *e e#)
           (throw e#))))))

(defn catch-warnings-and-exceptions-eval-cljs
  ([repl-env env form]
   (catch-warnings-and-exceptions-eval-cljs
    repl-env env form cljs.repl/*repl-opts*))
  ([repl-env env form opts]
   (try
     (binding [cljs.analyzer/*cljs-warning-handlers*
               [(warning-handler repl-env form opts)]]
       (#'cljs.repl/eval-cljs repl-env env form opts))
     (catch Throwable e
       (catch-exception e repl-env opts form env)
       ;; when we are in an nREPL environment lets re-throw with a friendlier
       ;; message maybe
       #_(when (in-nrepl-env?)
           (throw (ex-info "Hey" {})))))))

(defn connection-count [figwheel-server build-id]
  (get (server/connection-data figwheel-server) build-id))

(defn prompt-fn [figwheel-server build-id]
  (if (in-nrepl-env?)
    #(when-let [c (connection-count figwheel-server build-id)]
       (when (> c 1)
         (with-color-when (config/use-color? figwheel-server)
           (println
            (color
             (str "v------- " build-id "!{:conn " c "} -------")
             :magenta)))))
    #(print
      (str
       (when build-id (str build-id ":"))
       ana/*cljs-ns*
       (when-let [c (connection-count figwheel-server build-id)]
         (when (< 1 c) (str "!{:conn " c "}")))
       "=> "))))

(defn cljs-repl-env
  ([build figwheel-server]
   (cljs-repl-env build figwheel-server {}))
  ([build figwheel-server opts]
   (let [opts (merge (assoc (or (:compiler build) (:build-options build))
                            :warn-on-undeclared true
                            :wrap  wrap-fn
                            :prompt (prompt-fn figwheel-server (:id build))
                            :eval #'catch-warnings-and-exceptions-eval-cljs)
                     opts)
         figwheel-server (assoc figwheel-server
                                :repl-print-chan (chan))
         figwheel-repl-env (repl-env figwheel-server build)
         repl-opts (merge
                     {:compiler-env (:compiler-env build)
                      :special-fns cljs.repl/default-special-fns
                      :output-dir "out"}
                     opts)]
     (assoc figwheel-repl-env
            ;; these are merged to opts by cljs.repl/repl*
            :repl-opts repl-opts))))

(defn repl
  [& args]
  (start-cljs-repl
    (if (in-nrepl-env?)
      :nrepl
      :default)
    (apply cljs-repl-env args)))

;; deprecated
(defn get-project-cljs-builds []
  (-> (config/fetch-config) :data :all-builds))
