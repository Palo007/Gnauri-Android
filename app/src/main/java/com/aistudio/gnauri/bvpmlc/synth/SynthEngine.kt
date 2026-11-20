package com.aistudio.gnauri.bvpmlc.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure-DSP synthesis core (contract §3, §4). ZERO android.* dependencies — only kotlin stdlib,
 * java.*, and Moshi (in ScheduleModel). render() is the only hot path and allocates nothing;
 * every buffer/DSP object is preallocated at setSchedule()/play() time.
 *
 * Design: the engine renders sample-by-sample from an "active" playback [Instance] and, during a
 * loop seam, additionally from an "outgoing" [Instance] for a 0.18 s equal-power crossfade
 * (contract §4.7). Pause/stop/seek/hot-apply apply a 0.12 s linear stop-fade to whatever is
 * currently sounding (contract §4.8). The final loop pass optionally fades the whole mix over the
 * last min(30, totalTime) s (contract §4.9, index.html:1141-1164).
 */
class SynthEngine(val sampleRate: Int) {

    // ---- constants (index.html:768-769) ----
    private val CROSSFADE = 0.18            // 180 ms loop crossfade
    private val STOP_FADE = 0.12            // 120 ms hard-stop/pause/seek fade

    // ---- schedule ----
    private var schedule: Schedule? = null
    private var segsPerVoice: Array<Segments> = emptyArray()
    private var totalTime: Double = 0.0

    // ---- transport state ----
    private var playing: Boolean = false
    var played: Int = 0
        private set
    private var cursor: Double = 0.0        // seconds within the current pass
    var loopsLeft: Double = Double.POSITIVE_INFINITY
        private set

    // ---- gains ----
    private var uiVol: Double = 1.0
    private var chL: Double = 1.0
    private var chR: Double = 1.0
    private var fadeOut30s: Boolean = true  // default true (contract §3, index.html:750)

    // ---- audio buffers for type-2 voices, keyed by normalized name ----
    private val audioBuffers = HashMap<String, FloatArray>()

    // ---- playback instances ----
    // Two-slot pool preallocated for the current schedule (contract "NO allocation in render"): at a
    // loop seam we swap the roles of these two and reset the incoming one — no `new` on the hot path.
    // Rebuilt only off the hot path (setSchedule/play/seek/hot-apply). `active` and `outgoing` point
    // into slot0/slot1; a boolean picks which slot is currently active.
    private var slot0: Instance? = null
    private var slot1: Instance? = null
    private var activeSlot: Int = 0
    private var active: Instance? = null
    private var outgoing: Instance? = null  // outgoing loop tail during a crossfade

    // crossfade progress within the active seam, in seconds (0..CROSSFADE); NaN when not crossfading
    private var xfadePos: Double = Double.NaN

    // stop-fade progress: when >= 0, the whole mix is ramping to 0 over STOP_FADE; on reaching
    // STOP_FADE the engine finalizes the pending transport action (pause/stop/seek/hot-apply).
    private var stopFadePos: Double = -1.0
    private var stopFadeAction: Int = ACTION_NONE

    // deferred target for seek/hot-apply that starts AFTER the stop-fade completes
    private var pendingSeekElapsed: Double = 0.0
    private var pendingSchedule: Schedule? = null

    private val sr: Double = sampleRate.toDouble()
    private val samplePeriod: Double = 1.0 / sr

    // ============================================================================================
    // Public API — EXACTLY contract §3
    // ============================================================================================

    fun setSchedule(s: Schedule) {
        if (playing) {
            // Hot-apply (contract §5.6): capture elapsed, stop-fade the old state, resume the new
            // schedule at the same elapsed position (clamped). We defer the swap until the fade ends.
            pendingSchedule = s
            pendingSeekElapsed = elapsedSeconds
            beginStopFade(ACTION_HOTAPPLY)
        } else {
            installSchedule(s)
            // keep cursor clamped (contract §5.6 "while stopped ⇒ replace, keep cursor clamped")
            cursor = clampCursor(cursor)
        }
    }

    fun play(offsetSec: Double) {
        val sched = schedule ?: return
        if (offsetSec < 0.0) {
            // resume from current cursor (contract §3)
            // nothing to change on cursor/played
        } else {
            // absolute offset within one pass; played stays as-is unless this is a fresh (0) start
            cursor = clampCursor(offsetSec)
            if (offsetSec == 0.0) {
                // fresh start of the whole timeline: reset pass counter (index.html:945)
                played = 0
            }
        }
        loopsLeft = recomputeLoopsLeft()
        // build the pool (off hot path) and start a fresh active instance at the current cursor
        if (slot0 == null || slot1 == null) buildPool(sched)
        startActiveAt(cursor)
        xfadePos = Double.NaN
        stopFadePos = -1.0
        stopFadeAction = ACTION_NONE
        playing = true
    }

    fun pause() {
        if (!playing) return
        beginStopFade(ACTION_PAUSE)
    }

    fun stopReset() {
        if (!playing) {
            cursor = 0.0
            played = 0
            return
        }
        beginStopFade(ACTION_STOPRESET)
    }

    fun hardReset() {
        playing = false
        cursor = 0.0
        played = 0
        active = null
        outgoing = null
        xfadePos = Double.NaN
        stopFadePos = -1.0
        stopFadeAction = ACTION_NONE
        pendingSchedule = null
    }

    fun seekElapsed(sec: Double) {
        val sched = schedule ?: return
        val clamped = clampElapsed(sec)
        if (!playing) {
            applyElapsed(clamped)
            return
        }
        pendingSeekElapsed = clamped
        beginStopFade(ACTION_SEEK)
    }

    /**
     * Render [frames] interleaved stereo frames into [out] (length must be >= frames*2).
     * ONLY hot path — no allocation, no boxing, no string ops. (contract §3, §4)
     */
    fun render(out: FloatArray, frames: Int) {
        renderCore(out, null, frames)
    }

    /**
     * Split-bus render: [outDirect] gets ONLY the binaural voices (played on a track that bypasses
     * the vendor spatializer / Dolby so L/R stay separate); [outFx] gets everything else (noise,
     * isochronic, audio files) for the normal effects path. Every gain stage is applied identically
     * to both buses, so direct + fx == the single-bus render(out, frames) (barring clipping).
     */
    fun render(outDirect: FloatArray, outFx: FloatArray, frames: Int) {
        renderCore(outDirect, outFx, frames)
    }

    /** If [outFx] is null: single bus (total mix) into [out]. Else: out = binaural bus, outFx = rest. */
    private fun renderCore(out: FloatArray, outFx: FloatArray?, frames: Int) {
        // silence first so early-return / muted paths are safe
        val n = frames * 2
        var i = 0
        while (i < n) {
            out[i] = 0f
            if (outFx != null) outFx[i] = 0f
            i++
        }
        val sched = schedule ?: return
        if (active == null) return
        if (!playing && stopFadePos < 0.0) return

        val ovMaster = uiVol * ((sched.ovL + sched.ovR) / 2.0) // master (contract §4.6, index.html:872-876)
        val trimL = clampTrim(chL)
        val trimR = clampTrim(chR)

        var f = 0
        while (f < frames) {
            var l = 0.0 // total mix
            var r = 0.0
            var bl = 0.0 // binaural-only part
            var br = 0.0

            // Read the active/outgoing slots EACH sample: a seek/hot-apply finalize (§4.8) or an
            // end-of-schedule can swap or clear them mid-buffer via advanceTransport().
            val act = active
            if (act != null) {
                // ---- active instance ----
                var busGain = 1.0
                if (!xfadePos.isNaN()) {
                    // incoming (active) equal-power fade-in during a crossfade (index.html:792-794)
                    val x = (xfadePos / CROSSFADE).coerceIn(0.0, 1.0)
                    busGain = sin(x * PI / 2.0)
                }
                act.renderSample()
                l += act.outL * busGain
                r += act.outR * busGain
                bl += act.binL * busGain
                br += act.binR * busGain

                // ---- outgoing instance (loop tail) ----
                val out2 = outgoing
                if (out2 != null && !xfadePos.isNaN()) {
                    val x = (xfadePos / CROSSFADE).coerceIn(0.0, 1.0)
                    val downGain = cos(x * PI / 2.0) // equal-power fade-out (index.html:794)
                    out2.renderSample()
                    l += out2.outL * downGain
                    r += out2.outR * downGain
                    bl += out2.binL * downGain
                    br += out2.binR * downGain
                }
            }

            // ---- fadeOut30s on the final pass (contract §4.9, index.html:1141-1164) ----
            var gFade = 1.0
            if (fadeOut30s && loopsLeft <= 1.0) {
                val fadeDuration = min(30.0, totalTime)
                val fadeStart = max(0.0, totalTime - fadeDuration)
                if (cursor >= fadeStart && fadeDuration > 0.0) {
                    gFade = (1.0 - (cursor - fadeStart) / fadeDuration).coerceIn(0.0, 1.0)
                }
            }

            // ---- stop-fade linear ramp to zero (contract §4.8) ----
            var gStop = 1.0
            if (stopFadePos >= 0.0) {
                gStop = (1.0 - (stopFadePos / STOP_FADE)).coerceIn(0.0, 1.0)
            }

            // ---- fade, master + per-channel trims (contract §4.6), stop-fade: identical on both buses ----
            val gl = gFade * ovMaster * trimL * gStop
            val gr = gFade * ovMaster * trimR * gStop

            // ---- final clamp; never NaN/Inf (contract §4.11) ----
            if (outFx == null) {
                out[f * 2] = sanitize(l * gl)
                out[f * 2 + 1] = sanitize(r * gr)
            } else {
                out[f * 2] = sanitize(bl * gl)
                out[f * 2 + 1] = sanitize(br * gr)
                outFx[f * 2] = sanitize((l - bl) * gl)
                outFx[f * 2 + 1] = sanitize((r - br) * gr)
            }

            advanceTransport()
            f++
        }
    }

    fun setMasterVolume(v: Double) {
        uiVol = v // master = v * ((ovL+ovR)/2) applied in render (index.html:872-876)
    }

    fun setChannelGains(l: Double, r: Double) {
        // clamp [0, 1.5], applied post-master, no ramp (index.html:844-848)
        chL = clampTrim(l)
        chR = clampTrim(r)
    }

    fun setFadeOut30s(b: Boolean) {
        fadeOut30s = b
    }

    fun setAudioBuffer(name: String, mono: FloatArray) {
        audioBuffers[normName(name)] = mono
    }

    fun clearAudioBuffers() {
        audioBuffers.clear()
    }

    fun hasBuffer(name: String): Boolean {
        val nn = normName(name)
        // lookup normName(name), then name+".wav" (index.html:810-814)
        return audioBuffers.containsKey(nn) || audioBuffers.containsKey("$nn.wav")
    }

    val isPlaying: Boolean
        get() = playing

    /**
     * elapsedSeconds = played*totalTime + cursor for ALL loop modes (contract §3).
     * Deliberately does NOT replicate the JS infinite-loop bug (index.html:781-787) which returned
     * only `t` when loops==0.
     */
    val elapsedSeconds: Double
        get() = played * totalTime + cursor

    // ============================================================================================
    // Internal helpers
    // ============================================================================================

    private fun installSchedule(s: Schedule) {
        schedule = s
        totalTime = s.totalTime
        segsPerVoice = Array(s.voices.size) { Segments(s.voices[it]) }
        loopsLeft = recomputeLoopsLeft()
        // discard any live instances built against the old schedule and rebuild the pool (off hot path)
        active = null
        outgoing = null
        xfadePos = Double.NaN
        buildPool(s)
    }

    /** loops = 0 => infinite; N>0 => N passes total. loopsLeft counts remaining incl. current. */
    private fun recomputeLoopsLeft(): Double {
        val s = schedule ?: return Double.POSITIVE_INFINITY
        return if (s.loops == 0) Double.POSITIVE_INFINITY
        else max(0.0, s.loops.toDouble() - played.toDouble())
    }

    private fun clampCursor(c: Double): Double =
        if (totalTime <= 0.0) 0.0 else c.coerceIn(0.0, totalTime)

    private fun clampElapsed(sec: Double): Double {
        val s = schedule ?: return 0.0
        val maxElapsed = if (s.loops == 0) Double.MAX_VALUE else s.loops.toDouble() * totalTime
        return sec.coerceIn(0.0, maxElapsed)
    }

    /** Map a full-timeline elapsed value to (played, cursor). */
    private fun applyElapsed(elapsed: Double) {
        if (totalTime <= 0.0) {
            played = 0
            cursor = 0.0
        } else {
            val e = max(0.0, elapsed)
            played = (e / totalTime).toInt()
            cursor = e - played * totalTime
            // guard against fp landing exactly on a boundary
            if (cursor >= totalTime) {
                played += 1
                cursor -= totalTime
            }
        }
        loopsLeft = recomputeLoopsLeft()
    }

    private fun beginStopFade(action: Int) {
        if (stopFadePos >= 0.0) {
            // Fade already running: do NOT restart it (gain would snap back to 1.0 = click).
            // Priority: pause/stop > hot-apply > seek. A pending pause/stop is never replaced; a
            // pending hot-apply is not downgraded to a seek (its pendingSeekElapsed is reused).
            val cur = stopFadeAction
            val keep = cur == ACTION_PAUSE || cur == ACTION_STOPRESET ||
                (cur == ACTION_HOTAPPLY && action == ACTION_SEEK)
            if (!keep) stopFadeAction = action
            return
        }
        stopFadePos = 0.0
        stopFadeAction = action
    }

    /** Called after a stop-fade completes to finalize the pending transport transition. */
    private fun finalizeStopFade() {
        val action = stopFadeAction
        stopFadePos = -1.0
        stopFadeAction = ACTION_NONE
        outgoing = null
        xfadePos = Double.NaN
        when (action) {
            ACTION_PAUSE -> {
                playing = false // keep cursor & played (contract §3)
                active = null
                // a hot-apply issued during the pause fade must not be lost (stopped ⇒ replace)
                pendingSchedule?.let { installSchedule(it); cursor = clampCursor(cursor) }
                pendingSchedule = null
            }
            ACTION_STOPRESET -> {
                playing = false
                cursor = 0.0
                played = 0
                active = null
                pendingSchedule?.let { installSchedule(it); cursor = clampCursor(cursor) }
                pendingSchedule = null
            }
            ACTION_SEEK -> {
                // resume playing at the new position with a fresh instance (pool reused)
                active = null
                applyElapsed(pendingSeekElapsed)
                if (schedule != null) {
                    startActiveAt(cursor)
                    playing = true
                } else {
                    playing = false
                }
            }
            ACTION_HOTAPPLY -> {
                active = null
                val ns = pendingSchedule
                pendingSchedule = null
                if (ns != null) {
                    installSchedule(ns) // rebuilds pool for the new schedule
                    applyElapsed(pendingSeekElapsed) // resume at same elapsed, clamped
                    startActiveAt(cursor)
                    playing = true
                } else {
                    playing = false
                }
            }
            else -> { /* ACTION_NONE */ }
        }
    }

    /**
     * Advance transport by one sample: cursor forward, handle loop seams (crossfade start), stop-fade
     * progress, and end-of-schedule. Called once per rendered frame.
     */
    private fun advanceTransport() {
        // stop-fade takes priority: while fading, keep advancing cursor so the tail keeps sounding
        if (stopFadePos >= 0.0) {
            stopFadePos += samplePeriod
            // also advance the fading instances so their oscillators keep moving (no frozen tone)
            cursor += samplePeriod
            if (!xfadePos.isNaN()) xfadePos += samplePeriod
            if (stopFadePos >= STOP_FADE) {
                finalizeStopFade()
            }
            return
        }

        if (!playing) return

        cursor += samplePeriod

        // crossfade progress
        if (!xfadePos.isNaN()) {
            xfadePos += samplePeriod
            if (xfadePos >= CROSSFADE) {
                // crossfade done: drop the outgoing tail (pooled slot is freed for the next seam)
                outgoing = null
                xfadePos = Double.NaN
            }
        }

        // Loop seam: when the active instance's cursor passes totalTime, either wrap to the next
        // pass (with crossfade) or end the schedule (contract §4.7, §4.10).
        if (totalTime > 0.0 && cursor >= totalTime) {
            val remainingAfterThis = loopsLeft - 1.0 // passes left after finishing the current one
            if (remainingAfterThis > 0.0) {
                // Start the next pass with NO allocation: the just-finished active becomes the
                // outgoing tail (keeps its DSP state, keeps rendering past totalTime), and the OTHER
                // pooled slot becomes the fresh incoming active, reset to cursor 0 via start()
                // (fresh-instance semantics — contract §4.7). Only the two pooled instances are ever
                // used, so no `new` happens on the hot path.
                cursor -= totalTime
                played += 1
                loopsLeft = recomputeLoopsLeft()
                outgoing = active
                activeSlot = 1 - activeSlot
                val incoming = if (activeSlot == 0) slot0 else slot1
                incoming?.start(cursor)
                active = incoming
                xfadePos = 0.0
            } else {
                // finite loops exhausted: stop, cursor=totalTime (contract §4.10)
                playing = false
                cursor = totalTime
                active = null
                outgoing = null
                xfadePos = Double.NaN
            }
        }
    }

    /** Build the two-slot instance pool for [sched]. Off the hot path only. */
    private fun buildPool(sched: Schedule) {
        slot0 = Instance(sched, segsPerVoice, audioBuffers, sampleRate)
        slot1 = Instance(sched, segsPerVoice, audioBuffers, sampleRate)
    }

    /** Start playback: make slot0 the active instance at [startSec], clear outgoing. Off hot path. */
    private fun startActiveAt(startSec: Double) {
        activeSlot = 0
        active = slot0
        outgoing = null
        active?.start(startSec)
    }

    private fun sanitize(v: Double): Float {
        if (v.isNaN() || v.isInfinite()) return 0f
        val c = v.coerceIn(-1.0, 1.0)
        return c.toFloat()
    }

    private fun clampTrim(v: Double): Double =
        if (v.isFinite()) v.coerceIn(0.0, 1.5) else 1.0 // clamp [0,1.5] (index.html:845)

    /** normName(s): lowercase, strip path, trim (index.html:804). */
    fun normName(s: String): String {
        val trimmed = s.trim().lowercase()
        val slash = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
        return if (slash >= 0) trimmed.substring(slash + 1) else trimmed
    }

    companion object {
        private const val ACTION_NONE = 0
        private const val ACTION_PAUSE = 1
        private const val ACTION_STOPRESET = 2
        private const val ACTION_SEEK = 3
        private const val ACTION_HOTAPPLY = 4
    }
}

/**
 * One playback instance = a full set of per-voice DSP state, rendered from a private cursor. The
 * engine keeps a two-slot pool of these (allocated off the hot path); at each loop seam it swaps
 * roles and calls [start] on the incoming slot to get fresh-instance semantics (reset phases, noise
 * state, WAV position — contract §4.7) with NO allocation in render.
 *
 * renderSample() writes its stereo result into outL/outR; it reads the schedule time from its own
 * [posSec] which advances one sample per call, independent of the engine cursor. This lets the
 * outgoing loop-tail instance keep advancing past totalTime during a crossfade.
 */
internal class Instance(
    private val schedule: Schedule,
    private val segs: Array<Segments>,
    private val audioBuffers: HashMap<String, FloatArray>,
    private val sampleRate: Int
) {
    @JvmField var outL: Double = 0.0
    @JvmField var outR: Double = 0.0
    /** Binaural-voices-only part of outL/outR (outL - binL = everything else). Used for the split bus. */
    @JvmField var binL: Double = 0.0
    @JvmField var binR: Double = 0.0

    private val sr: Double = sampleRate.toDouble()
    private val samplePeriod: Double = 1.0 / sr

    private var posSec: Double = 0.0
    private val stereoswap: Boolean = schedule.stereoswap
    private val voices: List<Voice> = schedule.voices

    private val nVoices = voices.size

    // per-voice DSP objects (null for voices that render nothing / muted)
    private val oscL = arrayOfNulls<Oscillator>(nVoices)
    private val oscR = arrayOfNulls<Oscillator>(nVoices)
    private val oscC = arrayOfNulls<Oscillator>(nVoices)   // isochronic carrier
    private val pink = arrayOfNulls<PinkNoise>(nVoices)
    // white/brown use TWO independent generators (L & R) for a decorrelated stereo field, matching
    // _generateWhiteBuffer/_generateBrownBuffer which fill 2 channels independently (index.html:901-903,
    // 916-918). Pink is mono (index.html:880) so it uses one generator sent to both ears.
    private val whiteL = arrayOfNulls<WhiteNoise>(nVoices)
    private val whiteR = arrayOfNulls<WhiteNoise>(nVoices)
    private val brownL = arrayOfNulls<BrownNoise>(nVoices)
    private val brownR = arrayOfNulls<BrownNoise>(nVoices)
    private val biquad = arrayOfNulls<Biquad>(nVoices)
    private val gate = arrayOfNulls<IsoGate>(nVoices)

    // type-2 audio buffers resolved once per instance (channel 0, pre-resampled) and their state
    private val wavBuf = arrayOfNulls<FloatArray>(nVoices)
    private val wavPos = DoubleArray(nVoices)              // fractional read position, seconds*sr

    // resolved kind per voice (see KIND_* constants)
    private val kind = IntArray(nVoices)
    private val pinkHasBand = BooleanArray(nVoices)

    init {
        var seedCounter = 1L
        for (vi in 0 until nVoices) {
            val v = voices[vi]
            if (v.muted || segs[vi].count == 0) { // muted voices skipped (contract §4.6, index.html:966)
                kind[vi] = KIND_SILENT
                continue
            }
            when (v.type) {
                0 -> { // binaural
                    kind[vi] = KIND_BINAURAL
                    oscL[vi] = Oscillator(sampleRate)
                    oscR[vi] = Oscillator(sampleRate)
                }
                2 -> { // audio-file
                    // channel 0 of the WAV only (contract §4.5, index.html:983-986); lookup by desc,
                    // then file. Missing buffer => silent skip (index.html:977).
                    val buf = lookupBuffer(v.desc) ?: (v.file?.let { lookupBuffer(it) })
                    if (buf == null) {
                        kind[vi] = KIND_SILENT
                    } else {
                        kind[vi] = KIND_AUDIO
                        wavBuf[vi] = buf
                    }
                }
                3, 4 -> { // isochronic (3 smooth, 4 gapped)
                    kind[vi] = KIND_ISO
                    oscC[vi] = Oscillator(sampleRate)
                    gate[vi] = IsoGate(sampleRate, gapped = (v.type == 4))
                }
                5 -> { // white noise (decorrelated stereo, index.html:901-903)
                    kind[vi] = KIND_WHITE
                    whiteL[vi] = WhiteNoise(seedCounter++)
                    whiteR[vi] = WhiteNoise(seedCounter++)
                }
                6 -> { // brown noise (decorrelated stereo, index.html:916-918)
                    kind[vi] = KIND_BROWN
                    brownL[vi] = BrownNoise(seedCounter++)
                    brownR[vi] = BrownNoise(seedCounter++)
                }
                1 -> { // pink noise
                    kind[vi] = KIND_PINK
                    pink[vi] = PinkNoise(seedCounter++)
                    // bandpass only if any segment base>1 (index.html:1032)
                    pinkHasBand[vi] = segs[vi].anyBaseAboveOne
                    if (pinkHasBand[vi]) biquad[vi] = Biquad(sampleRate)
                }
                else -> { // unknown -> pink fallback (contract §4.4, index.html:1002-1006)
                    kind[vi] = KIND_PINK
                    pink[vi] = PinkNoise(seedCounter++)
                    pinkHasBand[vi] = segs[vi].anyBaseAboveOne
                    if (pinkHasBand[vi]) biquad[vi] = Biquad(sampleRate)
                }
            }
        }
    }

    private fun lookupBuffer(desc: String): FloatArray? {
        val n = normName(desc)
        audioBuffers[n]?.let { return it }
        audioBuffers["$n.wav"]?.let { return it }
        return null
    }

    private fun normName(s: String): String {
        val trimmed = s.trim().lowercase()
        val slash = maxOf(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'))
        return if (slash >= 0) trimmed.substring(slash + 1) else trimmed
    }

    /** Reset all DSP state to a fresh instance at absolute schedule time [startSec]. */
    fun start(startSec: Double) {
        posSec = startSec
        for (vi in 0 until nVoices) {
            oscL[vi]?.reset(); oscR[vi]?.reset(); oscC[vi]?.reset()
            pink[vi]?.reset()
            whiteL[vi]?.reset(); whiteR[vi]?.reset()
            brownL[vi]?.reset(); brownR[vi]?.reset()
            biquad[vi]?.reset(); gate[vi]?.reset()
            wavPos[vi] = 0.0 // WAV restarts from 0 at every instance start (contract §4.5, index.html:991)
        }
    }

    /**
     * Render one stereo sample for this instance into outL/outR, then advance posSec.
     * No allocation. Reads schedule params via Segments.sample at posSec.
     */
    fun renderSample() {
        var l = 0.0
        var r = 0.0
        var bl = 0.0
        var br = 0.0
        val t = posSec

        for (vi in 0 until nVoices) {
            val k = kind[vi]
            if (k == KIND_SILENT) continue
            val sg = segs[vi]
            if (!sg.sample(t)) continue
            val volL = max(0.0, sg.outVolL) // param values clamped >=0 (index.html:1187)
            val volR = max(0.0, sg.outVolR)
            val base = sg.outBase
            val beat = sg.outBeat

            when (k) {
                KIND_BINAURAL -> {
                    // fL = base - beat/2, fR = base + beat/2 (contract §4.2, index.html:1097-1098)
                    val sL = oscL[vi]!!.next(base - beat / 2.0)
                    val sR = oscR[vi]!!.next(base + beat / 2.0)
                    val aL = sL * volL
                    val aR = sR * volR
                    if (voices[vi].mono) {
                        // mono: both tones summed into both channels (contract §4.2, index.html:1101-1102)
                        val mono = aL + aR
                        l += mono
                        r += mono
                        bl += mono
                        br += mono
                    } else {
                        // hard L/R, swap per stereoswap (index.html:1104-1108)
                        if (stereoswap) {
                            l += aR
                            r += aL
                            bl += aR
                            br += aL
                        } else {
                            l += aL
                            r += aR
                            bl += aL
                            br += aR
                        }
                    }
                }

                KIND_ISO -> {
                    // one sine carrier f = max(1, base||200) (contract §4.3, index.html:1068);
                    // JS `s.base||200` substitutes 200 when base is falsy (0), then max(1, ·).
                    val carrier = oscC[vi]!!.next(max(1.0, if (base != 0.0) base else 200.0))
                    val g = gate[vi]!!.next(max(0.0, beat))
                    val s = carrier * g
                    // identical signal to both ears, scaled by volL/volR (index.html:1078-1079)
                    val aL = s * volL
                    val aR = s * volR
                    if (stereoswap) {
                        l += aR
                        r += aL
                    } else {
                        l += aL
                        r += aR
                    }
                }

                KIND_PINK -> {
                    var s = pink[vi]!!.next()
                    if (pinkHasBand[vi]) {
                        // centre = max(20, base||200) (index.html:1035); base falsy(0) -> 200
                        val centre = max(20.0, if (base != 0.0) base else 200.0)
                        val bq = biquad[vi]!!
                        bq.setCentre(centre)
                        s = bq.process(s)
                    }
                    mixNoise(s, volL, volR)
                    l += mixL; r += mixR
                }

                KIND_WHITE -> {
                    // decorrelated stereo: independent L/R generators (index.html:901-903)
                    val aL = whiteL[vi]!!.next() * volL
                    val aR = whiteR[vi]!!.next() * volR
                    if (stereoswap) { l += aR; r += aL } else { l += aL; r += aR }
                }

                KIND_BROWN -> {
                    // decorrelated stereo: independent L/R generators (index.html:916-918)
                    val aL = brownL[vi]!!.next() * volL
                    val aR = brownR[vi]!!.next() * volR
                    if (stereoswap) { l += aR; r += aL } else { l += aL; r += aR }
                }

                KIND_AUDIO -> {
                    val buf = wavBuf[vi]!!
                    val len = buf.size
                    if (len > 0) {
                        // loop modulo buffer length (contract §4.5, index.html:979). Linear-interp read.
                        var pos = wavPos[vi]
                        if (pos >= len) pos %= len
                        val i0 = pos.toInt()
                        val frac = pos - i0
                        val a = buf[i0]
                        val b = buf[if (i0 + 1 < len) i0 + 1 else 0]
                        val s = (a + (b - a) * frac).toDouble()
                        val aL = s * volL
                        val aR = s * volR
                        if (stereoswap) {
                            l += aR
                            r += aL
                        } else {
                            l += aL
                            r += aR
                        }
                        pos += 1.0
                        if (pos >= len) pos -= len
                        wavPos[vi] = pos
                    }
                }
            }
        }

        outL = l
        outR = r
        binL = bl
        binR = br
        posSec += samplePeriod
    }

    // scratch for mixNoise (no allocation)
    private var mixL: Double = 0.0
    private var mixR: Double = 0.0

    /**
     * Noise voices: same mono stream to both ears with independent L/R volume envelopes, swapped
     * per stereoswap (index.html:1042-1046). Writes mixL/mixR.
     */
    private fun mixNoise(s: Double, volL: Double, volR: Double) {
        val aL = s * volL
        val aR = s * volR
        if (stereoswap) {
            mixL = aR
            mixR = aL
        } else {
            mixL = aL
            mixR = aR
        }
    }

    companion object {
        private const val KIND_SILENT = 0
        private const val KIND_BINAURAL = 1
        private const val KIND_ISO = 2
        private const val KIND_PINK = 3
        private const val KIND_WHITE = 4
        private const val KIND_BROWN = 5
        private const val KIND_AUDIO = 6
    }
}
