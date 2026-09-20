package com.aistudio.gnauri.bvpmlc.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract §7 item 8 (gap-fill only): TransportStateTest.kt already covers stopReset-vs-pause and
 * seekElapsed clamping/mapping for FINITE-loop schedules. It does not exercise seekElapsed against
 * an INFINITE-loop (loops=0) schedule, where SynthEngine.clampElapsed uses Double.MAX_VALUE as the
 * ceiling instead of loops*totalTime (SynthEngine.kt clampElapsed). This file adds exactly that gap,
 * plus a seek-while-playing-with-loops=0 case (still resumes, still loops afterward), without
 * duplicating any of TransportStateTest's existing assertions.
 */
class TransportSeekLoopsTest {

    private fun infiniteVoice() = binauralVoice(
        points = listOf(Point(duration = 10.0, beat = 6.0, base = 220.0, volL = 0.5, volR = 0.5, state = 1))
    )

    private fun newInfiniteEngine(totalTime: Double = 10.0): SynthEngine {
        val engine = SynthEngine(SR)
        engine.setFadeOut30s(false)
        engine.setSchedule(scheduleOf(totalTime = totalTime, voice = infiniteVoice(), loops = 0))
        return engine
    }

    @Test
    fun `seekElapsed on an infinite-loop schedule is not clamped by a finite ceiling`() {
        val engine = newInfiniteEngine(totalTime = 10.0)
        engine.play(0.0)
        renderAll(engine, (1.0 * SR).toInt())
        engine.pause()
        renderAll(engine, (1.0 * SR).toInt())
        assertFalse(engine.isPlaying)

        // Seek far past what would be a finite ceiling for e.g. loops=2 (20.0) -- for loops=0 this
        // must NOT clamp down; it maps to played=345, cursor=1.5 (elapsed = 3451.5).
        engine.seekElapsed(3451.5)
        assertEquals(3451.5, engine.elapsedSeconds, 1e-6)
        assertEquals(345, engine.played)
        val impliedCursor = engine.elapsedSeconds - engine.played * 10.0
        assertEquals(1.5, impliedCursor, 1e-6)
        assertTrue(engine.loopsLeft.isInfinite())
    }

    @Test
    fun `seekElapsed negative still clamps to zero on an infinite-loop schedule`() {
        val engine = newInfiniteEngine(totalTime = 10.0)
        engine.play(0.0)
        renderAll(engine, (1.0 * SR).toInt())
        engine.pause()
        renderAll(engine, (1.0 * SR).toInt())

        engine.seekElapsed(-42.0)
        assertEquals(0.0, engine.elapsedSeconds, 1e-9)
        assertEquals(0, engine.played)
    }

    @Test
    fun `seekElapsed while playing an infinite-loop schedule defers through stop-fade then resumes and keeps looping`() {
        val engine = newInfiniteEngine(totalTime = 10.0)
        engine.play(0.0)
        renderAll(engine, (1.0 * SR).toInt())

        engine.seekElapsed(23.4) // played=2, cursor=3.4
        // Render EXACTLY the stop-fade duration (0.12s = 5292 frames, exact at 44100Hz) so
        // finalizeStopFade() lands on the last processed sample with no frames left over to advance
        // the newly-resumed cursor past the target (see TransportStateTest's equivalent test/comment
        // for the full trace of why rendering MORE than STOP_FADE here would move elapsedSeconds
        // past 23.4).
        val stopFadeFrames = (0.12 * SR).toInt() // 5292, exact
        renderAll(engine, stopFadeFrames)

        assertTrue("seek while playing should resume playback", engine.isPlaying)
        assertEquals(23.4, engine.elapsedSeconds, 1e-6)
        assertTrue("infinite schedule must still report infinite loopsLeft after seek", engine.loopsLeft.isInfinite())

        // keep rendering across another seam (t=30s) to confirm it still loops normally afterward.
        // From elapsed=23.4 (played=2, cursor=3.4), rendering 7.0s more reaches cursor=10.4 -> wraps
        // once (seam at cursor=10, elapsed=30.0), landing at played=3, cursor=0.4, elapsed=30.4.
        renderAll(engine, (7.0 * SR).toInt())
        assertTrue(engine.isPlaying)
        assertEquals(3, engine.played)
        assertEquals(30.4, engine.elapsedSeconds, 1e-3)
    }

    @Test
    fun `stopReset on an infinite-loop schedule resets played and cursor to zero`() {
        val engine = newInfiniteEngine(totalTime = 10.0)
        engine.play(0.0)
        renderAll(engine, (25.0 * SR).toInt()) // well past two seams

        engine.stopReset()
        renderAll(engine, (1.0 * SR).toInt())

        assertFalse(engine.isPlaying)
        assertEquals(0, engine.played)
        assertEquals(0.0, engine.elapsedSeconds, 1e-9)
        assertTrue("loopsLeft remains infinite for a loops=0 schedule even after reset", engine.loopsLeft.isInfinite())
    }
}
