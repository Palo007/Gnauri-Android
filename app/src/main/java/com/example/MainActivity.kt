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
    
    val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
        try {
          val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
          }
          startActivity(intent)
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }
    }

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
  
  val filePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenMultipleDocuments()
  ) { uris ->
    if (uris != null && uris.isNotEmpty()) {
      WebViewManager.fileChooserCallback?.onReceiveValue(uris.toTypedArray())
    } else {
      WebViewManager.fileChooserCallback?.onReceiveValue(null)
    }
    WebViewManager.fileChooserCallback = null
  }

  val createDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("*/*")
  ) { uri ->
    if (uri != null && WebViewManager.exportData != null) {
      try {
        context.contentResolver.openOutputStream(uri)?.use {
          it.write(WebViewManager.exportData!!.toByteArray())
        }
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
    WebViewManager.exportData = null
  }
  
  DisposableEffect(context) {
    WebViewManager.filePickerLauncher = filePickerLauncher
    WebViewManager.createDocumentLauncher = createDocumentLauncher
    
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
          "ACTION_PLAY_FROM_SERVICE" -> WebViewManager.webView?.evaluateJavascript("if(window.AndroidPlayerControl) window.AndroidPlayerControl.play();", null)
          "ACTION_PAUSE_FROM_SERVICE" -> WebViewManager.webView?.evaluateJavascript("if(window.AndroidPlayerControl) window.AndroidPlayerControl.pause();", null)
          "ACTION_SEEK_FROM_SERVICE" -> {
              val position = intent.getLongExtra("position", 0L)
              WebViewManager.webView?.evaluateJavascript("if(window.AndroidPlayerControl) window.AndroidPlayerControl.seek(${position / 1000f});", null)
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
      WebViewManager.filePickerLauncher = null
      WebViewManager.createDocumentLauncher = null
    }
  }

  AndroidView(
    modifier = modifier,
    factory = { ctx ->
      WebViewManager.getWebView(ctx)
    }
  )
}
