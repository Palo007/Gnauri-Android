package com.aistudio.gnauri.bvpmlc.synth

import kotlin.math.PI
import kotlin.math.sin

/**
 * Sine oscillator driven by a per-oscillator phase accumulator (contract §4.2):
 *   phase += 2π·f/sr each sample; the sample value is sin(phase). Frequency is set per sample so
 *   base/beat ramps track sampleVoice, but the phase is NEVER recomputed from absolute time —
 *   this is what keeps binaural tones and base sweeps click-free (contract test 2).
 *
 * WebAudio OscillatorNode is itself a phase accumulator; recreating one at each loop instance
 * resets its phase to 0, which is the "fresh instance" semantics we mirror by calling [reset]
 * (contract §4.7).
 */
class Oscillator(private val sampleRate: Int) {

    private var phase: Double = 0.0
    private val twoPiOverSr: Double = 2.0 * PI / sampleRate

    /** Fresh-instance reset: phase back to 0 (matches OscillatorNode rebuild — index.html:1091). */
    fun reset() {
        phase = 0.0
    }

    /**
     * Advance one sample at frequency [freq] (Hz) and return sin(phase).
     * Phase is wrapped into [0, 2π) to keep the accumulator from losing precision over long runs.
     */
    fun next(freq: Double): Double {
        val s = sin(phase)
        phase += twoPiOverSr * freq
        if (phase >= TWO_PI) {
            phase -= TWO_PI
            if (phase >= TWO_PI) phase %= TWO_PI // guard for very high freq / large blocks
        } else if (phase < 0.0) {
            // negative frequencies (base - beat/2 can go negative for large beats) wrap downward
            phase += TWO_PI
            if (phase < 0.0) phase = ((phase % TWO_PI) + TWO_PI) % TWO_PI
        }
        return s
    }

    companion object {
        private const val TWO_PI = 2.0 * PI
    }
}
