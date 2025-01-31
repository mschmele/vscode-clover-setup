;; ~/.config/clover/config.cljs

(defn- wrap-in-tap [code]
  (str "(let [value (try " code " (catch Throwable t t))]"
       "  (tap> value)"
       "  value)"))

(defn- wrap-in-tap-as-tree [code]
  (str "(let [value (try " code " (catch Throwable t t))]"
       "  (tap> (with-meta value {:portal.viewer/default :portal.viewer/tree}))"
       "  value)"))

(defn tap-top-block []
  (p/let [block (editor/get-top-block)]
    (when (seq (:text block))
      (-> block
          (update :text wrap-in-tap)
          (editor/eval-and-render)))))

(defn tap-block []
  (p/let [block (editor/get-block)]
    (when (seq (:text block))
      (-> block
          (update :text wrap-in-tap)
          (editor/eval-and-render)))))

(defn tap-block-as-tree []
  (p/let [block (editor/get-block)]
    (when (seq (:text block))
      (-> block
          (update :text wrap-in-tap-as-tree)
          (editor/eval-and-render)))))

(defn tap-selection []
  (p/let [block (editor/get-selection)]
    (when (seq (:text block))
      (-> block
          (update :text wrap-in-tap)
          (editor/eval-and-render)))))

(defn tap-def-var []
  (p/let [block (editor/get-selection)]
    (when (seq (:text block))
      (-> block
          (update :text
                  #(str "(def " % ")"))
          (update :text wrap-in-tap)
          (editor/eval-and-render)))))

(defn tap-var []
  (p/let [block (editor/get-var)]
    (when (seq (:text block))
      (-> block
          (update :text #(str "(let [m (meta (or (find-ns '"
                              %
                              ") (resolve '"
                              % ")))]"
                              " (if (contains? m :arglists)"
                              "  (update m :arglists str)"
                              "  m"
                              " )"
                              ")"))
          (update :text wrap-in-tap)
          (editor/eval-and-render)))))

(defn tap-ns []
  (p/let [block (editor/get-namespace)
          here  (editor/get-selection)]
    (when (seq (:text block))
      (-> block
          (update :text #(str "(-> '" %
                              " (find-ns)"
                              " (clojure.datafy/datafy)"
                              " :publics)"))
          (update :text wrap-in-tap)
          (assoc :range (:range here))
          (editor/eval-and-render)))))

(defn- wrap-in-clean-ns
  "Given a string, find the namespace, and clean it up:
  remove its aliases, its refers, and any interns."
  [s]
  (str "(when-let [ns (find-ns '" s ")]"
       " (run! #(try (ns-unalias ns %) (catch Throwable _)) (keys (ns-aliases ns)))"
       " (run! #(try (ns-unmap ns %)   (catch Throwable _)) (keys (ns-interns ns)))"
       " (->> (ns-refers ns)"
       "      (remove (fn [[_ v]] (.startsWith (str v) \"#'clojure.core/\")))"
       "      (map key)"
       "      (run! #(try (ns-unmap ns %) (catch Throwable _)))))"))

(defn tap-remove-ns []
  (p/let [block (editor/get-namespace)
          here  (editor/get-selection)]
    (when (seq (:text block))
      (editor/run-callback
       :notify
       {:type :info :title "Removing..." :message (:text block)})
      (-> block
          (update :text wrap-in-clean-ns)
          (update :text wrap-in-tap)
          (assoc :range (:range here))
          (editor/eval-and-render)))))

(defn tap-reload-all-ns []
  (p/let [block (editor/get-namespace)
          here  (editor/get-selection)]
    (when (seq (:text block))
      (editor/run-callback
       :notify
       {:type :info :title "Reloading all..." :message (:text block)})
      (p/let [res (editor/eval-and-render
                    (-> block
                        (update :text #(str "(require '" % " :reload-all)"))
                        (update :text wrap-in-tap)
                        (assoc :range (:range here))))]
        (editor/run-callback
         :notify
         {:type (if (:error res) :warning :info)
          :title (if (:error res)
                   "Reload failed for..."
                   "Reload succeeded!")
          :message (:text block)})))))

(defn- format-test-result [{:keys [test pass fail error]}]
  (str "Ran " test " test"
       (when-not (= 1 test) "s")
       (when-not (zero? pass)
         (str ", " pass " assertion"
              (when-not (= 1 pass) "s")
              " passed"))
       (when-not (zero? fail)
         (str ", " fail " failed"))
       (when-not (zero? error)
         (str ", " error " errored"))
       "."))

(defn tap-run-current-test []
  (p/let [block (editor/get-top-block)
          test-name (when (seq (:text block))
                      (clojure.string/replace (:text block)
                                              #"\(def[a-z]* (\^:[-a-z]* )*([^\s]*)[^.]*"
                                              "$2"))
          here  (editor/get-selection)]
    (when (seq test-name)
      (p/let [res (editor/eval-and-render
                   (-> block
                       (update :text
                               (fn [_]
                                 (str "
                          (with-out-str
                            (binding [clojure.test/*test-out* *out*]
                              (clojure.test/test-vars [#'" test-name "])))")))
                       (update :text wrap-in-tap)
                       (assoc :range (:range here))))]
        (editor/run-callback
         :notify
         (if (:error res)
           {:type :info
            :title "Failed to run tests for"
            :message test-name}
           (try
             (let [s (str (:result res))]
               (if (re-find #"FAIL in" s)
                 {:type :warning
                  :title test-name
                  :message s}
                 {:type :info
                  :title (str test-name " passed")
                  :message (when (seq s) s)}))
             (catch js/Error e
               {:type :warning
                :title "EXCEPTION!"
                :message (ex-message e)}))))))))

(defn tap-run-tests []
  (p/let [block (editor/get-namespace)
          here  (editor/get-selection)]
    (when (seq (:text block))
      (p/let [res (editor/eval-and-render
                   (-> block
                       (update :text (fn [s] (str "
                          (try
                            (let [nt (symbol \"" s "\")]
                              (clojure.test/run-tests nt))
                            (catch Throwable _))")))
                       (update :text wrap-in-tap)
                       (assoc :range (:range here))))]
        (editor/run-callback
         :notify
         {:type (if (:error res) :warning :info)
          :title (if (:error res)
                   "Failed to run tests for..."
                   "Tests completed!")
          :message (if (:error res) (:text block) (format-test-result (:result res)))})))))

(defn tap-run-side-tests []
  (p/let [block (editor/get-namespace)
          here  (editor/get-selection)]
    (when (seq (:text block))
      (p/let [res (editor/eval-and-render
                    (-> block
                        (update :text (fn [s] (str "
                          (some #(try
                                   (let [nt (symbol (str \"" s "\" \"-\" %))]
                                     (require nt)
                                     (clojure.test/run-tests nt))
                                  (catch Throwable _))
                                [\"test\" \"expectations\"])")))
                        (update :text wrap-in-tap)
                        (assoc :range (:range here))))]
        (editor/run-callback
         :notify
         {:type (if (:error res) :warning :info)
          :title (if (:error res)
                   "Failed to run tests for..."
                   "Tests completed!")
          :message (if (:error res) (:text block) (format-test-result (:result res)))})))))

(defn- wrap-in-tap-html [code]
  (str "(let [value " code
       "      html  (try (slurp value) (catch Throwable _))]"
       "  (tap> (if html"
       "          (with-meta [:div {:style {:background :white}}"
       "                      [:portal.viewer/html html]]"
       "            {:portal.viewer/default :portal.viewer/hiccup})"
       "        value))"
       "  value)"))

(defn tap-doc-var []
  (p/let [block (editor/get-var)]
    (when (seq (:text block))
      (-> block
          (update :text
                  #(str
                    "(java.net.URL."
                    " (str \"https://clojuredocs.org/\""
                    " (-> (str (symbol #'" % "))"
                    ;; clean up ? ! &
                    "  (clojure.string/replace \"?\" \"%3f\")"
                    "  (clojure.string/replace \"!\" \"%21\")"
                    "  (clojure.string/replace \"&\" \"%26\")"
                    ")))"))
          (update :text wrap-in-tap-html)
          (editor/eval-and-render)))))

(defn tap-javadoc []
  (p/let [block (editor/get-selection)
          block (if (< 1 (count (:text block))) block (editor/get-var))]
      (when (seq (:text block))
        (-> block
            (update :text
                    #(str
                      "(let [c-o-o " %
                      " ^Class c (if (instance? Class c-o-o) c-o-o (class c-o-o))] "
                      " (java.net.URL. "
                      "  (->"
                      "   ((requiring-resolve 'clojure.java.javadoc/javadoc-url)"
                      "    (.getName c))"
                      "   (clojure.string/replace" ; strip inner class
                      "    #\"\\$[a-zA-Z0-9_]+\" \"\")"
                      "   (clojure.string/replace" ; force https
                      "    #\"^http:\" \"https:\")"
                      ")))"))
            (update :text wrap-in-tap-html)
            (editor/eval-and-render)))))

(defn- add-libs [deps]
  (str "((requiring-resolve 'clojure.tools.deps.alpha.repl/add-libs) '" deps ")"))

(defn tap-add-libs []
  (p/let [block (editor/get-block)]
    (when (seq (:text block))
      (-> block
          (update :text add-libs)
          (update :text wrap-in-tap)
          (editor/eval-and-render)))))

(defn portal-start []
  (p/let [here (editor/get-selection)]
    (editor/eval-and-render
     (assoc here :text
            (str "(do"
                 " (ns dev)"
                 " (def portal"
                 "  ((requiring-resolve 'portal.api/open)"
                 "   {:launcher :vs-code :portal.launcher/window-title (System/getProperty \"user.dir\")}))"
                 " (try
                    (let [r!   (requiring-resolve 'portal.runtime/register!)
                          html (fn [url]
                                (with-meta
                                  [:div
                                    {:style {:background :white}}
                                    [:portal.viewer/html (slurp url)]]
                                  {:portal.viewer/default :portal.viewer/hiccup}))]
                      ;; install extra functions:
                      (run! (fn [[k f]] (r! f {:name k}))
                            {'dev/->file   (requiring-resolve 'clojure.java.io/file)
                            'dev/->html   html
                            'dev/->map    (partial into {})
                            'dev/->set    (partial into #{})
                            'dev/->vector (partial into [])}))
                    (catch Throwable _))"
                 " (add-tap (requiring-resolve 'portal.api/submit)))")))))

(defn portal-clear []
  (p/let [here (editor/get-selection)]
    (editor/eval-and-render (assoc here :text "(portal.api/clear)"))))
