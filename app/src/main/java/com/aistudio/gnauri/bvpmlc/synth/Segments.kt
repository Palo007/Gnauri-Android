package com.aistudio.gnauri.bvpmlc.synth

/**
 * Struct-of-arrays segment store for one voice. buildSegments (index.html:673-680) turns the
 * voice's points (durations) into absolute-time segments; sampleVoice (index.html:682-702) does a
 * piecewise-linear interpolation of {beat, base, volL, volR} at an absolute time t, ramping toward
 * the NEXT segment's start values across the current segment's duration.
 *
 * All storage is preallocated at construction so [sample] allocates nothing on the hot path.
 */
class Segments(voice: Voice) {

    val count: Int = voice.points.size

    // absolute time bounds and parameter values, one entry per point (index.html:676)
    private val t0 = DoubleArray(count)
    private val t1 = DoubleArray(count)
    private val beat = DoubleArray(count)
    private val base = DoubleArray(count)
    private val volL = DoubleArray(count)
    private val volR = DoubleArray(count)

    // scratch output of [sample], reused each call (no allocation in the render path)
    @JvmField var outBeat: Double = 0.0
    @JvmField var outBase: Double = 0.0
    @JvmField var outVolL: Double = 0.0
    @JvmField var outVolR: Double = 0.0

    /** True if any segment has base > 1 — gates the pink-noise bandpass (index.html:1032). */
    val anyBaseAboveOne: Boolean

    init {
        var t = 0.0
        var flag = false
        for (i in 0 until count) {
            val p = voice.points[i]
            t0[i] = t
            t1[i] = t + p.duration
            beat[i] = p.beat
            base[i] = p.base
            volL[i] = p.volL
            volR[i] = p.volR
            t += p.duration
            if (p.base > 1.0) flag = true
        }
        anyBaseAboveOne = flag
    }

    /**
     * Piecewise-linear sample at absolute time [t]; writes results into out* fields.
     * Exact port of sampleVoice (index.html:682-702):
     *  - t <= first.t0  -> first segment's start values (no ramp).
     *  - inside segment s -> interpolate from s's values toward the NEXT segment's start values,
     *    fraction f = (t - s.t0) / (s.t1 - s.t0 || 1).
     *  - t past the last segment -> last segment's values.
     * Returns false if there are no segments (JS returns null — index.html:683).
     */
    fun sample(t: Double): Boolean {
        if (count == 0) return false

        // t <= segs[0].t0 -> segs[0] start values (index.html:684)
        if (t <= t0[0]) {
            outBeat = beat[0]; outBase = base[0]; outVolL = volL[0]; outVolR = volR[0]
            return true
        }

        // find the segment whose [t0,t1] contains t (index.html:685-698)
        for (i in 0 until count) {
            if (t >= t0[i] && t <= t1[i]) {
                val span = (t1[i] - t0[i]).let { if (it == 0.0) 1.0 else it } // "|| 1" (index.html:687)
                val f = (t - t0[i]) / span
                // end = next segment if present, else this segment (index.html:690-691)
                val e = if (i + 1 < count) i + 1 else i
                outBeat = beat[i] + (beat[e] - beat[i]) * f
                outBase = base[i] + (base[e] - base[i]) * f
                outVolL = volL[i] + (volL[e] - volL[i]) * f
                outVolR = volR[i] + (volR[e] - volR[i]) * f
                return true
            }
        }

        // past the end -> last segment values (index.html:700-701)
        val last = count - 1
        outBeat = beat[last]; outBase = base[last]; outVolL = volL[last]; outVolR = volR[last]
        return true
    }
}
