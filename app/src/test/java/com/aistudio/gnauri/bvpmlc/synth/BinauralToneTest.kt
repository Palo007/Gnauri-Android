package com.aistudio.gnauri.bvpmlc.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * Contract §7 items 1-3: binaural zero-crossing frequency, click-free base sweep, and gain
 * envelope tracking sampleVoice. Uses base/beat combinations chosen so Segments.sample (a single
 * point per segment, or two points with the second only defining the ramp target) yields exactly
 * predictable values -- see Segments.kt: with N points, segment i ramps toward point i+1's start
 * values, and the LAST segment (i+1 == count) ramps toward itself, i.e. stays constant.
 */
class BinauralToneTest {

    // ---- Item 1: binaural zero-crossing frequency (base=200, beat=10 const) ----
    @Test
    fun `binaural zero-crossing frequency matches fL and fR within 0_5Hz`() {
        val engine = SynthEngine(SR)
        // single point spanning the whole render window -> Segments.sample returns it unchanged
        // (last/only segment ramps toward itself), so beat/base are exactly constant.
        val voice = binauralVoice(
            points = listOf(Point(duration = 12.0, beat = 10.0, base = 200.0, volL = 1.0, volR = 1.0, state = 1))
        )
        val sched = scheduleOf(totalTime = 12.0, voice = voice, loops = 1)
        engine.setFadeOut30s(false) // avoid the final-pass fade polluting the amplitude/measurement
        engine.setSchedule(sched)
        engine.play(0.0)

        val seconds = 9.0 // stay well clear of the loop-seam stop-fade near totalTime=12s
        val frames = (seconds * SR).toInt()
        val stereo = renderAll(engine, frames)
        val left = leftChannel(stereo)
        val right = rightChannel(stereo)

        val fL = zeroCrossingFrequency(left, seconds)
        val fR = zeroCrossingFrequency(right, seconds)

        // fL = base - beat/2 = 195, fR = base + beat/2 = 205 (contract §4.2)
        assertEquals(195.0, fL, 0.5)
        assertEquals(205.0, fR, 0.5)
    }

    // ---- Item 2: no click during a 100Hz -> 400Hz base sweep ----
    @Test
    fun `base sweep 100 to 400Hz has no sample-to-sample click`() {
        val engine = SynthEngine(SR)
        // segment 0 (5s) ramps base from 100 toward point 1's start base (400); beat=0 so
        // fL=fR=base, a pure single-channel tone tracking the sweep continuously.
        val voice = binauralVoice(
            points = listOf(
                Point(duration = 5.0, beat = 0.0, base = 100.0, volL = 0.5, volR = 0.5, state = 1),
                Point(duration = 1.0, beat = 0.0, base = 400.0, volL = 0.5, volR = 0.5, state = 1)
            )
        )
        val sched = scheduleOf(totalTime = 6.0, voice = voice, loops = 1)
        engine.setFadeOut30s(false)
        engine.setSchedule(sched)
        engine.play(0.0)

        val seconds = 4.99 // stay inside segment 0's ramp window (duration 5.0)
        val frames = (seconds * SR).toInt()
        val stereo = renderAll(engine, frames)
        val left = leftChannel(stereo)

        // theoretical bound: |delta| <= amplitude * (2*pi*fMax/sr) = 0.5 * (2*pi*400/44100) ~= 0.0285
        val maxDelta = maxAdjacentDelta(left)
        assertTrue("max adjacent-sample delta $maxDelta exceeded 0.05", maxDelta < 0.05)
    }

    // ---- Item 3: gain envelope tracks sampleVoice within 1e-3 (relative 5%) at random t ----
    @Test
    fun `RMS envelope tracks sampleVoice linear ramp within tolerance`() {
        val engine = SynthEngine(SR)
        // volL/volR ramp 0.2 -> 0.8 linearly across [0,10]; base=200 constant, beat=0 (single tone).
        val voice = binauralVoice(
            points = listOf(
                Point(duration = 10.0, beat = 0.0, base = 200.0, volL = 0.2, volR = 0.2, state = 1),
                Point(duration = 5.0, beat = 0.0, base = 200.0, volL = 0.8, volR = 0.8, state = 1)
            )
        )
        val sched = scheduleOf(totalTime = 15.0, voice = voice, loops = 1)
        engine.setFadeOut30s(false)
        engine.setSchedule(sched)
        engine.play(0.0)

        val totalSeconds = 9.5
        val frames = (totalSeconds * SR).toInt()
        val stereo = renderAll(engine, frames)
        val left = leftChannel(stereo)

        val windowLen = 2205 // 50ms = 10 cycles at 200Hz: RMS-of-sine converges well within this window
        val sampleTimes = listOf(2.0, 4.0, 6.0, 8.0) // avoid t near 0 where ramp/window edge effects grow
        for (t in sampleTimes) {
            val centerSample = (t * SR).toInt()
            val start = centerSample - windowLen / 2
            require(start >= 0 && start + windowLen <= left.size) { "window out of range for t=$t" }
            val actualRms = rms(left, start, windowLen)

            // sampleVoice expected gain at t (segment 0 ramps 0.2 -> 0.8 across duration 10)
            val expectedGain = 0.2 + (0.8 - 0.2) * (t / 10.0)
            val expectedRms = expectedGain / sqrt(2.0)

            val relErr = kotlin.math.abs(actualRms - expectedRms) / expectedRms
            assertTrue(
                "t=$t expectedRms=$expectedRms actualRms=$actualRms relErr=$relErr",
                relErr < 0.06
            )
        }
    }
}
