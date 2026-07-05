package com.example

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
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
    
    var filePickerLauncher: ActivityResultLauncher<Array<String>>? = null
    var createDocumentLauncher: ActivityResultLauncher<String>? = null

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
                    setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
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
                
                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun exportFile(xml: String, filename: String) {
                        exportData = xml
                        createDocumentLauncher?.launch(filename)
                    }
                    
                    @JavascriptInterface
                    fun playbackStateChanged(isPlaying: Boolean, positionSec: Float, durationSec: Float) {
                        val intent = Intent("UPDATE_PLAYBACK_STATE_ACTION").apply {
                            setPackage(appCtx.packageName)
                            putExtra("isPlaying", isPlaying)
                            putExtra("position", (positionSec * 1000).toLong())
                            putExtra("duration", (durationSec * 1000).toLong())
                        }
                        appCtx.sendBroadcast(intent)
                    }
                }, "AndroidBridge")
                
                loadUrl("https://appassets.androidplatform.net/assets/index.html")
            }
        }
        
        // Remove from parent if it already has one
        (webView?.parent as? ViewGroup)?.removeView(webView)
        
        return webView!!
    }
}
