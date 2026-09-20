package com.aistudio.gnauri.bvpmlc

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.result.ActivityResultLauncher

@SuppressLint("SetJavaScriptEnabled", "StaticFieldLeak")
object WebViewManager {
    var webView: WebView? = null
    private var contextWrapper: MutableContextWrapper? = null
    var fileChooserCallback: android.webkit.ValueCallback<Array<Uri>>? = null
    var exportData: String? = null
    var exportBinaryData: ByteArray? = null

    var filePickerLauncher: ActivityResultLauncher<Array<String>>? = null
    var createDocumentLauncher: ActivityResultLauncher<String>? = null

    // First-launch schedule race (see MediaForegroundService.onCreate): the WebView can call
    // loadSchedule (auto-loading the default schedule) before MediaForegroundService/engine exists
    // on API 33+, where the service only starts after the POST_NOTIFICATIONS permission result.
    // Cache the last schedule JSON here so the service can replay it once the engine is ready.
    @Volatile
    var lastScheduleJson: String? = null

    fun getWebView(context: Context): WebView {
        val appCtx = context.applicationContext
        if (contextWrapper == null) {
            contextWrapper = MutableContextWrapper(context)
        } else {
            contextWrapper?.setBaseContext(context)
        }
        
        if (webView == null) {
            webView = WebView(contextWrapper!!).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // false: never waive renderer priority when invisible (audio reliability; see docs/REVIEW_background-stop_and_native-audio-refactor.md)
                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
                }
                
                val assetLoader = androidx.webkit.WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(appCtx))
                    .build()
                    
                webViewClient = object : androidx.webkit.WebViewClientCompat() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: android.webkit.WebResourceRequest
                    ): android.webkit.WebResourceResponse? {
                        return assetLoader.shouldInterceptRequest(request.url)
                    }
                }
                
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        fileChooserCallback?.onReceiveValue(null)
                        fileChooserCallback = filePathCallback
                        
                        val mimeTypes = arrayOf("*/*")
                        try {
                            filePickerLauncher?.launch(mimeTypes)
                        } catch (e: Exception) {
                            fileChooserCallback?.onReceiveValue(null)
                            fileChooserCallback = null
                            return false
                        }
                        return true
                    }
                }
                
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    allowFileAccess = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
                
                // Legacy bridge: exportFile ONLY (contract §5.3; playbackStateChanged deleted).
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun exportFile(xml: String, filename: String) {
                        exportData = xml
                        createDocumentLauncher?.launch(filename)
                    }
                }, "AndroidBridge")

                // Native audio command surface (contract §5.1). These @JavascriptInterface methods
                // run on the WebView's JS-bridge thread; they forward to NativeAudioEngine, which is
                // itself thread-safe (commands are queued onto its render thread). Signatures match
                // the JS call sites in index.html EXACTLY.
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun loadSchedule(json: String): String {
                        // Always remember the latest schedule so it can be replayed if the engine
                        // isn't up yet (first-launch race, see MediaForegroundService.onCreate).
                        lastScheduleJson = json
                        return MediaForegroundService.engine?.loadSchedule(json) ?: "{\"missing\":[]}"
                    }

                    @JavascriptInterface
                    fun loadScheduleReset(json: String): String {
                        lastScheduleJson = json
                        return MediaForegroundService.engine?.loadScheduleReset(json) ?: "{\"missing\":[]}"
                    }

                    @JavascriptInterface
                    fun play(offsetSec: Double) { MediaForegroundService.engine?.play(offsetSec) }

                    @JavascriptInterface
                    fun pause() { MediaForegroundService.engine?.pause() }

                    @JavascriptInterface
                    fun stopReset() { MediaForegroundService.engine?.stopReset() }

                    @JavascriptInterface
                    fun seekElapsed(sec: Double) { MediaForegroundService.engine?.seekElapsed(sec) }

                    @JavascriptInterface
                    fun setMasterVolume(v: Double) { MediaForegroundService.engine?.setMasterVolume(v) }

                    @JavascriptInterface
                    fun setChannelGains(l: Double, r: Double) { MediaForegroundService.engine?.setChannelGains(l, r) }

                    @JavascriptInterface
                    fun setFadeOut30s(b: Boolean) { MediaForegroundService.engine?.setFadeOut30s(b) }

                    @JavascriptInterface
                    fun hasWav(name: String): Boolean =
                        MediaForegroundService.engine?.hasWav(name) ?: false

                    @JavascriptInterface
                    fun clearWavs() { MediaForegroundService.engine?.clearWavs() }

                    @JavascriptInterface
                    fun isAvailable(): Boolean = true

                    @JavascriptInterface
                    fun renderM4a(json: String, durationSec: Double, uiVol: Double, chL: Double, chR: Double, fadeOut30s: Boolean, filename: String) {
                        val ctx = webView?.context ?: return
                        com.aistudio.gnauri.bvpmlc.synth.M4aRenderer.render(
                            ctx, json, durationSec, uiVol, chL, chR, fadeOut30s, filename
                        )
                    }
                }, "NativeAudio")

                loadUrl("https://appassets.androidplatform.net/assets/index.html")
            }
        }
        
        // Remove from parent if it already has one
        (webView?.parent as? ViewGroup)?.removeView(webView)

        return webView!!
    }
}

/**
 * Kotlin → JS event pusher (contract §5.2, §8). The service/engine calls these to drive the JS
 * mirror via evaluateJavascript on the main handler. Null-safe: if the WebView is gone (activity
 * destroyed, page reloading) the calls are silently dropped — the engine keeps playing regardless.
 * The JS side (window.NativeAudioEvents) is itself a no-op when undefined.
 */
object NativeAudioEvents {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** window.NativeAudioEvents.onTick(elapsedSec, played, loopsLeft) — loopsLeft: -1 = infinite. */
    fun pushTick(elapsedSec: Double, played: Int, loopsLeft: Double) {
        mainHandler.post {
            val wv = WebViewManager.webView ?: return@post
            wv.evaluateJavascript(
                "if(window.NativeAudioEvents)window.NativeAudioEvents.onTick($elapsedSec,$played,$loopsLeft);",
                null
            )
        }
    }

    /** window.NativeAudioEvents.onState(isPlaying). */
    fun pushState(isPlaying: Boolean) {
        mainHandler.post {
            val wv = WebViewManager.webView ?: return@post
            wv.evaluateJavascript(
                "if(window.NativeAudioEvents)window.NativeAudioEvents.onState($isPlaying);",
                null
            )
        }
    }

    fun pushRenderProgress(progress: Double, isComplete: Boolean, error: String?) {
        mainHandler.post {
            val wv = WebViewManager.webView ?: return@post
            val errEscaped = error?.replace("'", "\\'")?.replace("\n", " ")
            wv.evaluateJavascript(
                "if(window.NativeAudioEvents && typeof window.NativeAudioEvents.onRenderProgress === 'function')window.NativeAudioEvents.onRenderProgress($progress,$isComplete,${if (errEscaped != null) "'$errEscaped'" else "null"});",
                null
            )
        }
    }
}
