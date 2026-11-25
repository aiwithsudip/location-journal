package app.locationjournal.data

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * One-time data exporter for the old WebView build. This is not part of the app UI: an
 * unattached WebView reads the legacy IndexedDB under the old origin and returns JSON.
 * It is removed from memory immediately after export.
 */
@Singleton
class LegacyWebViewExporter @Inject constructor(@ApplicationContext private val context: Context) {
    private val html = """
        <!doctype html><meta charset="utf-8"><title>legacy export</title><script>
        function done(payload) { window.LegacyExport && window.LegacyExport.deliver(payload); }
        var request = indexedDB.open('location-journal', 3);
        request.onerror = function () { done(JSON.stringify({error:'indexeddb'})); };
        request.onupgradeneeded = function (event) { event.preventDefault(); };
        request.onsuccess = function () {
          var db = request.result;
          var names = Array.prototype.slice.call(db.objectStoreNames);
          if (!names.length) { db.close(); done(JSON.stringify({})); return; }
          var out = {points:[],photos:[],meta:[],reelState:[],reelComments:[]};
          try {
            var tx = db.transaction(names, 'readonly');
            names.forEach(function (name) {
              if (!out.hasOwnProperty(name)) return;
              var all = tx.objectStore(name).getAll();
              all.onsuccess = function () { out[name] = all.result || []; };
            });
            tx.oncomplete = function () { db.close(); done(JSON.stringify(out)); };
            tx.onerror = function () { db.close(); done(JSON.stringify({error:'transaction'})); };
          } catch (error) { db.close(); done(JSON.stringify({error:String(error)})); }
        };
        </script>
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun export(): JSONObject = suspendCancellableCoroutine { continuation ->
        val webView = WebView(context.applicationContext)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        var resumed = false
        val handler = Handler(Looper.getMainLooper())
        fun finish(value: JSONObject) {
            if (resumed) return
            resumed = true
            handler.removeCallbacksAndMessages(null)
            continuation.resume(value)
            handler.post {
                runCatching {
                    webView.stopLoading()
                    webView.destroy()
                }
            }
        }
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun deliver(payload: String) {
                finish(runCatching { JSONObject(payload) }.getOrDefault(JSONObject()))
            }
        }, "LegacyExport")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript("void 0") {}
            }
        }
        handler.postDelayed({ finish(JSONObject()) }, 8_000L)
        continuation.invokeOnCancellation { finish(JSONObject()) }
        webView.loadDataWithBaseURL(
            "https://appassets.androidplatform.net/legacy-export.html",
            html,
            "text/html",
            "UTF-8",
            null,
        )
        webView.evaluateJavascript("void 0") {}
        // The page script calls deliver() itself; WebView queues JS after load completes.
    }
}
