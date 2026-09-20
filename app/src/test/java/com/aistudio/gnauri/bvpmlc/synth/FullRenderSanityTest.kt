package com.aistudio.gnauri.bvpmlc.synth

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract §7 item 6: full render of >= 3 representative schedules (binaural+noise mixes) must
 * never emit NaN/Inf/|x|>1 (contract §4.11 - render() sanitizes/clamps every sample).
 */
class FullRenderSanityTest {

    private fun renderSchedule(sched: Schedule, seconds: Double, fadeOut: Boolean = false): FloatArray {
        val engine = SynthEngine(SR)
        engine.setFadeOut30s(fadeOut)
        engine.setSchedule(sched)
        engine.play(0.0)
        return renderAll(engine, (seconds * SR).toInt())
    }

    @Test
    fun `binaural plus pink noise mix renders finite and bounded`() {
        val sched = Schedule(
            title = "mix1",
            totalTime = 8.0,
            loops = 1,
            voices = listOf(
                Voice(
                    index = 0, type = 0, desc = "binaural", mono = false,
                    points = listOf(
                        Point(duration = 4.0, beat = 8.0, base = 220.0, volL = 0.6, volR = 0.6, state = 1),
                        Point(duration = 4.0, beat = 2.0, base = 150.0, volL = 0.4, volR = 0.4, state = 1)
                    )
                ),
                Voice(
                    index = 1, type = 1, desc = "pink", // base > 1 on some segment -> exercises the biquad path
                    points = listOf(
                        Point(duration = 8.0, beat = 0.0, base = 500.0, volL = 0.3, volR = 0.3, state = 1)
                    )
                )
            )
        )
        val out = renderSchedule(sched, 7.9)
        assertTrue(allFiniteAndBounded(out))
    }

    @Test
    fun `gapped isochronic plus white noise mix renders finite and bounded`() {
        val sched = Schedule(
            title = "mix2",
            totalTime = 6.0,
            loops = 1,
            voices = listOf(
                Voice(
                    index = 0, type = 4, desc = "iso",
                    points = listOf(Point(duration = 6.0, beat = 6.0, base = 180.0, volL = 0.5, volR = 0.5, state = 1))
                ),
                Voice(
                    index = 1, type = 5, desc = "white",
                    points = listOf(Point(duration = 6.0, beat = 0.0, base = 0.0, volL = 0.35, volR = 0.35, state = 1))
                )
            )
        )
        val out = renderSchedule(sched, 5.9)
        assertTrue(allFiniteAndBounded(out))
    }

    @Test
    fun `brown noise plus mono binaural plus stereoswap renders finite and bounded`() {
        val sched = Schedule(
            title = "mix3",
            totalTime = 5.0,
            loops = 1,
            stereoswap = true,
            voices = listOf(
                Voice(
                    index = 0, type = 6, desc = "brown",
                    points = listOf(Point(duration = 5.0, beat = 0.0, base = 0.0, volL = 0.4, volR = 0.4, state = 1))
                ),
                Voice(
                    index = 1, type = 0, desc = "binaural-mono", mono = true,
                    points = listOf(Point(duration = 5.0, beat = 15.0, base = 100.0, volL = 0.6, volR = 0.6, state = 1))
                )
            )
        )
        val out = renderSchedule(sched, 4.9)
        assertTrue(allFiniteAndBounded(out))
    }

    @Test
    fun `unknown voice type falls back to pink noise and renders finite and bounded`() {
        val sched = Schedule(
            title = "mix4-unknown-type",
            totalTime = 4.0,
            loops = 1,
            voices = listOf(
                Voice(
                    index = 0, type = 99, desc = "unknown-fallback",
                    points = listOf(Point(duration = 4.0, beat = 0.0, base = 300.0, volL = 0.5, volR = 0.5, state = 1))
                )
            )
        )
        val out = renderSchedule(sched, 3.9)
        assertTrue(allFiniteAndBounded(out))
        // pink fallback should still produce a non-trivial signal
        assertTrue(rms(leftChannel(out)) > 0.01)
    }

    @Test
    fun `high volumes and master gain above unity still clamp to plus-minus 1`() {
        // ovL/ovR at max plausible and multiple loud voices summed -- exercises the final clamp
        // (contract sanitize()) rather than relying on voice volumes alone to stay in range.
        val sched = Schedule(
            title = "loud-mix",
            totalTime = 3.0,
            loops = 1,
            ovL = 1.0,
            ovR = 1.0,
            voices = listOf(
                Voice(
                    index = 0, type = 0, desc = "loud1",
                    points = listOf(Point(duration = 3.0, beat = 4.0, base = 200.0, volL = 1.0, volR = 1.0, state = 1))
                ),
                Voice(
                    index = 1, type = 0, desc = "loud2",
                    points = listOf(Point(duration = 3.0, beat = 4.0, base = 205.0, volL = 1.0, volR = 1.0, state = 1))
                ),
                Voice(
                    index = 2, type = 5, desc = "loud-white",
                    points = listOf(Point(duration = 3.0, beat = 0.0, base = 0.0, volL = 1.0, volR = 1.0, state = 1))
                )
            )
        )
        val engine = SynthEngine(SR)
        engine.setFadeOut30s(false)
        engine.setSchedule(sched)
        engine.setChannelGains(1.5, 1.5) // max allowed trim (contract §3 clamp [0,1.5])
        engine.play(0.0)
        val out = renderAll(engine, (2.9 * SR).toInt())
        assertTrue(allFiniteAndBounded(out))
    }
}
