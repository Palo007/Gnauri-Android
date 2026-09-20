package com.aistudio.gnauri.bvpmlc.synth

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Kotlin mirror of the schedule object built by parseGnaural (index.html:589-670) and shipped
 * across the bridge as JSON.stringify(sched). Parsed with Moshi reflection.
 *
 * Contract §2: unknown/missing fields must not crash. All fields carry defaults so a partial
 * JSON object still deserializes; volL/volR are already normalized to 0..1 by the JS parser
 * (index.html:650), so we store them verbatim.
 */
data class Point(
    val duration: Double = 0.0,
    val beat: Double = 0.0,
    val base: Double = 0.0,
    val volL: Double = 0.0,
    val volR: Double = 0.0,
    val state: Int = 1 // 0 = "off" anchor point; default 1 (index.html:651)
)

data class Voice(
    val index: Int = 0,
    val type: Int = 1, // unknown/missing handled as pink fallback in engine; default keeps parity
    val desc: String = "",
    val file: String? = null,
    val muted: Boolean = false,
    val visible: Boolean = true,
    val mono: Boolean = false,
    val points: List<Point> = emptyList()
)

data class Schedule(
    val title: String = "Untitled schedule",
    val author: String = "—",
    val desc: String = "—",
    val voices: List<Voice> = emptyList(),
    val totalTime: Double = 0.0,
    val ovL: Double = 1.0,
    val ovR: Double = 1.0,
    // loops: 0 (or absent) => infinite; N>0 => play N times total (index.html:605-606)
    val loops: Int = 0,
    val stereoswap: Boolean = false
) {
    companion object {
        private val moshi: Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        private val adapter = moshi.adapter(Schedule::class.java)

        /**
         * Parse a JSON string produced by JSON.stringify(sched). Returns null on any parse failure
         * or if the JSON is literally "null"; never throws (the bridge/driver handles null).
         */
        fun fromJson(json: String): Schedule? =
            try {
                adapter.fromJson(json)
            } catch (e: Exception) {
                null
            }
    }
}
