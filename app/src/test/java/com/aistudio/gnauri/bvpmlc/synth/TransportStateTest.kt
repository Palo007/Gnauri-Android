package com.aistudio.gnauri.bvpmlc.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract §7 item 8: stopReset vs pause semantics; seekElapsed mapping incl. loops.
 *
 * REAL-API NUANCE found while reading SynthEngine.advanceTransport(): during the 0.12s stop-fade
 * (contract §4.8) the cursor KEEPS ADVANCING even while paused/stopping ("keep advancing cursor so
 * the tail keeps sounding" - SynthEngine.kt comment). So `pause()` does not freeze the cursor at
 * the instant it is called; the cursor lands at (cursorAtPauseCall + STOP_FADE) once the fade
 * finishes rendering. `stopReset()` looks identical during the fade but then explicitly overwrites
 * cursor=0/played=0 in finalizeStopFade(), so the fade-advance is invisible for stopReset. Tests
 * below assert the real (fade-advanced) pause cursor rather than assuming it freezes immediately.
 *
 * SECOND REAL-API NUANCE: `play()` (for ANY offsetSec, including the "resume" case offsetSec<0)
 * unconditionally calls `startActiveAt(cursor)` -> `Instance.start(cursor)`, which resets EVERY
 * per-voice DSP state (oscillator phase, noise generator state, WAV read position) to a fresh
 * instance (contract §4.7's "fresh instance" reset, normally described for loop seams). This means
 * `play(-1)` does not truly "continue" the previous sound generation state across a pause/resume:
 * cursor and played survive, but a type-2 (audio-file) voice restarts its loop from sample 0 and
 * noise generators restart their deterministic sequence, even though only the transport (not the
 * schedule) changed. It is not audibly a "click" (phase merely resets to 0, amplitude/frequency
 * are unaffected) but it is a real behavioral deviation from naive "resume where you left off" DSP
 * continuity, and is asserted explicitly below for a type-2 voice.
 */
class TransportStateTest {

    private val STOP_FADE = 0.12

    private fun longVoice() = binauralVoice(
        points = listOf(Point(duration = 20.0, beat = 6.0, base = 220.0, volL = 0.5, volR = 0.5, state = 1))
    )

    private fun newEngine(totalTime: Double = 20.0, loops: Int = 1): SynthEngine {
        val engine = SynthEngine(SR)
        engine.setFadeOut30s(false)
        engine.setSchedule(scheduleOf(totalTime = totalTime, voice = longVoice(), loops = loops))
        return engine
    }

    @Test
    fun `pause retains played and advances cursor only through the stop-fade tail`() {
        val engine = newEngine()
        engine.play(0.0)
        renderAll(engine, (3.0 * SR).toInt()) // cursor ~3.0, played=0

        engine.pause()
        // render enough to complete the 0.12s stop-fade
        renderAll(engine, (1.0 * SR).toInt())

        assertFalse(engine.isPlaying)
        assertEquals(0, engine.played)
        // cursor = 3.0 (at pause call) + STOP_FADE (advances during the fade tail)
        assertEquals(3.0 + STOP_FADE, engine.elapsedSeconds, 0.001)
    }

    @Test
    fun `resuming after pause with play(-1) continues from the paused cursor`() {
        val engine = newEngine()
        engine.play(0.0)
        renderAll(engine, (3.0 * SR).toInt())
        engine.pause()
        renderAll(engine, (1.0 * SR).toInt())
        val elapsedAtPause = engine.elapsedSeconds

        engine.play(-1.0) // resume from current cursor
        assertTrue(engine.isPlaying)
        assertEquals(elapsedAtPause, engine.elapsedSeconds, 1e-9)
        assertEquals(0, engine.played) // resume must not reset the pass counter
    }

    @Test
    fun `play(-1) resume restarts a type-2 WAV voice from position 0 (fresh-instance reset)`() {
        // Real-API nuance (see class doc): play() always calls Instance.start(cursor), which
        // resets WAV position to 0 even on a plain resume. buf is a recognizable ramp so we can
        // tell exactly where playback would be if it had (incorrectly, by a naive reading of the
        // contract) continued from mid-buffer instead of restarting.
        val buf = FloatArray(10) { it.toFloat() } // 0,1,2,...,9
        val engine = SynthEngine(SR)
        engine.setFadeOut30s(false)
        engine.setAudioBuffer("ramp.wav", buf)
        val voice = Voice(
            index = 0, type = 2, desc = "ramp",
            points = listOf(Point(duration = 20.0, beat = 0.0, base = 0.0, volL = 1.0, volR = 1.0, state = 1))
        )
        engine.setSchedule(scheduleOf(totalTime = 20.0, voice = voice, loops = 1))
        engine.play(0.0)

        // advance wavPos well into the buffer at a non-zero, non-wrapped offset (e.g. position 4)
        renderAll(engine, 4)

        engine.pause()
        renderAll(engine, (1.0 * SR).toInt()) // finish the stop-fade

        engine.play(-1.0) // resume
        val out = renderAll(engine, 1)
        // if resume incorrectly continued from wavPos=4 the first sample would be buf[4]=4.0;
        // fresh-instance semantics restart at buf[0]=0.0.
        assertEquals(0.0f, out[0], 1e-6f)
    }

    @Test
    fun `stopReset while playing fades then resets cursor and played to zero`() {
        val engine = newEngine()
        engine.play(0.0)
        renderAll(engine, (5.0 * SR).toInt())

        engine.stopReset()
        renderAll(engine, (1.0 * SR).toInt()) // let the stop-fade finish

        assertFalse(engine.isPlaying)
        assertEquals(0, engine.played)
        assertEquals(0.0, engine.elapsedSeconds, 1e-9)
    }

    @Test
    fun `stopReset while already paused applies immediately with no fade needed`() {
        val engine = newEngine()
        engine.play(0.0)
        renderAll(engine, (5.0 * SR).toInt())
        engine.pause()
        renderAll(engine, (1.0 * SR).toInt())
        assertFalse(engine.isPlaying)

        engine.stopReset() // not playing -> immediate reset, no render needed
        assertEquals(0, engine.played)
        assertEquals(0.0, engine.elapsedSeconds, 1e-9)
    }

    @Test
    fun `seekElapsed while playing defers through a stop-fade then resumes at the target`() {
        val engine = newEngine(totalTime = 20.0, loops = 1)
        engine.play(0.0)
        renderAll(engine, (1.0 * SR).toInt())

        engine.seekElapsed(15.5)
        // REAL-API NUANCE (verified by tracing SynthEngine.render/advanceTransport): once
        // finalizeStopFade() resumes playback mid-buffer, the render() loop keeps calling
        // advanceTransport() for the REST of the frames requested in this same render() call, and
        // that branch unconditionally does `cursor += samplePeriod` while playing==true. So if we
        // rendered a full extra second here (as opposed to exactly STOP_FADE worth of frames), the
        // resumed cursor would keep advancing past the 15.5 target for the remainder of that call
        // (target + (1.0 - STOP_FADE) =~ 16.38s), NOT stay pinned at 15.5. To assert the exact
        // resume target we render EXACTLY STOP_FADE seconds (0.12s = 5292 frames at 44100Hz, an
        // exact integer) so finalize lands on the very last processed sample with zero frames left
        // to advance the newly-resumed cursor.
        val stopFadeFrames = (STOP_FADE * SR).toInt() // 5292, exact
        renderAll(engine, stopFadeFrames)

        assertTrue("seek while playing should resume playback", engine.isPlaying)
        assertEquals(15.5, engine.elapsedSeconds, 1e-6)
    }

    @Test
    fun `seekElapsed while playing resumes and continues advancing for any frames rendered past the stop-fade`() {
        // Companion to the test above: rendering MORE than STOP_FADE worth of frames after the seek
        // call means the resumed instance keeps playing for the remainder of that render() call, so
        // elapsedSeconds must reflect target + (rendered - STOP_FADE), not stay pinned at target.
        val engine = newEngine(totalTime = 20.0, loops = 1)
        engine.play(0.0)
        renderAll(engine, (1.0 * SR).toInt())

        engine.seekElapsed(15.5)
        renderAll(engine, (1.0 * SR).toInt()) // a full second: STOP_FADE (0.12s) + 0.88s of resumed playback

        assertTrue(engine.isPlaying)
        assertEquals(15.5 + (1.0 - STOP_FADE), engine.elapsedSeconds, 1e-6)
    }

    @Test
    fun `seekElapsed while paused applies immediately without needing a render call`() {
        val engine = newEngine(totalTime = 20.0, loops = 1)
        engine.play(0.0)
        renderAll(engine, (1.0 * SR).toInt())
        engine.pause()
        renderAll(engine, (1.0 * SR).toInt())
        assertFalse(engine.isPlaying)

        engine.seekElapsed(12.3)
        assertEquals(12.3, engine.elapsedSeconds, 1e-9)
        assertFalse("seeking while paused must not start playback", engine.isPlaying)
    }

    @Test
    fun `seekElapsed clamps to loops times totalTime for finite schedules`() {
        val engine = newEngine(totalTime = 10.0, loops = 2) // maxElapsed = 20.0
        engine.play(0.0)
        renderAll(engine, (1.0 * SR).toInt())
        engine.pause()
        renderAll(engine, (1.0 * SR).toInt())

        engine.seekElapsed(999.0) // way past the end -> clamps to loops*totalTime = 20.0
        assertEquals(20.0, engine.elapsedSeconds, 1e-9)
        // applyElapsed maps elapsed=20.0 (an exact multiple of totalTime) to played=2, cursor=0.0
        assertEquals(2, engine.played)

        engine.seekElapsed(-5.0) // negative clamps to 0.0
        assertEquals(0.0, engine.elapsedSeconds, 1e-9)
        assertEquals(0, engine.played)
    }

    @Test
    fun `seekElapsed maps mid-schedule elapsed to the correct played and cursor for loops=2`() {
        val engine = newEngine(totalTime = 10.0, loops = 2)
        engine.play(0.0)
        renderAll(engine, (1.0 * SR).toInt())
        engine.pause()
        renderAll(engine, (1.0 * SR).toInt())

        engine.seekElapsed(13.25) // played=1, cursor=3.25
        assertEquals(13.25, engine.elapsedSeconds, 1e-9)
        assertEquals(1, engine.played)
        val impliedCursor = engine.elapsedSeconds - engine.played * 10.0
        assertEquals(3.25, impliedCursor, 1e-9)
    }
}
