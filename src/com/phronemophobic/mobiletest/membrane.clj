(ns com.phronemophobic.mobiletest.membrane
  (:require membrane.ios
            [membrane.ui :as ui]
            babashka.nrepl.server
            [sci.core :as sci]
            [sci.addons :as addons]
            [com.phronemophobic.mobiletest.objc :as objc]
            [com.phronemophobic.clj-objc :as clj-objc]

            [tech.v3.datatype.ffi :as dt-ffi]
            [tech.v3.datatype :as dtype]
            [clojure.java.io :as io]

            ;; babashka extras
            babashka.impl.async
            babashka.impl.hiccup
            babashka.impl.httpkit-client
            babashka.impl.httpkit-server)
  (:import java.net.NetworkInterface
           java.net.URL
           java.net.InetAddress)
  (:gen-class))

(set! *warn-on-reflection* true)

(def main-view (atom nil))
(defonce debug-view (atom nil))
(defonce debug-log (atom []))

(defn sleep [msecs]
  (Thread/sleep msecs))

(defn ns->ns-map [ns-name]
  (let [fns (sci/create-ns ns-name nil)]
    {ns-name
     (reduce (fn [ns-map [var-name var]]
               (let [m (meta var)
                     no-doc (:no-doc m)
                     doc (:doc m)
                     arglists (:arglists m)]
                 (if no-doc ns-map
                     (assoc ns-map var-name
                            (sci/new-var (symbol var-name) @var
                                         (cond-> {:ns fns
                                                  :name (:name m)}
                                           (:macro m) (assoc :macro true)
                                           doc (assoc :doc doc)
                                           arglists (assoc :arglists arglists)))))))
             {}
             (ns-publics ns-name))}))



(defn url->image [s]
  (ui/image (java.net.URL. s)))

(defn acceleration->map [data]
  {:x (objc/xAcceleration data)
   :y (objc/yAcceleration data)
   :z (objc/zAcceleration data)})

(def opts (addons/future
            {:classes
             {'java.net.URL java.net.URL}
             :namespaces
             (merge (let [ns-name 'com.phronemophobic.mobiletest.membrane
                          fns (sci/create-ns ns-name nil)]
                      {ns-name {'main-view (sci/copy-var main-view fns)
                                'debug-view (sci/copy-var debug-view fns)
                                'debug-log (sci/copy-var debug-log fns)}})

                    (let [ns-name 'clojure.core
                          fns (sci/create-ns ns-name nil)]
                      {ns-name {'sleep (sci/copy-var sleep fns)
                                'main-view (sci/copy-var main-view fns)
                                'url->image (sci/copy-var url->image fns)
                                'debug-view (sci/copy-var debug-view fns)
                                'acceleration->map (sci/copy-var acceleration->map fns)
                                'debug-log (sci/copy-var debug-log fns)}})

                    (ns->ns-map 'membrane.ui)
                    (ns->ns-map 'membrane.ios)
                    (ns->ns-map 'com.phronemophobic.clj-objc)

                    ;; extras
                    {'clojure.core.async babashka.impl.async/async-namespace
                     'clojure.core.async.impl.protocols babashka.impl.async/async-protocols-namespace

                     'org.httpkit.client babashka.impl.httpkit-client/httpkit-client-namespace
                     'org.httpkit.sni-client babashka.impl.httpkit-client/sni-client-namespace
                     'org.httpkit.server babashka.impl.httpkit-server/httpkit-server-namespace

                     'hiccup.core babashka.impl.hiccup/hiccup-namespace
                     'hiccup2.core babashka.impl.hiccup/hiccup2-namespace
                     'hiccup.util babashka.impl.hiccup/hiccup-util-namespace
                     'hiccup.compiler babashka.impl.hiccup/hiccup-compiler-namespace}

                    {'clojure.main {'repl-requires
                                    '[[clojure.repl :refer [dir doc]]]}})}))


(def sci-ctx (sci/init opts))

(defonce old-eval-msg babashka.nrepl.impl.server/eval-msg)


(defonce clear-future (atom nil))

(defn show-code [code]
  (when-let [fut @clear-future]
    (future-cancel fut)
    (reset! clear-future nil))

  (reset! debug-view
          (let [body (ui/padding
                      5 5
                      (ui/label code))
                [w h] (ui/bounds body)]
            (ui/translate 10 60
                          [(ui/with-color [1 1 1 0.8]
                             (ui/rectangle w h))
                           body])))

  (reset! clear-future
          (future
            (Thread/sleep 2000)
            (reset! debug-view nil))))

(comment
  (def server (babashka.nrepl.server/start-server! sci-ctx {:host "0.0.0.0" :port 23456
                                                            :debug true

                                                            :xform
                                                            (comp babashka.nrepl.impl.server/wrap-read-msg
                                                                  (map (fn [m]
                                                                         (prn "received" (-> m :msg))
                                                                         m))
                                                                  babashka.nrepl.impl.server/wrap-process-message)}))
  (.close (:socket server))

  (require '[membrane.java2d :as backend])
  (backend/run #(deref debug-view))

  ,
)

(defn get-local-address []
  (let [address (->> (NetworkInterface/getNetworkInterfaces)
                       enumeration-seq
                       (filter (fn [interface]
                                 (.startsWith (.getName ^NetworkInterface interface)
                                              "en")))
                       (map
                        (fn [interface]
                          (let [ip4 (->> (.getInetAddresses ^NetworkInterface interface)
                                         enumeration-seq
                                         (some (fn [inet]
                                                 (when (= 4 (count
                                                             (.getAddress ^InetAddress inet)))
                                                   inet))))]
                            ip4)))
                       (filter (fn [ip4]
                                 (.isSiteLocalAddress ^InetAddress ip4)))
                       first)]
    address))


(defn get-addresses []
  (let [addresses (->> (NetworkInterface/getNetworkInterfaces)
                       enumeration-seq
                       (filter (fn [interface]
                                 (.startsWith (.getName ^NetworkInterface interface)
                                              "en")))
                       (map
                        (fn [interface]
                          (let [ip4 (->> (.getInetAddresses ^NetworkInterface interface)
                                         enumeration-seq
                                         (some (fn [inet]
                                                 (when (= 4 (count
                                                             (.getAddress ^InetAddress inet)))
                                                   inet))))]
                            (.getHostAddress ^InetAddress ip4)))))]
    addresses))

(defn with-background [body]
  (let [body (ui/padding 5 body)
        [w h] (ui/bounds body)]
    [(ui/filled-rectangle [1 1 1]
                          w h)
     body]))

(defn clj_init []
  (membrane.ios/initialize-ios)
  (objc/initialize-objc)
  (clj-objc/initialize-objc)
  (let [path-str (dt-ffi/c->string
                  (objc/clj_app_dir))
        path (io/file path-str "gol.clj")]
    (prn "file path:"
         path-str
         path
         (.exists path))
    (sci/eval-string (slurp path) sci-ctx))
  (let [local-address (get-local-address)
        host-address (when local-address
                       (.getHostAddress ^InetAddress local-address))
        address-str (if host-address
                      (str host-address ":" 23456)
                      "No local address found.")]
    (reset! debug-view (ui/translate 10 50
                                     (with-background
                                       (ui/label address-str))))
    (println (str "address: \n" address-str))
    (babashka.nrepl.server/start-server! sci-ctx
                                         {:host host-address :port 23456
                                          :xform
                                          (comp babashka.nrepl.impl.server/wrap-read-msg
                                                (map (fn [m]
                                                       (let [msg (:msg m)]
                                                         (case (:op msg)
                                                           :eval (show-code (:code msg))
                                                           :load-file (show-code (:file msg))
                                                           nil))
                                                       m))
                                                babashka.nrepl.impl.server/wrap-process-message)})))

(defonce last-draw (atom nil))

(defn clj_needs_redraw []
  (if (not= @last-draw
            [@main-view @debug-view])
    1
    0))


(defn clj_draw [ctx]
  (try
    (let [mv @main-view
          dv @debug-view]
      (membrane.ios/skia_clear ctx)
      (membrane.ios/draw! ctx mv)
      (membrane.ios/draw! ctx dv)
      (reset! last-draw [mv dv]))
    (catch Exception e
      (prn e)
      (reset! last-draw nil))))

(defn clj_touch_ended [x y]
  (try
    (doall (ui/mouse-up @main-view [x y]))
    (catch Exception e
      (prn e))))

(defn clj_touch_began [x y]
  (try
    (doall (ui/mouse-down @main-view [x y]))
    (catch Exception e
      (prn e))))

(defn clj_touch_moved [x y]
  (try
    (let [view @main-view]
      (doall (ui/mouse-move view [x y]))
      (doall (ui/mouse-move-global view [x y])))
    (catch Exception e
      (prn e))))

(defn clj_touch_cancelled [x y])

(defn clj_insert_text [ptr]
  (let [s (dt-ffi/c->string ptr)]
    (try 
      (ui/key-press @main-view s)
      (catch Exception e
        (prn e)))))

(defn clj_delete_backward []
  (try
    (ui/key-press @main-view :backspace)
    (catch Exception e
      (prn e))))


(defn -main [& args])

(defn compile-interface-class [& args]
  ((requiring-resolve 'tech.v3.datatype.ffi.graalvm/expose-clojure-functions)
   {#'clj_init {:rettype :void}

    #'clj_needs_redraw {:rettype :int32}

    #'clj_draw {:rettype :void
                :argtypes [['skia-resource :pointer]]}
    #'clj_touch_ended {:rettype :void
                       :argtypes [['x :float64]
                                  ['y :float64]]}
    #'clj_touch_began {:rettype :void
                       :argtypes [['x :float64]
                                  ['y :float64]]}
    #'clj_touch_moved {:rettype :void
                       :argtypes [['x :float64]
                                  ['y :float64]]}
    #'clj_touch_cancelled {:rettype :void
                           :argtypes [['x :float64]
                                      ['y :float64]]}
    #'clj_delete_backward {:rettype :void}
    #'clj_insert_text {:rettype :void
                       :argtypes [['s :pointer]]}}

   'com.phronemophobic.mobiletest.membrane.interface nil)
  )

(when *compile-files*
  (compile-interface-class))
