package com.aistudio.gnauri.bvpmlc.synth

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Contract §7 item 3 (dedicated coverage): gain envelope tracks `sampleVoice` within tolerance at
 * random t, using a tone voice with a volume ramp where BOTH volL and volR stay <= 0.5 throughout
 * (BinauralToneTest's own item-3 test uses a 0.2->0.8 ramp which exceeds 0.5 at t>7.5s -- this file
 * adds the specific "volumes <= 0.5" case called out in the WP4 task list, using a different,
 * non-overlapping ramp/base so it is not a duplicate).
 *
 * Method: window-RMS of the rendered tone vs. the RMS predicted from sampleVoice's instantaneous
 * gain (amplitude/sqrt(2) for a pure sine), at several random-ish times within the ramp, with a
 * ~5% relative tolerance. fadeOut30s is disabled so the final-pass fade does not pollute the
 * measurement.
 */
class GainEnvelopeTest {

    @Test
    fun `RMS envelope tracks sampleVoice ramp within 5 percent when volumes stay at or below 0_5`() {
        val engine = SynthEngine(SR)
        // volL/volR ramp 0.1 -> 0.5 linearly across segment 0 (duration 12s); base=330 constant,
        // beat=0 so fL=fR=base (single-tone amplitude == volL == volR, no beat interference).
        val voice = binauralVoice(
            points = listOf(
                Point(duration = 12.0, beat = 0.0, base = 330.0, volL = 0.1, volR = 0.1, state = 1),
                Point(duration = 3.0, beat = 0.0, base = 330.0, volL = 0.5, volR = 0.5, state = 1)
            )
        )
        val sched = scheduleOf(totalTime = 15.0, voice = voice, loops = 1)
        engine.setFadeOut30s(false)
        engine.setSchedule(sched)
        engine.play(0.0)

        val totalSeconds = 11.5
        val frames = (totalSeconds * SR).toInt()
        val stereo = renderAll(engine, frames)
        val left = leftChannel(stereo)
        val right = rightChannel(stereo)

        // window long enough for RMS-of-sine to converge at 330Hz (33 cycles), short enough to keep
        // the ramp ~locally linear within the window.
        val windowLen = 2205 // 50ms
        // "random" sample times, deliberately not round numbers, all comfortably inside [0, 12).
        val sampleTimes = listOf(1.37, 3.91, 5.05, 7.63, 9.88, 11.2)
        for (t in sampleTimes) {
            val centerSample = (t * SR).toInt()
            val start = centerSample - windowLen / 2
            require(start >= 0 && start + windowLen <= left.size) { "window out of range for t=$t" }

            val expectedGain = 0.1 + (0.5 - 0.1) * (t / 12.0) // sampleVoice linear ramp (Segments.sample)
            assertTrue("expected gain should stay <= 0.5 by construction (t=$t, gain=$expectedGain)", expectedGain <= 0.5 + 1e-9)
            val expectedRms = expectedGain / sqrt(2.0)

            val actualRmsL = rms(left, start, windowLen)
            val actualRmsR = rms(right, start, windowLen)
            val relErrL = kotlin.math.abs(actualRmsL - expectedRms) / expectedRms
            val relErrR = kotlin.math.abs(actualRmsR - expectedRms) / expectedRms

            assertTrue("L t=$t expectedRms=$expectedRms actualRms=$actualRmsL relErr=$relErrL", relErrL < 0.05)
            assertTrue("R t=$t expectedRms=$expectedRms actualRms=$actualRmsR relErr=$relErrR", relErrR < 0.05)
        }
    }
}
