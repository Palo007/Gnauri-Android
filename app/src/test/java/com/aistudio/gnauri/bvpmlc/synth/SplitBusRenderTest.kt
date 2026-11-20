package com.aistudio.gnauri.bvpmlc.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Split-bus render: binaural-only "direct" bus vs. everything-else "fx" bus. */
class SplitBusRenderTest {

    private val binPts = listOf(Point(duration = 4.0, beat = 8.0, base = 220.0, volL = 0.5, volR = 0.5, state = 1))
    private val pinkPts = listOf(Point(duration = 4.0, beat = 0.0, base = 500.0, volL = 0.2, volR = 0.2, state = 1))

    private fun sched(bin: Boolean, pink: Boolean): Schedule {
        val v = mutableListOf<Voice>()
        if (bin) v += Voice(index = v.size, type = 0, desc = "binaural", mono = false, points = binPts)
        if (pink) v += Voice(index = v.size, type = 1, desc = "pink", points = pinkPts)
        return Schedule(title = "split", totalTime = 4.0, loops = 1, voices = v)
    }

    private fun engine(s: Schedule) = SynthEngine(SR).also { it.setSchedule(s); it.play(0.0) }

    private class Split(val direct: FloatArray, val fx: FloatArray)

    private fun renderSplit(s: Schedule, frames: Int): Split {
        val e = engine(s)
        val d = FloatArray(frames * 2)
        val x = FloatArray(frames * 2)
        val bd = FloatArray(1024 * 2)
        val bx = FloatArray(1024 * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(1024, frames - done)
            e.render(bd, bx, n)
            System.arraycopy(bd, 0, d, done * 2, n * 2)
            System.arraycopy(bx, 0, x, done * 2, n * 2)
            done += n
        }
        return Split(d, x)
    }

    private fun maxAbs(a: FloatArray): Float = a.maxOfOrNull { kotlin.math.abs(it) } ?: 0f

    @Test
    fun `direct plus fx equals single bus render`() {
        val s = sched(bin = true, pink = true)
        val frames = SR * 3
        val single = renderAll(engine(s), frames)
        val sp = renderSplit(s, frames)
        assertTrue(maxAbs(sp.direct) > 0.01f && maxAbs(sp.fx) > 0.01f)
        for (i in single.indices) {
            assertEquals(single[i].toDouble(), (sp.direct[i] + sp.fx[i]).toDouble(), 1e-6)
        }
    }

    @Test
    fun `binaural only schedule leaves fx silent`() {
        val sp = renderSplit(sched(bin = true, pink = false), SR)
        assertTrue(maxAbs(sp.direct) > 0.01f)
        assertTrue(maxAbs(sp.fx) < 1e-6f)
    }

    @Test
    fun `noise only schedule leaves direct silent`() {
        val sp = renderSplit(sched(bin = false, pink = true), SR)
        assertTrue(maxAbs(sp.fx) > 0.001f)
        assertTrue(maxAbs(sp.direct) < 1e-6f)
    }
}
