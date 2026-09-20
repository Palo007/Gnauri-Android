package com.aistudio.gnauri.bvpmlc.synth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract §2 / §7 (Schedule.fromJson round-trip): valid JSON round-trips into the exact Schedule
 * shape described in contract §2 (JSON.stringify(sched) of parseGnaural's object), and garbage JSON
 * must not throw -- Schedule.fromJson wraps the Moshi parse in try/catch and returns null on any
 * failure (ScheduleModel.kt fromJson), per contract §2's "unknown/missing fields must not crash".
 *
 * These tests build JSON strings by hand (never parsing real .gnaural XML, per the WP4 task rules)
 * and never touch android.* -- Moshi reflection runs fine on the plain JVM.
 */
class ScheduleJsonTest {

    private val fullJson = """
        {
          "title": "Deep Focus",
          "author": "Test Author",
          "desc": "A test schedule",
          "voices": [
            {
              "index": 0,
              "type": 0,
              "desc": "binaural1",
              "file": "",
              "muted": false,
              "visible": true,
              "mono": false,
              "points": [
                { "duration": 600, "beat": 10, "base": 200, "volL": 0.8, "volR": 0.8, "state": 1 },
                { "duration": 300, "beat": 4, "base": 150, "volL": 0.5, "volR": 0.5, "state": 1 }
              ]
            },
            {
              "index": 1,
              "type": 2,
              "desc": "rain.wav",
              "file": "rain.wav",
              "muted": true,
              "visible": true,
              "mono": true,
              "points": [
                { "duration": 900, "beat": 0, "base": 0, "volL": 0.3, "volR": 0.3, "state": 1 }
              ]
            }
          ],
          "totalTime": 900,
          "ovL": 0.9,
          "ovR": 0.85,
          "loops": 2,
          "stereoswap": true
        }
    """.trimIndent()

    @Test
    fun `valid full JSON round-trips into an equivalent Schedule`() {
        val sched = Schedule.fromJson(fullJson)
        assertNotNull(sched)
        requireNotNull(sched)

        assertEquals("Deep Focus", sched.title)
        assertEquals("Test Author", sched.author)
        assertEquals("A test schedule", sched.desc)
        assertEquals(900.0, sched.totalTime, 1e-9)
        assertEquals(0.9, sched.ovL, 1e-9)
        assertEquals(0.85, sched.ovR, 1e-9)
        assertEquals(2, sched.loops)
        assertTrue(sched.stereoswap)

        assertEquals(2, sched.voices.size)
        val v0 = sched.voices[0]
        assertEquals(0, v0.index)
        assertEquals(0, v0.type)
        assertEquals("binaural1", v0.desc)
        assertEquals(false, v0.muted)
        assertEquals(true, v0.visible)
        assertEquals(false, v0.mono)
        assertEquals(2, v0.points.size)
        assertEquals(600.0, v0.points[0].duration, 1e-9)
        assertEquals(10.0, v0.points[0].beat, 1e-9)
        assertEquals(200.0, v0.points[0].base, 1e-9)
        assertEquals(0.8, v0.points[0].volL, 1e-9)
        assertEquals(0.8, v0.points[0].volR, 1e-9)
        assertEquals(1, v0.points[0].state)

        val v1 = sched.voices[1]
        assertEquals(2, v1.type)
        assertEquals("rain.wav", v1.desc)
        assertEquals("rain.wav", v1.file)
        assertTrue(v1.muted)
        assertTrue(v1.mono)
        assertEquals(1, v1.points.size)

        // and the Schedule built directly from these values should produce an engine that plays
        // without crashing, closing the loop from JSON -> Schedule -> engine.
        val engine = SynthEngine(SR)
        engine.setFadeOut30s(false)
        engine.setAudioBuffer("rain.wav", FloatArray(50) { it.toFloat() })
        engine.setSchedule(sched)
        engine.play(0.0)
        val out = renderAll(engine, (0.05 * SR).toInt())
        assertTrue(allFiniteAndBounded(out))
    }

    @Test
    fun `minimal JSON with only some fields present fills in defaults`() {
        // contract §2: unknown/missing fields must not crash; defaults: muted=false, mono=false,
        // state=1 (per ScheduleModel.kt doc comments and Point/Voice defaults).
        val minimal = """{ "title": "Bare", "voices": [ { "points": [ { "duration": 10 } ] } ] }"""
        val sched = Schedule.fromJson(minimal)
        assertNotNull(sched)
        requireNotNull(sched)

        assertEquals("Bare", sched.title)
        assertEquals("—", sched.author) // default (ScheduleModel.kt: val author: String = "—")
        assertEquals(0.0, sched.totalTime, 1e-9) // default
        assertEquals(1.0, sched.ovL, 1e-9)
        assertEquals(1.0, sched.ovR, 1e-9)
        assertEquals(0, sched.loops) // default 0 == infinite
        assertEquals(false, sched.stereoswap)

        assertEquals(1, sched.voices.size)
        val v = sched.voices[0]
        assertEquals(false, v.muted)
        assertEquals(true, v.visible)
        assertEquals(false, v.mono)
        assertEquals(1, v.type) // Voice's declared default (ScheduleModel.kt: type: Int = 1)

        assertEquals(1, v.points.size)
        val p = v.points[0]
        assertEquals(10.0, p.duration, 1e-9)
        assertEquals(0.0, p.beat, 1e-9) // default
        assertEquals(0.0, p.base, 1e-9) // default
        assertEquals(1, p.state) // default
    }

    @Test
    fun `empty JSON object parses to an all-defaults Schedule`() {
        val sched = Schedule.fromJson("{}")
        assertNotNull(sched)
        requireNotNull(sched)
        assertEquals("Untitled schedule", sched.title)
        assertTrue(sched.voices.isEmpty())
        assertEquals(0.0, sched.totalTime, 1e-9)
    }

    @Test
    fun `garbage JSON does not throw and returns null`() {
        val garbageInputs = listOf(
            "not json at all {{{",
            "{ \"title\": \"unterminated string",
            "[1, 2, 3]", // valid JSON but not an object -> Moshi type mismatch, not a Kotlin exception
            "12345",
            "true",
            "",
            "   ",
            "{ \"voices\": \"this should be a list, not a string\" }",
            "{ \"totalTime\": \"not-a-number\" }",
            "null"
        )
        for (input in garbageInputs) {
            val result = try {
                Schedule.fromJson(input)
            } catch (e: Throwable) {
                throw AssertionError("Schedule.fromJson threw for input=<$input>: $e", e)
            }
            assertNull("expected null for garbage input <$input>, got $result", result)
        }
    }

    @Test
    fun `JSON with an unknown extra field does not crash and is simply ignored`() {
        val withExtra = """
            { "title": "X", "totalTime": 5, "someBrandNewField": { "nested": [1,2,3] },
              "voices": [ { "points": [ { "duration": 5 } ], "aFutureVoiceField": 42 } ] }
        """.trimIndent()
        val sched = Schedule.fromJson(withExtra)
        assertNotNull(sched)
        requireNotNull(sched)
        assertEquals("X", sched.title)
        assertEquals(5.0, sched.totalTime, 1e-9)
        assertEquals(1, sched.voices.size)
    }
}
