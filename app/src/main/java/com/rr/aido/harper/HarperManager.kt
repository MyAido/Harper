package com.rr.aido.harper

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs Harper Grammar Checker completely offline using a hidden WebView.
 * isEngineReady is only flipped to true when the JS side calls Android.onEngineReady(),
 * i.e. after the WASM binary has been loaded and initialized.
 */
class HarperManager(private val context: Context) {
    private val TAG = "HarperManager"
    private var webView: WebView? = null

    // Becomes true ONLY after JS calls Android.onEngineReady()
    @Volatile private var isEngineReady = false

    private val gson = Gson()

    // Pending lint callbacks
    private val requestMap = mutableMapOf<Int, CompletableDeferred<List<HarperLint>>>()
    private val requestCounter = AtomicInteger(0)

    // ─── Data Model ─────────────────────────────────────────────────
    data class HarperLint(
        val span: Span,
        val lintKind: String,
        val isCertain: Boolean,
        val suggestion: String?,
        val message: String
    )
    data class Span(val start: Int, val end: Int)

    // ─── Init ────────────────────────────────────────────────────────
    fun initialize() {
        if (webView != null) return

        Handler(Looper.getMainLooper()).post {
            try {
                @SuppressLint("SetJavaScriptEnabled")
                webView = WebView(context).apply {
                    settings.javaScriptEnabled         = true
                    settings.domStorageEnabled          = true
                    settings.allowFileAccess            = true
                    settings.allowContentAccess         = true
                    // Allow WASM execution (required for Harper)
                    settings.allowFileAccessFromFileURLs = true
                    settings.allowUniversalAccessFromFileURLs = true

                    addJavascriptInterface(HarperJSInterface(), "Android")

                    // Capture JS console output so we can debug errors
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                            val level = when (msg.messageLevel()) {
                                ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                                ConsoleMessage.MessageLevel.WARNING -> "WARN"
                                else -> "LOG"
                            }
                            Log.d(TAG, "JS[$level] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                            return true
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            Log.d(TAG, "HTML page loaded. Waiting for WASM init…")
                        }

                        override fun onReceivedError(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError
                        ) {
                            Log.e(TAG, "WebView resource error: ${error.description} for ${request?.url}")
                        }
                    }

                    loadUrl("file:///android_asset/harper/engine.html")
                }
                Log.d(TAG, "Harper WebView created, loading engine…")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Harper WebView", e)
            }
        }
    }

    // ─── Public API ──────────────────────────────────────────────────
    suspend fun checkText(text: String): List<HarperLint> {
        if (!isEngineReady || webView == null) {
            Log.w(TAG, "Engine not ready yet, skipping lint")
            return emptyList()
        }

        val requestId = requestCounter.incrementAndGet()
        val deferred  = CompletableDeferred<List<HarperLint>>()
        requestMap[requestId] = deferred

        Handler(Looper.getMainLooper()).post {
            val safeText = gson.toJson(text)
            val js = """
                (function(){
                    try { window.lintText($requestId, $safeText); }
                    catch(e) { Android.onError($requestId, e.message); }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(js, null)
        }

        return try {
            deferred.await()
        } catch (e: Exception) {
            Log.e(TAG, "Error waiting for Harper result", e)
            emptyList()
        } finally {
            requestMap.remove(requestId)
        }
    }

    fun destroy() {
        Handler(Looper.getMainLooper()).post {
            webView?.destroy()
            webView = null
            isEngineReady = false
        }
    }

    // ─── JS ↔ Android Bridge ─────────────────────────────────────────
    private inner class HarperJSInterface {

        /** Called by JS once WASM is ready */
        @JavascriptInterface
        fun onEngineReady() {
            Log.d(TAG, "✅ Harper WASM engine is ready!")
            isEngineReady = true
        }

        /** Called if WASM fails to init */
        @JavascriptInterface
        fun onEngineError(message: String) {
            Log.e(TAG, "❌ Harper engine init failed: $message")
        }

        @JavascriptInterface
        fun onLintResult(requestId: Int, resultJson: String) {
            Log.d(TAG, "Lint result for #$requestId: $resultJson")
            try {
                val type = object : com.google.gson.reflect.TypeToken<List<HarperLint>>() {}.type
                val lints: List<HarperLint> = gson.fromJson(resultJson, type)
                requestMap[requestId]?.complete(lints)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing lint result", e)
                requestMap[requestId]?.complete(emptyList())
            }
        }

        @JavascriptInterface
        fun onError(requestId: Int, errorMessage: String) {
            Log.e(TAG, "JS Error for #$requestId: $errorMessage")
            requestMap[requestId]?.completeExceptionally(Exception(errorMessage))
        }
    }
}
