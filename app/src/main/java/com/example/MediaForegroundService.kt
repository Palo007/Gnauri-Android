package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder

class MediaForegroundService : Service() {

    private var mediaSession: MediaSession? = null
    private var isPlaying = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
    }

    override fun onDestroy() {
        mediaSession?.release()
        super.onDestroy()
    }

    private var position = 0L
    private var duration = 0L

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
            "UPDATE_PLAYBACK_STATE" -> {
                isPlaying = intent.getBooleanExtra("isPlaying", false)
                position = intent.getLongExtra("position", 0L)
                duration = intent.getLongExtra("duration", 0L)
                updateMediaSessionState()
                updateNotification()
                return START_STICKY
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
