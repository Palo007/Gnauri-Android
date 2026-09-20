package com.aistudio.gnauri.bvpmlc.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract §7 item 5: isochronic pulse gate. Drives [IsoGate] directly (same package, public
 * class) rather than demodulating engine output, since IsoGate IS the exact unit the contract
 * describes (§4.3) and driving it directly gives sample-exact, deterministic assertions instead of
 * envelope-detection noise. A short engine-level smoke test at the bottom exercises the full
 * SynthEngine wiring (voice type 3/4 -> IsoGate) for integration coverage.
 */
class IsochronicGateTest {

    // rate=4Hz chosen so period (0.25s) * 44100 = 11025 samples exactly -> no fractional-cycle
    // drift in the pulse-counting assertions below.
    private val rate = 4.0

    @Test
    fun `type 3 smooth pulse rate equals beat`() {
        val gate = IsoGate(SR, gapped = false)
        val seconds = 10.0
        val n = (seconds * SR).toInt()

        var risingCrossings = 0
        var prev = 0.0
        for (i in 0 until n) {
            val v = gate.next(rate)
            if (prev < 0.5 && v >= 0.5) risingCrossings++
            prev = v
        }

        // period=0.25s -> 40 cycles in 10s -> 40 rising crossings of the 0->1 ramp's midpoint.
        assertEquals(40, risingCrossings)
    }

    @Test
    fun `type 4 gapped duty cycle is approximately 0_66`() {
        val gate = IsoGate(SR, gapped = true)
        val seconds = 10.0
        val n = (seconds * SR).toInt()

        var onSamples = 0
        for (i in 0 until n) {
            val v = gate.next(rate)
            if (v > 0.0) onSamples++
        }
        val duty = onSamples.toDouble() / n
        assertEquals(0.66, duty, 0.05)
    }

    @Test
    fun `type 4 gapped pulse rate equals beat (rising edges from silence)`() {
        val gate = IsoGate(SR, gapped = true)
        val seconds = 10.0
        val n = (seconds * SR).toInt()

        var risingCrossings = 0
        var prev = 0.0
        for (i in 0 until n) {
            val v = gate.next(rate)
            if (prev <= 0.0 && v > 0.0) risingCrossings++
            prev = v
        }
        assertEquals(40, risingCrossings)
    }

    @Test
    fun `beat less or equal 0_01 holds the gate fully open (constant carrier)`() {
        val gateSmooth = IsoGate(SR, gapped = false)
        val gateGapped = IsoGate(SR, gapped = true)
        val n = (2.0 * SR).toInt()
        for (i in 0 until n) {
            assertEquals(1.0, gateSmooth.next(0.0), 1e-12)
            assertEquals(1.0, gateGapped.next(0.0), 1e-12)
        }
    }

    @Test
    fun `reset returns the gate to fresh-instance state`() {
        val gate = IsoGate(SR, gapped = false)
        // advance partway into a cycle
        repeat(2000) { gate.next(rate) }
        gate.reset()
        // immediately after reset, the very first sample of a fresh cycle must be the ramp start (0.0)
        val first = gate.next(rate)
        assertEquals(0.0, first, 1e-9)
    }

    // ---- integration smoke test: engine wiring for isochronic voices does not crash / produces
    // ---- a bounded, non-silent signal that is amplitude-modulated (contract §4.3 end-to-end) ----
    @Test
    fun `engine renders a bounded non-silent isochronic voice`() {
        val engine = SynthEngine(SR)
        val voice = isoVoice(
            points = listOf(Point(duration = 5.0, beat = rate, base = 300.0, volL = 0.7, volR = 0.7, state = 1)),
            gapped = true
        )
        val sched = scheduleOf(totalTime = 5.0, voice = voice, loops = 1)
        engine.setFadeOut30s(false)
        engine.setSchedule(sched)
        engine.play(0.0)

        val stereo = renderAll(engine, (4.9 * SR).toInt())
        assertTrue(allFiniteAndBounded(stereo))
        val left = leftChannel(stereo)
        assertTrue("expected some non-zero signal", rms(left) > 0.01)
    }
}
