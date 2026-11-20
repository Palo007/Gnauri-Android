package com.aistudio.gnauri.bvpmlc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import com.aistudio.gnauri.bvpmlc.synth.Schedule
import com.aistudio.gnauri.bvpmlc.synth.SynthEngine
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * Android driver around the pure [SynthEngine] (contract §8).
 *
 * ## Threading model (contract §8 "SynthEngine is not thread-safe — serialize commands and render
 * access")
 * A SINGLE dedicated render thread owns the [SynthEngine] instance exclusively. It is the ONLY
 * thread that ever touches the engine's mutable state or calls [SynthEngine.render]. Every public
 * command ([loadSchedule], [play], [pause], …) is wrapped as a [Runnable] and pushed onto a
 * lock-free [ConcurrentLinkedQueue]; the render thread drains the whole queue at the top of each
 * loop iteration, immediately before rendering the next buffer. Because commands and render() run
 * on the same thread there is no data race on the engine and no lock on the hot path — the queue is
 * the serialization point. Getters that other threads read ([elapsedSeconds]/[durationSeconds]/
 * [isPlaying]) are backed by @Volatile fields the render thread publishes each buffer, so the
 * MediaSession/notification can read a recent snapshot without touching the engine.
 *
 * When the engine reports not-playing AND the command queue is empty, the render thread parks with
 * [LockSupport.park] (it does NOT busy-spin); any command enqueue unparks it. The AudioTrack is
 * paused()+flush()ed around this park so no stale PCM keeps sounding.
 */
class NativeAudioEngine(context: Context) {

    private val appContext: Context = context.applicationContext

    // Output sample rate = device native rate for STREAM_MUSIC (contract §8).
    private val sampleRate: Int = run {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val native = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        if (native > 0) native else 48000
    }

    private val synth = SynthEngine(sampleRate)

    // ---- render buffer sizing (contract §8: buffer >= 4x getMinBufferSize) ----
    private val channelCount = 2
    private val bytesPerFrame = channelCount * 4 // stereo float32
    private val minBufBytes: Int = run {
        val m = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT
        )
        if (m > 0) m else sampleRate * bytesPerFrame / 2
    }
    private val trackBufBytes: Int = minBufBytes * 4
    // One PREALLOCATED render buffer (contract §8). Sized to ~1/8 of the track buffer in frames so
    // we write several times per track buffer for low latency without excessive syscalls.
    private val renderFrames: Int = (trackBufBytes / bytesPerFrame / 8).coerceAtLeast(256)
    private val floatBuf = FloatArray(renderFrames * channelCount)      // binaural (direct) bus
    private val floatBufFx = FloatArray(renderFrames * channelCount)    // everything else (fx) bus

    // ---- command queue (serialization point; drained on the render thread) ----
    private val commands = ConcurrentLinkedQueue<Runnable>()

    private val running = AtomicBoolean(false)
    private var renderThread: Thread? = null
    @Volatile private var track: AudioTrack? = null     // direct: binaural only, bypasses Dolby
    @Volatile private var trackFx: AudioTrack? = null   // fx: all other layers, normal effects path

    // ---- volatile snapshots published by the render thread for cross-thread getters ----
    @Volatile private var volElapsed: Double = 0.0
    @Volatile private var volDuration: Double = 0.0
    @Volatile private var volPlaying: Boolean = false
    @Volatile private var lastReportedPlaying: Boolean = false

    // ---- listeners (invoked on the render thread; consumers must marshal to their own thread) ----
    /** onTick(elapsedSec, played, loopsLeft) — loopsLeft = -1.0 for infinite. ~4 Hz while playing. */
    var onTick: ((Double, Int, Double) -> Unit)? = null

    /** onState(isPlaying) — fired on every play/pause/stop transition. */
    var onState: ((Boolean) -> Unit)? = null

    private var lastTickNs: Long = 0L
    private val tickIntervalNs = 250_000_000L // ~4 Hz

    // ============================================================================
    // Public command surface (called from JS-bridge / MediaSession / UI threads)
    // ============================================================================

    /**
     * Replace the schedule. Parses JSON, resolves type-2 WAVs via [WavRegistry], pushes decoded
     * buffers into the engine, then applies the schedule. Returns {"missing":[...]} JSON of type-2
     * voice descs with no decodable WAV. Decoding runs on the CALLING thread (JS-bridge thread) —
     * it is I/O heavy and must not block the render thread; only the final engine mutations are
     * marshalled onto the render thread.
     */
    fun loadSchedule(json: String): String {
        return loadScheduleInternal(json, reset = false)
    }

    fun loadScheduleReset(json: String): String {
        return loadScheduleInternal(json, reset = true)
    }

    private fun loadScheduleInternal(json: String, reset: Boolean): String {
        val schedule = Schedule.fromJson(json) ?: return "{\"missing\":[]}"
        val missing = ArrayList<String>()

        // Resolve & decode type-2 voices off the render thread (I/O).
        val buffers = ArrayList<Pair<String, FloatArray>>()
        for (v in schedule.voices) {
            if (v.type != 2) continue
            val candidates = buildList {
                if (v.desc.isNotBlank()) { add(v.desc); add(v.desc + ".wav") }
                v.file?.let { if (it.isNotBlank()) add(it) }
            }
            var decoded: FloatArray? = null
            var resolvedName: String? = null
            for (c in candidates) {
                val d = WavRegistry.decode(appContext, c, sampleRate)
                if (d != null) { decoded = d; resolvedName = c; break }
            }
            if (decoded != null && resolvedName != null) {
                buffers.add(resolvedName to decoded)
            } else {
                val nm = if (v.desc.isNotBlank()) v.desc else (v.file ?: "")
                if (nm.isNotBlank()) missing.add(nm)
            }
        }

        post {
            synth.clearAudioBuffers()
            for ((name, buf) in buffers) synth.setAudioBuffer(name, buf)
            if (reset) {
                synth.hardReset()
            }
            synth.setSchedule(schedule)
            updateScheduleDuration(schedule)
            publishSnapshot()
        }
        // If a schedule is loaded while playing, hot-apply keeps the thread running; if stopped the
        // thread stays parked until play(). No unpark needed for a pure schedule swap while stopped.
        wakeIfNeeded()

        return buildMissingJson(missing)
    }

    fun play(offsetSec: Double) {
        ensureThread()
        post {
            synth.play(offsetSec)
            publishSnapshot()
        }
        wakeIfNeeded()
    }

    fun pause() {
        post {
            synth.pause()
            publishSnapshot()
        }
        wakeIfNeeded()
    }

    fun stopReset() {
        post {
            synth.stopReset()
            publishSnapshot()
        }
        wakeIfNeeded()
    }

    fun seekElapsed(sec: Double) {
        post {
            synth.seekElapsed(sec)
            publishSnapshot()
        }
        wakeIfNeeded()
    }

    fun setMasterVolume(v: Double) = post { synth.setMasterVolume(v) }

    fun setChannelGains(l: Double, r: Double) = post { synth.setChannelGains(l, r) }

    fun setFadeOut30s(b: Boolean) = post { synth.setFadeOut30s(b) }

    /** hasWav is a registry query (no engine state) — answer directly, thread-safe in WavRegistry. */
    fun hasWav(name: String): Boolean = WavRegistry.hasWav(name)

    /** clearWavs empties the WAV registry (contract §5.1) and the engine's decoded buffers. */
    fun clearWavs() {
        WavRegistry.clear()
        post { synth.clearAudioBuffers() }
    }

    // ---- cross-thread getters (volatile snapshots; safe off the render thread) ----
    val isPlaying: Boolean get() = volPlaying
    val elapsedSeconds: Double get() = volElapsed

    /** Duration for the MediaSession: totalTime*loops (finite) or totalTime (infinite). */
    val durationSeconds: Double get() = volDuration

    // ============================================================================
    // Render thread lifecycle
    // ============================================================================

    private fun ensureThread() {
        if (running.get()) return
        synchronized(this) {
            if (running.get()) return
            running.set(true)
            val t = Thread({ renderLoop() }, "NativeAudioRender")
            t.isDaemon = true
            renderThread = t
            t.start()
        }
    }

    /** Enqueue a command for the render thread. */
    private fun post(cmd: Runnable) {
        commands.add(cmd)
    }

    private fun wakeIfNeeded() {
        renderThread?.let { LockSupport.unpark(it) }
    }

    private fun drainCommands() {
        while (true) {
            val c = commands.poll() ?: break
            try { c.run() } catch (e: Exception) { Log.e(TAG, "command failed", e) }
        }
    }

    private fun renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // Two tracks (see buildTrack): direct = binaural only, bypasses Dolby/spatializer;
        // fx = everything else, goes through the normal effects path (Dolby immersive sound).
        var at = buildTrack(direct = true)
        var atFx = buildTrack(direct = false)
        track = at
        trackFx = atFx

        var trackPlaying = false
        var lastUnderrun = 0

        try {
            while (running.get()) {
                drainCommands()

                if (synth.isPlaying) {
                    if (!trackPlaying) {
                        try { at.play() } catch (e: IllegalStateException) { Log.e(TAG, "track.play", e) }
                        try { atFx.play() } catch (e: IllegalStateException) { Log.e(TAG, "trackFx.play", e) }
                        trackPlaying = true
                    }

                    synth.render(floatBuf, floatBufFx, renderFrames)

                    val written = at.write(floatBuf, 0, renderFrames * channelCount, AudioTrack.WRITE_BLOCKING)
                    val writtenFx = atFx.write(floatBufFx, 0, renderFrames * channelCount, AudioTrack.WRITE_BLOCKING)
                    if (written < 0 || writtenFx < 0) {
                        // ERROR_DEAD_OBJECT (audio server restart / route change) etc.: rebuild BOTH tracks.
                        Log.e(TAG, "AudioTrack.write error: direct=$written fx=$writtenFx; rebuilding tracks")
                        try {
                            try { at.release() } catch (_: Exception) {}
                            try { atFx.release() } catch (_: Exception) {}
                            val nt = buildTrack(direct = true)
                            at = nt
                            track = nt
                            val ntFx = buildTrack(direct = false)
                            atFx = ntFx
                            trackFx = ntFx
                            trackPlaying = false // loop calls play() again on the new tracks
                            lastUnderrun = 0
                        } catch (e: Exception) {
                            Log.e(TAG, "track rebuild failed", e)
                        }
                        // back off so a repeated failure cannot spin the URGENT_AUDIO thread
                        LockSupport.parkNanos(50_000_000L)
                        continue
                    }

                    // underrun logging (API 24+)
                    val uc = at.underrunCount + atFx.underrunCount
                    if (uc != lastUnderrun) {
                        Log.w(TAG, "AudioTrack underruns (direct+fx): $uc")
                        lastUnderrun = uc
                    }

                    publishSnapshot()
                    maybeTick(force = false)
                    maybeState()
                } else {
                    // Not playing: stop both tracks cleanly and park until a command arrives.
                    if (trackPlaying) {
                        // stop() in MODE_STREAM plays out what is already buffered (the stop-fade tail);
                        // wait (bounded) for it to drain, then flush so the next play() starts clean.
                        try {
                            at.stop()
                            atFx.stop()
                            var last = at.playbackHeadPosition
                            var lastFx = atFx.playbackHeadPosition
                            var stable = 0
                            var waited = 0L
                            while (waited < 300_000_000L && stable < 3) {
                                LockSupport.parkNanos(5_000_000L)
                                waited += 5_000_000L
                                val h = at.playbackHeadPosition
                                val hFx = atFx.playbackHeadPosition
                                if (h == last && hFx == lastFx) stable++ else { stable = 0; last = h; lastFx = hFx }
                            }
                            at.flush()
                            atFx.flush()
                        } catch (e: IllegalStateException) { Log.e(TAG, "track.stop/flush", e) }
                        trackPlaying = false
                    }
                    publishSnapshot()
                    maybeState()
                    // Park (no busy-spin). Re-check the queue after unpark; a spurious wake just loops.
                    if (commands.isEmpty() && running.get()) {
                        LockSupport.park(this)
                    }
                }
            }
        } finally {
            try { at.pause() } catch (_: Exception) {}
            try { at.flush() } catch (_: Exception) {}
            try { at.release() } catch (_: Exception) {}
            try { atFx.pause() } catch (_: Exception) {}
            try { atFx.flush() } catch (_: Exception) {}
            try { atFx.release() } catch (_: Exception) {}
            track = null
            trackFx = null
        }
    }

    /**
     * Builds one of the two output tracks.
     *  - direct = true: BINAURAL bus. Must keep L and R fully separate, so it bypasses the vendor
     *    output effects (Dolby Atmos "immersive sound") via SPATIALIZATION_BEHAVIOR_NEVER plus
     *    PERFORMANCE_MODE_LOW_LATENCY (fast mixer skips the effect chain).
     *  - direct = false: FX bus (noise, isochronic, audio files). Default path, no spatialization
     *    opt-out and no performance mode, so Dolby immersive sound still enhances these layers.
     */
    private fun buildTrack(direct: Boolean): AudioTrack {
        val attrsBuilder = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        // Binaural beats need L and R kept fully separate. Ask the platform Spatializer to stay out.
        if (direct && Build.VERSION.SDK_INT >= 32) {
            attrsBuilder.setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER)
        }
        val attrs = attrsBuilder.build()
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .build()
        // AudioTrack.Builder is API 23+; minSdk is 24 so no legacy fallback needed.
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(trackBufBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
        // LOW_LATENCY routes the track to the fast mixer. That path skips the vendor output
        // effects (Dolby Atmos "immersive sound" on Xiaomi/POCO) which cross-mix L and R and
        // turn a binaural beat into an audible amplitude pulse. Direct (binaural) track only.
        if (direct && Build.VERSION.SDK_INT >= 26) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        val t = builder.build()
        if (Build.VERSION.SDK_INT >= 26) {
            // 1 = LOW_LATENCY granted; 0 = system fell back to the normal (effects) path.
            Log.i(TAG, "AudioTrack(${if (direct) "direct" else "fx"}) performanceMode=${t.performanceMode} rate=$sampleRate")
        }
        return t
    }

    // ============================================================================
    // Snapshot publishing + listeners (all on the render thread)
    // ============================================================================

    private fun publishSnapshot() {
        volElapsed = synth.elapsedSeconds
        volPlaying = synth.isPlaying
        volDuration = scheduleDuration
    }

    // Schedule duration (secs) for the MediaSession: totalTime*loops (finite) or totalTime
    // (infinite). Set on the render thread inside loadSchedule's posted command; volatile so the
    // MediaSession/notification thread can read a recent value.
    @Volatile private var scheduleDuration: Double = 0.0

    private fun maybeState() {
        val p = synth.isPlaying
        if (p != lastReportedPlaying) {
            lastReportedPlaying = p
            try { onState?.invoke(p) } catch (e: Exception) { Log.e(TAG, "onState", e) }
            // force an immediate tick on transitions so the UI/MediaSession jumps at once
            maybeTick(force = true)
        }
    }

    private fun maybeTick(force: Boolean) {
        val now = System.nanoTime()
        if (!force && now - lastTickNs < tickIntervalNs) return
        lastTickNs = now
        val loops = synth.loopsLeft
        val loopsForJs = if (loops.isInfinite()) -1.0 else loops
        try {
            onTick?.invoke(synth.elapsedSeconds, synth.played, loopsForJs)
        } catch (e: Exception) {
            Log.e(TAG, "onTick", e)
        }
    }

    private fun buildMissingJson(missing: List<String>): String {
        // Manual escaping (voice descs can contain quotes/backslashes). Moshi is present but a small
        // hand-built writer avoids reflective adapter setup for a trivial payload.
        val sb = StringBuilder("{\"missing\":[")
        for ((i, m) in missing.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append('"')
            for (c in m) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Called by loadSchedule's posted command to cache the schedule duration for the MediaSession. */
    private fun updateScheduleDuration(schedule: Schedule) {
        scheduleDuration = if (schedule.loops <= 0) schedule.totalTime
        else schedule.totalTime * schedule.loops
    }

    fun release() {
        running.set(false)
        wakeIfNeeded()
        renderThread = null
    }

    companion object {
        private const val TAG = "NativeAudioEngine"
    }
}
