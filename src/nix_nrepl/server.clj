(ns nix-nrepl.server
  (:refer-clojure :exclude [send])
  (:require
   [bencode.core :refer [write-bencode read-bencode]]
   [me.raynes.conch.low-level :as sh]
   [clojure.string :as str]
   [clojure.stacktrace :as stacktrace])
  (:import
   [java.net ServerSocket]
   [java.io PushbackInputStream EOFException]))

(def debug? true)

(defonce nix-repl (sh/proc "nix" "repl"))

(defn read-available! []
  (let [out (:out nix-repl)
        bb (byte-array (.available out))]
    (.read out bb)
    (reduce str (map char bb))))

;; strip nix repl initialization message
(Thread/sleep 100)
(println "[NIX REPL]" (read-available!))

(defn response-for [old-msg msg]
  (assoc msg
         "session" (get old-msg :session "none")
         "id" (get old-msg :id "unknown")))

(defn send [os msg]
  (when debug? (println "Sending" msg))
  (write-bencode os msg)
  (.flush os))

(defn send-exception [os msg ex]
  (when debug? (prn "sending ex" (with-out-str (stacktrace/print-throwable ex))))
  (send os (response-for msg {"ex" (with-out-str (stacktrace/print-throwable ex))
                              "status" #{"done"}})))

(defn eval-msg [o msg ns]
  (let [code-str (get msg :code)]
    (if-not (= code-str
               "(clojure.core/apply clojure.core/require clojure.main/repl-requires)")
      (do (sh/feed-from-string nix-repl (str code-str "\n"))
          (Thread/sleep 100)
          (let [r (-> (read-available!)
                      (str/replace "[33m" "")
                      (str/replace "[0m" ""))
                r (subs r 0 (max 0 (dec (count r))))]
            (println "[NIX REPL]" r)
            (send o (response-for msg {"ns" (ns-name *ns*)
                                       "value" r}))))
      (send o (response-for msg {"ns" (ns-name *ns*) "value" "nil"})))
    (send o (response-for msg {"status" #{"done"}}))))

(defn register-session [i o ns msg session-loop]
  (let [id (str (java.util.UUID/randomUUID))]
    (send o (response-for msg {"new-session" id "status" #{"done"}}))
    (session-loop i o id ns)))

(defn read-msg [msg]
  (-> (zipmap (map keyword (keys msg))
              (map #(if (instance? (Class/forName "[B") %)
                      (String. %)
                      %) (vals msg)))
      (update :op keyword)))

(defn session-loop [is os id ns]
  (println "Reading!" id (.available is))
  (when-let [msg (try (read-bencode is)
                 (catch EOFException _
                   (println "Client closed connection.")))]
    (let [msg (read-msg msg)]
      ;; (when debug? (prn "Received" msg))
      (case (get msg :op)
        :clone (do
                 (when debug? (println "Cloning!"))
                 (register-session is os ns msg session-loop))
        :eval (do
                (try (eval-msg os msg ns)
                     (catch Exception exn
                       (send-exception os msg exn)))
                (recur is os id ns))
        :describe
        (do (send os (response-for msg {"status" #{"done"}
                                        "aux" {}
                                        "ops" (zipmap #{"clone", "describe", "eval"}
                                                      (repeat {}))
                                        "versions" {"nrepl" {"major" "0"
                                                             "minor" "4"
                                                             "incremental" "0"
                                                             "qualifier" ""}
                                                    "clojure"
                                                    {"*clojure-version*"
                                                     (zipmap (map name (keys *clojure-version*))
                                                             (vals *clojure-version*))}}}))
            (recur is os id ns))
        (when debug?
          (println "Unhandled message" msg)
          (recur is os id ns))))))

(defn listen [listener]
  (when debug? (println "Listening"))
  (let [client-socket (.accept listener)
        in (.getInputStream client-socket)
        in (PushbackInputStream. in)
        out (.getOutputStream client-socket)]
    (when debug? (println "Connected."))
    ;; TODO: run this in a future, but for debugging this is better
    (binding [*print-length* 20]
      (session-loop in out "pre-init" *ns*))
    #_(recur listener)))

(defn -main [& _args]
  (let [server-socket (new ServerSocket 19993)]
    (println "Starting on port" (.getLocalPort server-socket))
    (listen server-socket)))
