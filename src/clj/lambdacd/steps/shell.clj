(ns lambdacd.steps.shell
  (:require [lambdacd.steps.support :as support]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [me.raynes.conch.low-level :as sh]
            [lambdacd.util :as utils]
            [lambdacd.util :as util])
  (:import (java.util UUID)
           (java.io IOException)))


(defn- exit-code->status [exit-code was-killed]
  (cond
    was-killed :killed
    (zero? exit-code) :success
    :default :failure))

(defn kill [was-killed-indicator proc]
  (reset! was-killed-indicator true)
  (.destroy proc))

(defn- add-kill-handling [ctx proc was-killed watch-ref]
  (let [is-killed (:is-killed ctx)]
    (dosync
      (if @is-killed
        (kill was-killed proc)
        (add-watch is-killed watch-ref (fn [_ _ _ new]
                                           (if new
                                             (kill was-killed proc))))))))

(defn- safe-read-line [reader]
  (try
    (.readLine reader)
    (catch IOException e nil)))

(defn- read-and-print-shell-output [proc-result ctx]
  (let [out-reader (io/reader (:out proc-result))
        printer (support/new-printer)]
    (loop []
      (let [line (safe-read-line out-reader)]
        (if line
          (do
            (support/print-to-output ctx printer line)
            (recur)))))
    (support/printed-output printer)))

(defn- execte-shell-command [cwd shell-script ctx env]
  (let [x (sh/proc "bash" "-e" shell-script
                   :dir cwd
                   :env env
                   :redirect-err true)
        proc (:process x)
        was-killed (atom false)
        kill-switch (:is-killed ctx)
        watch-ref (UUID/randomUUID)
        _ (add-kill-handling ctx proc was-killed watch-ref)
        out (read-and-print-shell-output x ctx)
        exit-code (sh/exit-code x)
        status (exit-code->status exit-code @was-killed)]
    (remove-watch kill-switch watch-ref)
    {:exit exit-code :status status :out out}))

(defn bash
  "step that executes commands in a bash. arguments are the working-directory and at least one command to execute
  returns stdout and stderr as :out value, the exit code as :exit and succeeds if exit-code was 0"
  [ctx cwd & optional-env-and-commands]
  (let [temp-file (utils/create-temp-file)
        env-or-first-command (first optional-env-and-commands)
        env (if (map? env-or-first-command) env-or-first-command {})
        commands (if (map? env-or-first-command) (rest optional-env-and-commands) optional-env-and-commands)
        command-lines (string/join "\n" commands)]
    (spit temp-file command-lines)
    (util/with-temp temp-file
      (execte-shell-command cwd temp-file ctx env))))