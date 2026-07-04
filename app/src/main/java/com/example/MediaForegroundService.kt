package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class MediaForegroundService : Service() {
    private var mediaSession: MediaSession? = null
    private var isPlaying = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var position = 0L
    private var duration = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "UPDATE_PLAYBACK_STATE_ACTION") {
                val newIsPlaying = intent.getBooleanExtra("isPlaying", false)
                val wasPlaying = isPlaying
                isPlaying = newIsPlaying
                
                if (isPlaying) {
                    if (wakeLock?.isHeld == false) wakeLock?.acquire()
                } else {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                }
                
                position = intent.getLongExtra("position", 0L)
                duration = intent.getLongExtra("duration", 0L)
                
                updateMediaSessionState()
                
                if (wasPlaying != isPlaying) {
                    updateNotification()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MediaForegroundService::WakeLock")
        
        mediaSession = MediaSession(this, "MediaForegroundService")
        mediaSession?.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                sendBroadcast(Intent("ACTION_PLAY_FROM_SERVICE").apply { setPackage(packageName) })
            }
            override fun onPause() {
                sendBroadcast(Intent("ACTION_PAUSE_FROM_SERVICE").apply { setPackage(packageName) })
            }
            override fun onStop() {
                sendBroadcast(Intent("ACTION_PAUSE_FROM_SERVICE").apply { setPackage(packageName) })
            }
            override fun onSeekTo(pos: Long) {
                sendBroadcast(Intent("ACTION_SEEK_FROM_SERVICE").apply {
                    setPackage(packageName)
                    putExtra("position", pos)
                })
            }
        })
        mediaSession?.isActive = true

        val filter = IntentFilter("UPDATE_PLAYBACK_STATE_ACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        mediaSession?.release()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP_SERVICE" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            "ACTION_PLAY" -> {
                sendBroadcast(Intent("ACTION_PLAY_FROM_SERVICE").apply { setPackage(packageName) })
                return START_STICKY
            }
            "ACTION_PAUSE" -> {
                sendBroadcast(Intent("ACTION_PAUSE_FROM_SERVICE").apply { setPackage(packageName) })
                return START_STICKY
            }
        }
        
        if (intent?.action == null) {
            createNotificationChannel()
            updateMediaSessionState()
            updateNotification()
        }
        return START_STICKY
    }

    private fun updateMediaSessionState() {
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val playbackState = PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SEEK_TO)
            .setState(state, position, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
        
        val metadata = android.media.MediaMetadata.Builder()
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, duration)
            .build()
        mediaSession?.setMetadata(metadata)
    }

    private fun updateNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = Intent(this, MediaForegroundService::class.java).apply {
            action = if (isPlaying) "ACTION_PAUSE" else "ACTION_PLAY"
        }
        val playPausePendingIntent = PendingIntent.getService(
            this,
            1,
            playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "MediaServiceChannel")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"

        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Notification.Action.Builder(
                Icon.createWithResource(this, playPauseIcon),
                playPauseTitle,
                playPausePendingIntent
            ).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Action(playPauseIcon, playPauseTitle, playPausePendingIntent)
        }

        val notification = builder
            .setContentTitle("Gnauri")
            .setContentText(if (isPlaying) "Playing schedule" else "Paused")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(action)
            .setStyle(Notification.MediaStyle()
                .setMediaSession(mediaSession?.sessionToken)
                .setShowActionsInCompactView(0))
            .setOngoing(isPlaying)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, notification)
        }
        
        if (!isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                "MediaServiceChannel",
                "Media Playback Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
