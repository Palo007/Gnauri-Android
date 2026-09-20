package com.aistudio.gnauri.bvpmlc.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract §7 item 10: type-2 (audio-file) buffer voice. Verifies (contract §4.5):
 *  - the buffer loops with the exact period of its length (100 samples here), independent of the
 *    engine's sample rate interpretation -- SynthEngine treats the buffer as already resampled to
 *    the engine's rate and reads it as a plain per-SAMPLE ring, so a 100-sample buffer repeats every
 *    100 rendered frames;
 *  - the read position restarts at 0 at every playback-instance start (fresh-instance semantics --
 *    play()/seek/loop-seam all call Instance.start(), which zeroes wavPos);
 *  - only channel 0 is ever used (setAudioBuffer's contract is "channel-0 only", so the buffer
 *    passed in IS already the mono/ch0 stream -- verified by using a buffer that would be obviously
 *    wrong if any imagined "channel 1" data leaked in);
 *  - volL/volR scale the (mono) sample identically before hitting L/R, honoring stereoswap.
 *
 * The engine reads the buffer with LINEAR INTERPOLATION between adjacent samples (see
 * SynthEngine.Instance.renderSample's KIND_AUDIO branch: `pos` is a fractional sample index that
 * advances by 1.0/sample and reads `buf[i0] + (buf[i0+1]-buf[i0])*frac`). Since our buffer advances
 * exactly 1.0 sample per rendered frame with no resampling ratio involved, `frac` stays 0 at every
 * sample boundary rendered, so the linear interpolation is a no-op here (frac==0.0) and each
 * rendered sample equals buf[i] exactly -- this lets the test assert exact float values rather than
 * interpolated ones.
 */
class AudioBufferVoiceTest {

    /** 100-sample recognizable mono ramp: 0,1,2,...,99 (as floats), easy to identify by position. */
    private fun rampBuffer(len: Int = 100): FloatArray = FloatArray(len) { it.toFloat() }

    private fun bufferVoice(desc: String = "loop", volL: Double = 1.0, volR: Double = 1.0, duration: Double): Voice =
        Voice(
            index = 0, type = 2, desc = desc,
            points = listOf(Point(duration = duration, beat = 0.0, base = 0.0, volL = volL, volR = volR, state = 1))
        )

    @Test
    fun `type-2 buffer loops with a period equal to its length`() {
        val buf = rampBuffer(100)
        val engine = SynthEngine(SR)
        engine.setFadeOut30s(false)
        engine.setAudioBuffer("loop.wav", buf)
        val voice = bufferVoice(desc = "loop", duration = 1.0) // 44100 frames >> 441 buffer periods
        engine.setSchedule(scheduleOf(totalTime = 1.0, voice = voice, loops = 1))
        engine.play(0.0)

        // Render 350 frames (3.5 periods of the 100-sample buffer) and confirm the left channel
        // exactly repeats every 100 samples: out[n] == buf[n % 100] (frac==0 at every integer step).
        val stereo = renderAll(engine, 350, chunk = 64) // exercise chunk boundaries mid-period too
        val left = leftChannel(stereo)
        for (n in 0 until 350) {
            assertEquals("sample $n", buf[n % 100], left[n], 1e-4f)
        }
    }

    @Test
    fun `type-2 buffer read position restarts at zero at every instance start (play, seek, loop seam)`() {
        val buf = rampBuffer(100)
        val engine = SynthEngine(SR)
        engine.setFadeOut30s(false)
        engine.setAudioBuffer("loop.wav", buf)
        val voice = bufferVoice(desc = "loop", duration = 1.0)
        engine.setSchedule(scheduleOf(totalTime = 1.0, voice = voice, loops = 3))
        engine.play(0.0)

        // advance to a non-zero, non-wrapped read position (e.g. position 37) then pause. Pause's
        // stop-fade finalize sets active=null and playing=false, so over-rendering past the fade
        // boundary here is harmless (render() early-returns once active==null) -- unlike the seek
        // case below, where the resumed instance keeps rendering (and advancing wavPos) for any
        // frames rendered past the exact fade boundary in the SAME render() call.
        renderAll(engine, 37)
        engine.pause()
        renderAll(engine, (0.12 * SR).toInt() + 10) // let the stop-fade finish

        // resume: play(-1) calls Instance.start(cursor) -> wavPos resets to 0, NOT 37.
        engine.play(-1.0)
        val afterResume = renderAll(engine, 1)
        assertEquals("resume must restart the buffer at position 0, not the paused position", buf[0], leftChannel(afterResume)[0], 1e-4f)

        // seek: also restarts the buffer at position 0 relative to the NEW instance start. Render
        // EXACTLY the stop-fade duration (0.12s = 5292 frames, exact at 44100Hz) so finalize lands
        // on the last processed sample with no leftover frames in this render() call to advance the
        // freshly-restarted wavPos past 0 (same real-API nuance as TransportStateTest's seek test:
        // once finalizeStopFade() resumes mid-buffer, the render loop keeps rendering the resumed
        // instance for whatever frames remain in that call).
        engine.seekElapsed(0.5)
        val stopFadeFrames = (0.12 * SR).toInt() // 5292, exact
        renderAll(engine, stopFadeFrames)
        val afterSeek = renderAll(engine, 1)
        assertEquals("seek must restart the buffer at position 0", buf[0], leftChannel(afterSeek)[0], 1e-4f)
    }

    @Test
    fun `type-2 buffer restarts at zero across a loop seam (fresh incoming instance)`() {
        // A short totalTime relative to the crossfade window keeps the assertion simple: the
        // INCOMING instance (post-seam) must start its wavPos at 0 (contract §4.7), even though the
        // OUTGOING instance is still rendering its tail during the 0.18s crossfade. We probe the
        // active (incoming) instance's contribution shortly after the seam once the outgoing tail's
        // crossfade window (CROSSFADE=0.18s) has fully elapsed, so only the incoming instance's
        // buffer-position semantics are being observed.
        val buf = rampBuffer(100)
        val engine = SynthEngine(SR)
        engine.setFadeOut30s(false)
        engine.setAudioBuffer("loop.wav", buf)
        val voice = bufferVoice(desc = "loop", duration = 2.0) // totalTime=2s, buffer period 100 samples
        engine.setSchedule(scheduleOf(totalTime = 2.0, voice = voice, loops = 2))
        engine.play(0.0)

        // Render up to just past the seam (t=2.0s) plus the full crossfade window (0.18s), so only
        // the incoming instance remains sounding; then render a few more samples and confirm they
        // match buf[k] for a fresh restart at the seam+crossfade point.
        val seamPlusXfade = (2.0 * SR).toInt() + (0.18 * SR).toInt() + 5
        renderAll(engine, seamPlusXfade)
        // At this point the incoming instance has been running for ((0.18*SR)+5) samples since the
        // seam restarted it at wavPos=0, so its current position is that count mod 100.
        val incomingSamplesSoFar = ((0.18 * SR).toInt() + 5) % 100
        val next = renderAll(engine, 1)
        assertEquals(
            "incoming instance's buffer position after the seam should equal elapsed-since-seam mod 100",
            buf[incomingSamplesSoFar % 100], leftChannel(next)[0], 1e-3f
        )
    }

    @Test
    fun `volL and volR scale the mono buffer sample independently, honoring stereoswap`() {
        val buf = rampBuffer(100)
        val engine = SynthEngine(SR)
        engine.setFadeOut30s(false)
        engine.setAudioBuffer("loop.wav", buf)
        val voice = bufferVoice(desc = "loop", volL = 0.25, volR = 0.75, duration = 1.0)
        engine.setSchedule(scheduleOf(totalTime = 1.0, voice = voice, loops = 1, stereoswap = false))
        engine.play(0.0)

        // sample index 10 -> buf[10] = 10.0; expect L = 10*0.25 = 2.5, R = 10*0.75 = 7.5
        val stereo = renderAll(engine, 11)
        assertEquals(2.5f, leftChannel(stereo)[10], 1e-4f)
        assertEquals(7.5f, rightChannel(stereo)[10], 1e-4f)
    }

    @Test
    fun `stereoswap swaps volL and volR ears for a type-2 buffer voice`() {
        val buf = rampBuffer(100)
        val engine = SynthEngine(SR)
        engine.setFadeOut30s(false)
        engine.setAudioBuffer("loop.wav", buf)
        val voice = bufferVoice(desc = "loop", volL = 0.25, volR = 0.75, duration = 1.0)
        engine.setSchedule(scheduleOf(totalTime = 1.0, voice = voice, loops = 1, stereoswap = true))
        engine.play(0.0)

        // with stereoswap, the L output channel receives the volR-scaled sample and vice versa
        // (SynthEngine.Instance.renderSample KIND_AUDIO: "if (stereoswap) { l += aR; r += aL }").
        val stereo = renderAll(engine, 11)
        assertEquals(7.5f, leftChannel(stereo)[10], 1e-4f)
        assertEquals(2.5f, rightChannel(stereo)[10], 1e-4f)
    }

    @Test
    fun `voice with no matching buffer is silently skipped (no crash, silence from that voice)`() {
        val engine = SynthEngine(SR)
        engine.setFadeOut30s(false)
        // no setAudioBuffer call at all -- desc "missing" resolves to no buffer.
        val voice = bufferVoice(desc = "missing", duration = 1.0)
        engine.setSchedule(scheduleOf(totalTime = 1.0, voice = voice, loops = 1))
        engine.play(0.0)

        val stereo = renderAll(engine, (0.9 * SR).toInt())
        assertTrue(allFiniteAndBounded(stereo))
        assertEquals(0.0, rms(leftChannel(stereo)), 1e-9)
        assertEquals(0.0, rms(rightChannel(stereo)), 1e-9)
    }

    @Test
    fun `buffer lookup resolves via the desc field using the name plus dot-wav fallback`() {
        // Voice.desc = "loop" (no extension); the buffer is registered under "loop.wav" via
        // setAudioBuffer (mirroring how WavRegistry/loadSchedule would register a real WAV file's
        // display name). Instance.lookupBuffer's own normName(desc)+".wav" fallback must resolve it.
        val buf = rampBuffer(100)
        val engine = SynthEngine(SR)
        engine.setFadeOut30s(false)
        engine.setAudioBuffer("loop.wav", buf)
        val voice = bufferVoice(desc = "loop", duration = 1.0)
        engine.setSchedule(scheduleOf(totalTime = 1.0, voice = voice, loops = 1))
        engine.play(0.0)

        val stereo = renderAll(engine, 5)
        // non-silent -> the buffer was found and is being rendered (values 0,1,2,3,4)
        for (n in 0 until 5) {
            assertEquals(buf[n], leftChannel(stereo)[n], 1e-4f)
        }
    }
}
