package com.aistudio.gnauri.bvpmlc.synth

import kotlin.math.min

/**
 * Isochronic pulse gate (contract §4.3, port of _scheduleIsoGate index.html:1206-1242).
 *
 * The JS version walked forward one cycle at a time, reading the instantaneous beat rate at each
 * cycle start (index.html:1216-1217) and writing an explicit gain curve for that cycle. We render
 * the same shape sample-by-sample: we track the position within the current cycle; when a cycle
 * ends we sample the beat rate at the new cycle-start time to size the next cycle. The 200 000-event
 * guard is deliberately NOT ported (WebAudio artifact — contract §4.3).
 *
 *   beat <= 0.01  -> gate held fully open (value 1), advancing in 0.10 s steps (index.html:1218-1222).
 *   type 3 (smooth): 0 at cycle start, linear ramp up to 1 at period/2, back to 0 at period end
 *                    (index.html:1236-1238).
 *   type 4 (gapped): 0 at start, ramp up over `ramp = min(0.004, period*0.1)` to 1, hold 1 until
 *                    onSpan-ramp, ramp down to 0 at onSpan = period*0.66, silence for the remainder
 *                    (index.html:1226-1233).
 *
 * The beat rate at time u is provided by the caller via [Segments.sample] (the caller passes the
 * already-sampled beat), so this class does no schedule lookup itself and allocates nothing.
 */
class IsoGate(private val sampleRate: Int, private val gapped: Boolean) {

    private val samplePeriod: Double = 1.0 / sampleRate

    // state for the current cycle
    private var cyclePos: Double = 0.0     // seconds elapsed within the current cycle
    private var period: Double = 0.0       // current cycle length (0 => "held open" mode)
    private var ramp: Double = 0.0         // gapped attack/release ramp for the current cycle
    private var onSpan: Double = 0.0       // gapped ON duration for the current cycle
    private var openHold: Boolean = true   // true when in the beat<=0.01 held-open branch
    private var started: Boolean = false   // whether the first cycle has been primed

    /** Fresh-instance reset (contract §4.7). */
    fun reset() {
        cyclePos = 0.0
        period = 0.0
        ramp = 0.0
        onSpan = 0.0
        openHold = true
        started = false
    }

    /** Begin a new cycle given the beat rate sampled at this cycle's start (index.html:1217). */
    private fun beginCycle(rate: Double) {
        if (rate <= 0.01) {                 // index.html:1218
            openHold = true
            period = 0.10                   // advance by 0.10 s while held open (index.html:1221)
        } else {
            openHold = false
            period = 1.0 / rate             // index.html:1224
            ramp = min(0.004, period * 0.1) // index.html:1226
            onSpan = period * 0.66          // index.html:1228
        }
        cyclePos = 0.0
        started = true
    }

    /**
     * Return the gate value for the current sample given [rate] = instantaneous beat frequency
     * (>= 0) sampled at the current absolute time, then advance one sample.
     *
     * We re-evaluate the cycle parameters at each cycle boundary using the caller-supplied [rate],
     * matching the JS "read the beat rate at each cycle start" behavior (index.html:1216-1217).
     */
    fun next(rate: Double): Double {
        if (!started) beginCycle(rate)

        val value: Double = if (openHold) {
            1.0 // held fully open (index.html:1220)
        } else {
            val p = cyclePos
            if (gapped) {
                when {
                    p >= onSpan -> 0.0                        // silence gap (index.html:1233)
                    p < ramp -> p / ramp                      // attack ramp 0->1 (index.html:1230)
                    p >= onSpan - ramp ->                     // release ramp 1->0 (index.html:1231-1232)
                        (onSpan - p) / ramp
                    else -> 1.0                               // hold ON
                }
            } else {
                val half = period * 0.5
                if (p < half) p / half                        // rise 0->1 at mid (index.html:1237)
                else (period - p) / half                      // fall 1->0 at end (index.html:1238)
            }
        }

        // advance one sample; roll to the next cycle when we pass the period boundary
        cyclePos += samplePeriod
        if (cyclePos >= period) {
            // Boundary crossed: re-prime the next cycle using the current rate, so a beat-rate
            // change across datapoints is honored at each cycle start (matches the JS loop reading
            // beatAt(u) at every cycle — index.html:1216-1217). Sub-sample overshoot is dropped
            // (beginCycle resets cyclePos to 0); the resulting drift is well under the parity
            // tolerance (contract test 5).
            beginCycle(rate)
        }

        return value
    }
}
