package com.aistudio.gnauri.bvpmlc

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Manages persistence of the last played session using SharedPreferences.
 * Stores the schedule JSON, global settings, and external WAV URI references.
 */
object SessionManager {
    private const val PREFS_NAME = "gnauri_session"
    private const val KEY_LAST_SCHEDULE = "last_schedule_json"
    private const val KEY_WAV_URIS = "wav_uris"
    private const val KEY_MASTER_VOLUME = "master_volume"
    private const val KEY_FADE_OUT_30S = "fade_out_30s"
    private const val KEY_CURRENT_FILENAME = "current_filename"

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val uriMapType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    private val uriMapAdapter = moshi.adapter<Map<String, String>>(uriMapType)

    fun saveSession(
        context: Context,
        scheduleJson: String?,
        masterVolume: Double,
        fadeOut30s: Boolean,
        filename: String?
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uris = WavRegistry.getUris().mapValues { it.value.toString() }
        val urisJson = uriMapAdapter.toJson(uris)

        prefs.edit().apply {
            putString(KEY_LAST_SCHEDULE, scheduleJson)
            putString(KEY_WAV_URIS, urisJson)
            putFloat(KEY_MASTER_VOLUME, masterVolume.toFloat())
            putBoolean(KEY_FADE_OUT_30S, fadeOut30s)
            putString(KEY_CURRENT_FILENAME, filename)
            apply()
        }
    }

    fun restoreWavs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val urisJson = prefs.getString(KEY_WAV_URIS, null) ?: return
        try {
            val urisStr = uriMapAdapter.fromJson(urisJson) ?: return
            val uris = urisStr.mapValues { it.value.toUri() }
            WavRegistry.registerAll(context, uris)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getLastSession(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val schedule = prefs.getString(KEY_LAST_SCHEDULE, null) ?: return null
        val volume = prefs.getFloat(KEY_MASTER_VOLUME, 1.0f)
        val fade = prefs.getBoolean(KEY_FADE_OUT_30S, false)
        val filename = prefs.getString(KEY_CURRENT_FILENAME, null)

        // Return a combined JSON for the JS bridge
        val map = mapOf<String, Any?>(
            "schedule" to schedule,
            "masterVolume" to volume.toDouble(),
            "fadeOut30s" to fade,
            "filename" to filename
        )
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        return moshi.adapter<Map<String, Any?>>(type).toJson(map)
    }
}
