package com.aistudio.gnauri.bvpmlc.synth

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * RBJ Audio-EQ-Cookbook bandpass, "constant 0 dB peak gain" variant — the one the Web Audio API
 * uses for BiquadFilterNode.type = "bandpass" (contract §4.4, index.html:1029). Q is fixed at 0.7.
 *
 * Cookbook (constant 0 dB peak) bandpass:
 *   w0 = 2π f0 / Fs;  alpha = sin(w0)/(2 Q)
 *   b0 =  alpha,  b1 = 0,  b2 = -alpha
 *   a0 = 1 + alpha, a1 = -2 cos(w0), a2 = 1 - alpha
 * then normalize all b/a by a0. Direct-Form-I processing with per-sample state.
 *
 * Coefficients are recomputed only when the requested centre frequency moves ≥ 1 Hz from the last
 * computed centre (contract §4.4: "recompute when centre changes ≥ 1 Hz; per render block is fine").
 */
class Biquad(private val sampleRate: Int) {

    private val q: Double = 0.7

    // normalized coefficients
    private var nb0 = 1.0
    private var nb1 = 0.0
    private var nb2 = 0.0
    private var na1 = 0.0
    private var na2 = 0.0

    // Direct-Form-I state
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    private var lastCentre = Double.NaN

    /** Fresh-instance reset: clear filter state and force a coefficient recompute (contract §4.7). */
    fun reset() {
        x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0
        lastCentre = Double.NaN
    }

    /** Recompute coefficients if [centre] has moved ≥ 1 Hz since the last computation. */
    fun setCentre(centre: Double) {
        if (!lastCentre.isNaN() && kotlin.math.abs(centre - lastCentre) < 1.0) return
        lastCentre = centre

        val w0 = 2.0 * PI * centre / sampleRate
        val cosw0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)

        val b0 = alpha
        val b1 = 0.0
        val b2 = -alpha
        val a0 = 1.0 + alpha
        val a1 = -2.0 * cosw0
        val a2 = 1.0 - alpha

        val inv = 1.0 / a0
        nb0 = b0 * inv
        nb1 = b1 * inv
        nb2 = b2 * inv
        na1 = a1 * inv
        na2 = a2 * inv
    }

    /** Process one sample through the Direct-Form-I biquad. */
    fun process(x: Double): Double {
        val y = nb0 * x + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }
}
