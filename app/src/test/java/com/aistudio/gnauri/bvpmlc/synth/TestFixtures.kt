package com.aistudio.gnauri.bvpmlc.synth

import kotlin.math.sqrt

/**
 * Shared helpers for the synth JVM unit tests (contract §7). Not a test class itself — plain
 * functions used by the *Test.kt files in this package. Deterministic sample rate (44100) is used
 * throughout per the test-writing guidance.
 */
const val SR = 44100

/** Convenience: build a single-voice Schedule with one voice made of [points]. */
fun scheduleOf(
    totalTime: Double,
    voice: Voice,
    loops: Int = 1,
    ovL: Double = 1.0,
    ovR: Double = 1.0,
    stereoswap: Boolean = false
): Schedule = Schedule(
    title = "test",
    voices = listOf(voice),
    totalTime = totalTime,
    ovL = ovL,
    ovR = ovR,
    loops = loops,
    stereoswap = stereoswap
)

fun binauralVoice(
    points: List<Point>,
    mono: Boolean = false,
    index: Int = 0
): Voice = Voice(index = index, type = 0, desc = "binaural", mono = mono, points = points)

fun isoVoice(
    points: List<Point>,
    gapped: Boolean = false,
    index: Int = 0
): Voice = Voice(index = index, type = if (gapped) 4 else 3, desc = "iso", points = points)

/** Render [frames] total frames from [engine] in chunks of [chunk], appending into one FloatArray. */
fun renderAll(engine: SynthEngine, frames: Int, chunk: Int = 1024): FloatArray {
    val out = FloatArray(frames * 2)
    var done = 0
    val buf = FloatArray(chunk * 2)
    while (done < frames) {
        val n = minOf(chunk, frames - done)
        engine.render(buf, n)
        System.arraycopy(buf, 0, out, done * 2, n * 2)
        done += n
    }
    return out
}

/** Left-channel (even indices) view of an interleaved stereo buffer. */
fun leftChannel(interleaved: FloatArray): FloatArray {
    val n = interleaved.size / 2
    val l = FloatArray(n)
    for (i in 0 until n) l[i] = interleaved[i * 2]
    return l
}

fun rightChannel(interleaved: FloatArray): FloatArray {
    val n = interleaved.size / 2
    val r = FloatArray(n)
    for (i in 0 until n) r[i] = interleaved[i * 2 + 1]
    return r
}

/** Count zero crossings (sign changes) in a mono buffer. */
fun countZeroCrossings(mono: FloatArray): Int {
    var count = 0
    for (i in 1 until mono.size) {
        val prev = mono[i - 1]
        val cur = mono[i]
        if ((prev < 0f && cur >= 0f) || (prev > 0f && cur <= 0f)) count++
    }
    return count
}

/** Estimate frequency (Hz) from zero-crossing count over [seconds] of mono audio. */
fun zeroCrossingFrequency(mono: FloatArray, seconds: Double): Double =
    countZeroCrossings(mono) / (2.0 * seconds)

/** Max absolute sample-to-sample delta in a mono buffer. */
fun maxAdjacentDelta(mono: FloatArray): Float {
    var maxD = 0f
    for (i in 1 until mono.size) {
        val d = kotlin.math.abs(mono[i] - mono[i - 1])
        if (d > maxD) maxD = d
    }
    return maxD
}

/** RMS of a mono buffer window [start, start+len). */
fun rms(mono: FloatArray, start: Int, len: Int): Double {
    var sumSq = 0.0
    for (i in start until start + len) {
        val v = mono[i].toDouble()
        sumSq += v * v
    }
    return sqrt(sumSq / len)
}

fun rms(mono: FloatArray): Double = rms(mono, 0, mono.size)

/** True if every sample in the interleaved buffer is finite and within [-1, 1]. */
fun allFiniteAndBounded(interleaved: FloatArray): Boolean {
    for (v in interleaved) {
        if (v.isNaN() || v.isInfinite()) return false
        if (v < -1f || v > 1f) return false
    }
    return true
}
