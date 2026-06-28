package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  private val requestPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission()
  ) { isGranted: Boolean ->
    if (isGranted) {
      startMediaService()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      } else {
        startMediaService()
      }
    } else {
      startMediaService()
    }
    
    setContent {
      MyApplicationTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
          WebApp(modifier = Modifier.padding(innerPadding).fillMaxSize())
        }
      }
    }
  }

  private fun startMediaService() {
    val serviceIntent = Intent(this, MediaForegroundService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      startForegroundService(serviceIntent)
    } else {
      startService(serviceIntent)
    }
  }
}

@SuppressLint("SetJavaScriptEnabled", "UnspecifiedRegisterReceiverFlag")
@Composable
fun WebApp(modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val fileChooserCallback = remember { mutableStateOf<android.webkit.ValueCallback<Array<Uri>>?>(null) }
  val exportData = remember { mutableStateOf<String?>(null) }
  
  val filePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenMultipleDocuments()
  ) { uris ->
    if (uris != null && uris.isNotEmpty()) {
      fileChooserCallback.value?.onReceiveValue(uris.toTypedArray())
    } else {
      fileChooserCallback.value?.onReceiveValue(null)
    }
    fileChooserCallback.value = null
  }

  val createDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("*/*")
  ) { uri ->
    if (uri != null && exportData.value != null) {
      try {
        context.contentResolver.openOutputStream(uri)?.use {
          it.write(exportData.value!!.toByteArray())
        }
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
    exportData.value = null
  }

  // To hold a reference to the WebView so we can call evaluateJavascript on it
  val webViewRef = remember { mutableStateOf<WebView?>(null) }

  DisposableEffect(context) {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
          "ACTION_PLAY_FROM_SERVICE" -> webViewRef.value?.evaluateJavascript("if(window.AndroidPlayerControl) window.AndroidPlayerControl.play();", null)
          "ACTION_PAUSE_FROM_SERVICE" -> webViewRef.value?.evaluateJavascript("if(window.AndroidPlayerControl) window.AndroidPlayerControl.pause();", null)
          "ACTION_SEEK_FROM_SERVICE" -> {
              val position = intent.getLongExtra("position", 0L)
              webViewRef.value?.evaluateJavascript("if(window.AndroidPlayerControl) window.AndroidPlayerControl.seek(${position / 1000f});", null)
          }
        }
      }
    }
    val filter = IntentFilter().apply {
      addAction("ACTION_PLAY_FROM_SERVICE")
      addAction("ACTION_PAUSE_FROM_SERVICE")
      addAction("ACTION_SEEK_FROM_SERVICE")
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        context.registerReceiver(receiver, filter)
    }
    
    onDispose {
      context.unregisterReceiver(receiver)
    }
  }

  AndroidView(
    modifier = modifier,
    factory = { ctx ->
      WebView(ctx).apply {
        webViewRef.value = this
        layoutParams = ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
        
        val assetLoader = androidx.webkit.WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(ctx))
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
                fileChooserCallback.value?.onReceiveValue(null)
                fileChooserCallback.value = filePathCallback
                
                val mimeTypes = arrayOf("*/*")
                
                try {
                    filePickerLauncher.launch(mimeTypes)
                } catch (e: Exception) {
                    fileChooserCallback.value?.onReceiveValue(null)
                    fileChooserCallback.value = null
                    return false
                }
                return true
            }
        }
        
        settings.apply {
          javaScriptEnabled = true
          domStorageEnabled = true
          allowFileAccess = true
          mediaPlaybackRequiresUserGesture = false
          mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        addJavascriptInterface(object {
            @JavascriptInterface
            fun exportFile(xml: String, filename: String) {
                exportData.value = xml
                createDocumentLauncher.launch(filename)
            }

            @JavascriptInterface
            fun playbackStateChanged(isPlaying: Boolean, positionSec: Float, durationSec: Float) {
                val intent = Intent(ctx, MediaForegroundService::class.java).apply {
                    action = "UPDATE_PLAYBACK_STATE"
                    putExtra("isPlaying", isPlaying)
                    putExtra("position", (positionSec * 1000).toLong())
                    putExtra("duration", (durationSec * 1000).toLong())
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            }
        }, "AndroidBridge")
        
        loadUrl("https://appassets.androidplatform.net/assets/index.html")
      }
    }
  )
}
