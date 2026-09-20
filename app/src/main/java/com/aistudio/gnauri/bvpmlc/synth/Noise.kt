package com.aistudio.gnauri.bvpmlc.synth

/**
 * Stateful per-sample noise generators (contract §4.4). The JS engine pre-rendered these into
 * looping AudioBuffers (index.html:878-921); we generate sample-by-sample instead (no buffers,
 * no caches), so there is no audible loop point. Sample-exact parity is NOT required (stochastic);
 * algorithm + amplitude-scaling parity IS. Each generator has a [reset] for fresh-instance
 * semantics at loop seams (contract §4.7).
 */

/**
 * White noise — LCG producing uniform ±1 samples. Port of WhiteNoiseGenerator (index.html:708-716)
 * as used by _generateWhiteBuffer (index.html:896-906).
 *
 * JS: seed = (1664525*seed + 1013904223) >>> 0; return (seed / 0xffffffff)*2 - 1.
 * The >>> 0 keeps seed as an unsigned 32-bit int. In Kotlin we hold it in a Long masked to 32 bits;
 * the multiply 1664525*seed can exceed 32 bits, and JS's `*` on the >>>0 operand also computes the
 * full product before the following >>>0 truncates it, so masking after the add reproduces the
 * algorithm. Division uses 0xffffffff (4294967295.0) verbatim (index.html:714).
 */
class WhiteNoise(seed: Long = 1L) {
    private val initialSeed: Long = (seed and 0xffffffffL).let { if (it == 0L) 1L else it }
    private var seed: Long = initialSeed

    fun reset() {
        seed = initialSeed
    }

    fun next(): Double {
        // (1664525 * seed + 1013904223) >>> 0  (index.html:713)
        seed = (1664525L * seed + 1013904223L) and 0xffffffffL
        return (seed.toDouble() / 4294967295.0) * 2.0 - 1.0 // /0xffffffff *2 -1 (index.html:714)
    }
}

/**
 * Brown noise — leaky random walk. Port of BrownNoiseGenerator (index.html:725-741) used by
 * _generateBrownBuffer (index.html:911-920). Uses the SAME LCG as white for its _random() source
 * (index.html:730-733), accumulates white*0.02 (index.html:736), and hard-clamps to ±1
 * (index.html:737-738). No /1.02 leakage compensation — omitted deliberately in JS (index.html:718-724).
 */
class BrownNoise(seed: Long = 1L) {
    private val initialSeed: Long = (seed and 0xffffffffL).let { if (it == 0L) 1L else it }
    private var seed: Long = initialSeed
    private var last: Double = 0.0

    fun reset() {
        seed = initialSeed
        last = 0.0
    }

    private fun random(): Double {
        seed = (1664525L * seed + 1013904223L) and 0xffffffffL // index.html:731
        return (seed.toDouble() / 4294967295.0) * 2.0 - 1.0    // index.html:732
    }

    fun next(): Double {
        val white = random()
        last += white * 0.02                 // index.html:736
        if (last < -1.0) last = -1.0         // index.html:737
        else if (last > 1.0) last = 1.0      // index.html:738
        return last
    }
}

/**
 * Pink noise — Paul Kellet filter. Port of _noiseBuffer (index.html:878-891).
 * The white source there is Math.random() in [0,1) mapped to [-1,1) (index.html:884); we use a
 * java.util.Random for that white source (stochastic parity only). Filter coefficients and the
 * final 0.11 scaling are ported verbatim (index.html:885-888).
 */
class PinkNoise(seed: Long = 0L) {
    private val rng = java.util.Random(seed)
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var b3 = 0.0
    private var b4 = 0.0
    private var b5 = 0.0
    private var b6 = 0.0

    fun reset() {
        b0 = 0.0; b1 = 0.0; b2 = 0.0; b3 = 0.0; b4 = 0.0; b5 = 0.0; b6 = 0.0
    }

    fun next(): Double {
        val w = rng.nextDouble() * 2.0 - 1.0 // Math.random()*2-1 (index.html:884)
        b0 = 0.99886 * b0 + w * 0.0555179    // index.html:885
        b1 = 0.99332 * b1 + w * 0.0750759
        b2 = 0.96900 * b2 + w * 0.1538520    // index.html:886
        b3 = 0.86650 * b3 + w * 0.3104856
        b4 = 0.55000 * b4 + w * 0.5329522    // index.html:887
        b5 = -0.7616 * b5 - w * 0.0168980
        val out = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11 // index.html:888
        b6 = w * 0.115926
        return out
    }
}
