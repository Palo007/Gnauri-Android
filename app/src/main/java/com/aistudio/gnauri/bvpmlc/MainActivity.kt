package com.aistudio.gnauri.bvpmlc

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.net.toUri
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.aistudio.gnauri.bvpmlc.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  private val requestPermissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestPermission(),
  ) { isGranted: Boolean ->
    if (isGranted) {
      startMediaService()
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
    if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
      try {
        val intent =
          Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:$packageName".toUri()
          }
        startActivity(intent)
      } catch (e: Exception) {
        e.printStackTrace()
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

    // Restore previously picked WAV URIs so they are available for the engine.
    SessionManager.restoreWavs(this)

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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebApp(modifier: Modifier = Modifier) {
  val context = LocalContext.current

  val filePickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenMultipleDocuments(),
  ) { uris ->
    if (uris.isNotEmpty()) {
      // Contract §5.5: record every picked *.wav URI in WavRegistry BEFORE forwarding to the
      // WebView file-chooser callback, so native can decode them lazily on loadSchedule.
      for (uri in uris) {
        val name = queryDisplayName(context, uri)
        if ((name != null) && name.endsWith(".wav", ignoreCase = true)) {
          WavRegistry.register(context, uri)
        }
      }
      WebViewManager.fileChooserCallback?.onReceiveValue(uris.toTypedArray())
    } else {
      WebViewManager.fileChooserCallback?.onReceiveValue(null)
    }
    WebViewManager.fileChooserCallback = null
  }

  val createDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("*/*"),
  ) { uri ->
    if (uri != null) {
      if (WebViewManager.exportData != null) {
        try {
          context.contentResolver.openOutputStream(uri)?.use {
            it.write(WebViewManager.exportData!!.toByteArray())
          }
        } catch (e: Exception) {
          e.printStackTrace()
        }
      } else if (WebViewManager.exportBinaryData != null) {
        try {
          context.contentResolver.openOutputStream(uri)?.use {
            it.write(WebViewManager.exportBinaryData!!)
          }
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }
    }
    WebViewManager.exportData = null
    WebViewManager.exportBinaryData = null
  }

  DisposableEffect(context) {
    WebViewManager.filePickerLauncher = filePickerLauncher
    WebViewManager.createDocumentLauncher = createDocumentLauncher

    onDispose {
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

/** Query the OpenableColumns.DISPLAY_NAME for a picked URI (best effort; null on failure). */
private fun queryDisplayName(context: Context, uri: Uri): String? {
  return try {
    context.contentResolver.query(
      uri,
      arrayOf(OpenableColumns.DISPLAY_NAME),
      null, null, null
    )?.use { c ->
      if (c.moveToFirst()) {
        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (idx >= 0) c.getString(idx) else null
      } else null
    }
  } catch (_: Exception) {
    null
  }
}
