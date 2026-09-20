package com.aistudio.gnauri.bvpmlc.synth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract §7 item 9: hasBuffer/normName lookup, including the `name+".wav"` fallback
 * (SynthEngine.hasBuffer / SynthEngine.normName, contract §3 and §9: "lowercase, strip path, trim").
 *
 * SynthEngine.hasBuffer(name) normalizes [name] and checks the buffer map first for the normalized
 * key, then for "<normalized>.wav" (SynthEngine.kt hasBuffer, matching index.html:810-814). This
 * means: if a buffer was STORED under a key that already ends in ".wav" (e.g. setAudioBuffer with
 * the raw display name "Tone.WAV"), looking it up by the bare name WITHOUT ".wav" must still
 * succeed via the fallback.
 */
class HasBufferNormNameTest {

    @Test
    fun `normName lowercases, strips path, and trims`() {
        val engine = SynthEngine(SR)
        assertEqualsNorm(engine, "Tone.wav", "tone.wav")
        assertEqualsNorm(engine, "  Tone.wav  ", "tone.wav")
        assertEqualsNorm(engine, "C:\\Users\\me\\Sounds\\Tone.wav", "tone.wav")
        assertEqualsNorm(engine, "/storage/emulated/0/Download/Tone.wav", "tone.wav")
        assertEqualsNorm(engine, "MIXED/Path\\Style/Tone.WAV", "tone.wav")
        assertEqualsNorm(engine, "noext", "noext")
    }

    private fun assertEqualsNorm(engine: SynthEngine, input: String, expected: String) {
        org.junit.Assert.assertEquals("normName($input)", expected, engine.normName(input))
    }

    @Test
    fun `hasBuffer finds an exact normalized-name match`() {
        val engine = SynthEngine(SR)
        engine.setAudioBuffer("tone.wav", floatArrayOf(0f, 1f, 0f, -1f))

        assertTrue(engine.hasBuffer("tone.wav"))
        assertTrue("case-insensitive", engine.hasBuffer("TONE.WAV"))
        assertTrue("path-stripped", engine.hasBuffer("/some/dir/tone.wav"))
        assertTrue("trimmed", engine.hasBuffer("  tone.wav  "))
        assertFalse(engine.hasBuffer("other.wav"))
    }

    @Test
    fun `hasBuffer falls back to name plus dot-wav when the bare name was requested`() {
        // Store the buffer keyed by its FULL filename including extension (normName keeps the
        // extension -- it only lowercases/strips-path/trims, per contract §3's normName spec).
        val engine = SynthEngine(SR)
        engine.setAudioBuffer("loop.wav", floatArrayOf(1f, 2f, 3f))

        // A caller/voice description that omits the extension ("loop") must still resolve via the
        // documented fallback: lookup normName(name) first ("loop" -- not found), then
        // normName(name)+".wav" ("loop.wav" -- found). (contract §3 hasBuffer, index.html:810-814)
        assertTrue("bare name without extension must resolve via the +\".wav\" fallback", engine.hasBuffer("loop"))
        assertTrue(engine.hasBuffer("Loop"))
        assertTrue(engine.hasBuffer("  LOOP  "))
        assertTrue(engine.hasBuffer("some/dir/loop"))
    }

    @Test
    fun `hasBuffer returns false for names with no matching buffer under either lookup`() {
        val engine = SynthEngine(SR)
        engine.setAudioBuffer("tone.wav", floatArrayOf(0f))

        assertFalse(engine.hasBuffer("missing"))
        assertFalse(engine.hasBuffer("missing.wav"))
        assertFalse(engine.hasBuffer(""))
    }

    @Test
    fun `clearAudioBuffers removes all entries so hasBuffer reports false afterward`() {
        val engine = SynthEngine(SR)
        engine.setAudioBuffer("tone.wav", floatArrayOf(0f, 1f))
        engine.setAudioBuffer("loop", floatArrayOf(0f, 1f)) // stored WITHOUT an extension this time

        assertTrue(engine.hasBuffer("tone.wav"))
        assertTrue(engine.hasBuffer("loop"))

        engine.clearAudioBuffers()

        assertFalse(engine.hasBuffer("tone.wav"))
        assertFalse(engine.hasBuffer("loop"))
        // and the +".wav" fallback for a name stored bare must also be gone
        assertFalse(engine.hasBuffer("loop.wav"))
    }

    @Test
    fun `setAudioBuffer keyed with an already-normalized name overwrites on re-registration`() {
        val engine = SynthEngine(SR)
        engine.setAudioBuffer("Tone.wav", floatArrayOf(1f))
        assertTrue(engine.hasBuffer("tone.wav"))

        // re-register under a differently-cased/path-prefixed variant of the SAME normalized name
        engine.setAudioBuffer("  DIR/tone.WAV  ", floatArrayOf(2f, 3f))
        assertTrue(engine.hasBuffer("tone.wav"))
    }
}
