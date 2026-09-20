package com.aistudio.gnauri.bvpmlc

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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Foreground media service (contract §5.4, §8). Owns the [NativeAudioEngine]; MediaSession
 * callbacks drive the engine DIRECTLY. All the legacy keep-alive machinery (silent AudioTrack,
 * 10 s heartbeat, WebView.resumeTimers/onResume pings, UPDATE_PLAYBACK_STATE_ACTION receiver,
 * ACTION_*_FROM_SERVICE broadcasts) is DELETED — the native engine owns playback state.
 *
 * ## Engine lifetime
 * The engine is created in [onCreate] and exposed through a companion accessor [engine] so the
 * WebView's `NativeAudio` JS bridge (WebViewManager) and the file-picker (MainActivity) can reach
 * the same instance. The service is started at app launch and lives for the process; the accessor
 * is cleared in [onDestroy]. This mirrors the existing WebViewManager singleton pattern.
 *
 * ## FGS state machine (contract R1)
 *   - startForeground fires when a playback session STARTS (engine onState(true) — first play).
 *   - The service STAYS foreground through pause (no STOP_FOREGROUND_DETACH on pause).
 *   - stopForeground(+ notification removal) only on stopReset (engine reports playing=false via
 *     the STOP action path) or on service destroy.
 *   - Because pause and stopReset both surface as onState(false), we distinguish them: a plain
 *     pause keeps the notification (updated to a paused/resume state, stays foreground); a stopReset
 *     additionally demotes. onStop() from the MediaSession/notification is the demote trigger.
 */
class MediaForegroundService : Service() {

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var audioEngine: NativeAudioEngine
    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- audio focus ----
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false

    // ---- state mirror maintained from engine callbacks (for notification/MediaSession) ----
    @Volatile private var isPlaying = false
    private var startedForeground = false

    // ---- becoming-noisy receiver, registered only while playing ----
    private var noisyRegistered = false
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                audioEngine.pause()
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                audioEngine.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // pause, remember to auto-resume on GAIN (contract §8: no ducking)
                resumeOnFocusGain = isPlaying
                audioEngine.pause()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    audioEngine.play(-1.0)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MediaForegroundService::WakeLock"
        )

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        audioEngine = NativeAudioEngine(this)
        engine = audioEngine

        // First-launch schedule race: on API 33+ this service only starts after the
        // POST_NOTIFICATIONS permission result (MainActivity), but the WebView auto-loads the
        // default schedule immediately on page load, before the engine existed — that
        // loadSchedule call fell back to a no-op and the schedule was lost. Replay the last
        // schedule the WebView sent (if any) now that the engine is available. Safe to call here:
        // loadSchedule only enqueues onto the engine's command queue / decodes WAVs on this
        // (calling) thread — it does not require the render thread to be running yet.
        WebViewManager.lastScheduleJson?.let { audioEngine.loadSchedule(it) }

        // Engine callbacks arrive on the render thread; marshal service/UI work to the main thread
        // and push the JS mirror (contract §5.2). NativeAudioEvents does its own main-handler post.
        audioEngine.onState = { playing ->
            NativeAudioEvents.pushState(playing)
            mainHandler.post { onEngineState(playing) }
        }
        audioEngine.onTick = { elapsed, played, loopsLeft ->
            NativeAudioEvents.pushTick(elapsed, played, loopsLeft)
            mainHandler.post { updateMediaSessionState() }
        }

        mediaSession = MediaSession(this, "MediaForegroundService")
        mediaSession?.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = audioEngine.play(-1.0)
            override fun onPause() = audioEngine.pause()
            override fun onStop() {
                audioEngine.stopReset()
                mainHandler.post { demoteAndStop() }
            }
            override fun onSeekTo(pos: Long) = audioEngine.seekElapsed(pos / 1000.0)
        })
        mediaSession?.isActive = true

        createNotificationChannel()
    }

    override fun onDestroy() {
        unregisterNoisy()
        abandonFocus()
        releaseWakeLock()
        mediaSession?.release()
        audioEngine.release()
        engine = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> audioEngine.play(-1.0)
            ACTION_PAUSE -> audioEngine.pause()
            ACTION_STOP_SERVICE -> {
                audioEngine.stopReset()
                demoteAndStop()
                return START_NOT_STICKY
            }
            else -> {
                // Initial launch (null action). MainActivity uses startForegroundService() on API
                // 26+, which mandates a startForeground() call within ~5 s or the OS kills us — so
                // we promote immediately with the idle (paused) notification. This is a deliberate
                // deviation from contract §8 R1's literal "startForeground on first play": the OS
                // deadline forces early promotion. R1's INTENT is honored — we stay foreground for
                // the whole session and only demote on stopReset (see demoteAndStop).
                if (!startedForeground) updateNotification()
            }
        }
        return START_STICKY
    }

    // ============================================================================
    // Engine state transitions
    // ============================================================================

    private fun onEngineState(playing: Boolean) {
        val wasPlaying = isPlaying
        isPlaying = playing

        if (playing) {
            requestFocus()
            acquireWakeLock()
            registerNoisy()
            startForegroundSession()   // R1: startForeground when a session starts
        } else {
            // pause OR stopReset: release wake lock + noisy receiver, keep foreground (R1).
            releaseWakeLock()
            unregisterNoisy()
            // stay foreground; stopForeground only happens on explicit stopReset via demoteAndStop()
        }

        updateMediaSessionState()
        if (wasPlaying != playing) updateNotification()

        // A stopReset resets the engine to cursor 0 while playing=false; the MediaSession onStop
        // path calls stopReset() directly and we then demote. onEngineState(false) alone (a plain
        // pause) must NOT demote.
    }

    // ============================================================================
    // Foreground / notification
    // ============================================================================

    private fun startForegroundSession() {
        updateNotification()
    }

    private fun demoteAndStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        startedForeground = false
        stopSelf()
    }

    private fun updateMediaSessionState() {
        val positionMs = (audioEngine.elapsedSeconds * 1000.0).toLong()
        val durationMs = (audioEngine.durationSeconds * 1000.0).toLong()
        val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val playbackState = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                    PlaybackState.ACTION_SEEK_TO
            )
            .setState(state, positionMs, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)

        val metadata = MediaMetadata.Builder()
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
            .build()
        mediaSession?.setMetadata(metadata)
    }

    private fun updateNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val contentPending = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIntent = Intent(this, MediaForegroundService::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        }
        val playPausePending = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        val action = Notification.Action.Builder(
            Icon.createWithResource(this, playPauseIcon),
            playPauseTitle,
            playPausePending
        ).build()

        val notification = builder
            .setContentTitle("Gnauri")
            .setContentText(if (isPlaying) "Playing schedule" else "Paused")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(contentPending)
            .addAction(action)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0)
            )
            .setOngoing(isPlaying)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            startedForeground = true
        } catch (e: Exception) {
            // Android 12+ ForegroundServiceStartNotAllowedException (and any other failure).
            Log.e(TAG, "startForeground failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Playback Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // ============================================================================
    // Wake lock / audio focus / becoming-noisy
    // ============================================================================

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == false) {
            try { wakeLock?.acquire() } catch (e: Exception) { Log.e(TAG, "wakeLock.acquire", e) }
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (e: Exception) { Log.e(TAG, "wakeLock.release", e) }
        }
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try { unregisterReceiver(becomingNoisyReceiver) } catch (e: Exception) { Log.w(TAG, "unregisterNoisy", e) }
        noisyRegistered = false
    }

    companion object {
        private const val TAG = "MediaForegroundService"
        private const val CHANNEL_ID = "MediaServiceChannel"
        private const val NOTIF_ID = 1

        const val ACTION_PLAY = "ACTION_PLAY"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"

        /**
         * The single live [NativeAudioEngine], owned by the service, exposed for the WebView JS
         * bridge (WebViewManager) and the file-picker (MainActivity). Non-null while the service is
         * alive; null before onCreate / after onDestroy. Consumers null-check.
         */
        @Volatile
        var engine: NativeAudioEngine? = null
            private set
    }
}
