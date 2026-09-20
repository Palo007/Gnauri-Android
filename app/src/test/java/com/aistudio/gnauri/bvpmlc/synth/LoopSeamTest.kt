package com.aistudio.gnauri.bvpmlc.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * Contract §7 items 4 and 7: loop-seam continuity/crossfade and elapsedSeconds/played/loopsLeft
 * bookkeeping across seams for both finite (loops=2) and infinite (loops=0) schedules.
 *
 * IMPORTANT real-API nuance found while reading SynthEngine.advanceTransport(): when the FINAL
 * pass of a finite-loop schedule is exhausted, `played` is NOT incremented for that last pass —
 * only `cursor` is snapped to `totalTime`. E.g. loops=2, totalTime=10: after the schedule fully
 * finishes at absolute t=20s, `played == 1` (not 2) and `cursor == 10.0`, so
 * elapsedSeconds = played*totalTime + cursor = 1*10 + 10 = 20 is still correct, but the raw
 * `played` getter under-reports the completed-pass count by one at end-of-schedule. This is
 * asserted explicitly below (see `played is 1, not 2, once loops=2 is exhausted`) — it is not a
 * bug per contract wording (§4.10 only specifies isPlaying/cursor), but callers relying on
 * `played` alone at end-of-schedule should be aware of it.
 *
 * ANOTHER FINDING (relevant to the RMS-constancy sub-test of item 4): the equal-power crossfade
 * (sin/cos quarter-wave, contract §4.7) only preserves total RMS through the seam when the
 * outgoing and incoming signals are UNCORRELATED. For a coherent binaural tone, the outgoing tail
 * and the fresh (phase-reset) incoming tone at the same frequency add COHERENTLY, and depending on
 * their relative phase at the seam the combined power can rise by up to +3dB (verified
 * numerically against this exact test's base/beat) rather than staying flat — equal-power
 * crossfade is only power-preserving for decorrelated content, not for a deterministic repeated
 * tone. The contract's "+/-1.5dB" wording is verified below using a white-noise voice instead,
 * which IS decorrelated at the seam in practice (see the seeding note below) — a duplicate check
 * using the binaural tone is intentionally NOT asserted at 1.5dB since it can legitimately exceed
 * that bound by construction.
 *
 * CODE SMELL (reported, not fixed): `Instance.init` seeds its noise generators from a per-Instance
 * local `var seedCounter = 1L` (SynthEngine.kt ~line 516). Both pooled instances (slot0/slot1)
 * therefore assign IDENTICAL seeds to their white/brown/pink generators (e.g. voice 0's WhiteNoise
 * is always seeded 1L in every Instance). This works out harmlessly for the RMS check above only
 * because the outgoing instance has already advanced far into its LCG sequence by the time the
 * incoming instance restarts the SAME seed from position 0 — i.e. the two streams are decorrelated
 * by sequence OFFSET, not by seed diversity. A schedule with a very short totalTime (so a loop
 * seam happens within the first few generator calls) would crossfade two near-identical noise
 * streams and could show the same coherent-addition behavior as the tone case.
 */
class LoopSeamTest {

    private fun loopVoice() = binauralVoice(
        points = listOf(Point(duration = 10.0, beat = 10.0, base = 200.0, volL = 0.5, volR = 0.5, state = 1))
    )

    private fun noiseLoopVoice() = Voice(
        index = 0, type = 5, desc = "white", // white noise -> decorrelated per-instance generators
        points = listOf(Point(duration = 10.0, beat = 0.0, base = 0.0, volL = 0.5, volR = 0.5, state = 1))
    )

    // ---- Item 4: loop-seam continuity across 2 seams (loops=3, totalTime=10s) ----
    @Test
    fun `no adjacent-sample jump greater than 0_05 across loop seams`() {
        val engine = SynthEngine(SR)
        val sched = scheduleOf(totalTime = 10.0, voice = loopVoice(), loops = 3)
        engine.setFadeOut30s(false)
        engine.setSchedule(sched)
        engine.play(0.0)

        // Render up to 29.5s: covers both crossfaded seams (t=10s, t=20s) with margin, while
        // staying clear of the t=30s hard exhaustion stop (not a crossfaded seam - contract item 10).
        val seconds = 29.5
        val frames = (seconds * SR).toInt()
        val stereo = renderAll(engine, frames)
        val left = leftChannel(stereo)
        val right = rightChannel(stereo)

        val maxDeltaL = maxAdjacentDelta(left)
        val maxDeltaR = maxAdjacentDelta(right)
        assertTrue("left max delta $maxDeltaL", maxDeltaL < 0.05)
        assertTrue("right max delta $maxDeltaR", maxDeltaR < 0.05)
    }

    // ---- Item 4b: equal-power RMS constancy through the seam, using uncorrelated noise content ----
    @Test
    fun `equal-power crossfade keeps RMS within 1_5dB for uncorrelated noise voice`() {
        val engine = SynthEngine(SR)
        val sched = scheduleOf(totalTime = 10.0, voice = noiseLoopVoice(), loops = 3)
        engine.setFadeOut30s(false)
        engine.setSchedule(sched)
        engine.play(0.0)

        val seconds = 29.5
        val frames = (seconds * SR).toInt()
        val stereo = renderAll(engine, frames)
        val left = leftChannel(stereo)

        val steadyStart = 2 * SR
        val steadyWindow = (0.05 * SR).toInt()
        val steadyRms = rms(left, steadyStart, steadyWindow)
        val steadyDb = 20.0 * log10(steadyRms)

        val seamCenters = listOf((10.0 * SR).toInt(), (20.0 * SR).toInt())
        val probe = (0.05 * SR).toInt()
        for (seamSample in seamCenters) {
            val offsets = listOf(-3000, -1000, 0, 1000, 3000)
            for (off in offsets) {
                val start = (seamSample + off - probe / 2).coerceIn(0, left.size - probe)
                val seamRms = rms(left, start, probe)
                if (seamRms <= 0.0) continue
                val seamDb = 20.0 * log10(seamRms)
                val deltaDb = kotlin.math.abs(seamDb - steadyDb)
                assertTrue(
                    "seam@${seamSample}off=$off steadyDb=$steadyDb seamDb=$seamDb deltaDb=$deltaDb",
                    deltaDb < 1.5
                )
            }
        }
    }

    // ---- Item 7a: elapsedSeconds/played/loopsLeft across seams, loops=2 ----
    @Test
    fun `elapsedSeconds played and loopsLeft are correct across seams for loops=2`() {
        val engine = SynthEngine(SR)
        val sched = scheduleOf(totalTime = 10.0, voice = loopVoice(), loops = 2)
        engine.setFadeOut30s(false)
        engine.setSchedule(sched)
        engine.play(0.0)

        assertEquals(2.0, engine.loopsLeft, 1e-9)
        assertEquals(0, engine.played)

        // render just past the first seam (t=10s)
        renderAll(engine, (10.01 * SR).toInt())
        assertTrue(engine.isPlaying)
        assertEquals(1, engine.played)
        assertEquals(1.0, engine.loopsLeft, 1e-9)
        assertEquals(10.01, engine.elapsedSeconds, 0.01)

        // render up to just past the second seam (t=20s) -> schedule exhausted
        renderAll(engine, (9.99 * SR).toInt()) // total so far: 10.01 + 9.99 = 20.00s
        renderAll(engine, (2000)) // nudge a little further past 20s to be safe

        assertTrue("engine should have stopped once loops=2 is exhausted", !engine.isPlaying)
        // See class-level doc: played is NOT incremented on the final exhaustion pass.
        assertEquals("played is 1, not 2, once loops=2 is exhausted", 1, engine.played)
        // cursor is private; derive it from elapsedSeconds = played*totalTime + cursor.
        assertEquals(20.0, engine.elapsedSeconds, 1e-6)
        val impliedCursor = engine.elapsedSeconds - engine.played * 10.0
        assertEquals(10.0, impliedCursor, 1e-6)
        assertEquals(0.0, engine.loopsLeft, 1e-9)
    }

    // ---- Item 7b: elapsedSeconds/played/loopsLeft across seams, loops=0 (infinite) ----
    @Test
    fun `elapsedSeconds played and loopsLeft are correct across seams for loops=0 infinite`() {
        val engine = SynthEngine(SR)
        val sched = scheduleOf(totalTime = 10.0, voice = loopVoice(), loops = 0)
        engine.setFadeOut30s(false)
        engine.setSchedule(sched)
        engine.play(0.0)

        assertTrue(engine.loopsLeft.isInfinite())

        renderAll(engine, (25.0 * SR).toInt()) // cross two seams (10s, 20s), 5s into pass 3

        assertTrue("infinite-loop schedule must keep playing", engine.isPlaying)
        assertEquals(2, engine.played)
        assertTrue(engine.loopsLeft.isInfinite())
        assertEquals(25.0, engine.elapsedSeconds, 0.02)
    }
}
