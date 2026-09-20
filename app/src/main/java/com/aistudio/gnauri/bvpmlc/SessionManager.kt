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
    private const val KEY_CH_L = "ch_l"
    private const val KEY_CH_R = "ch_r"

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val uriMapType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
    private val uriMapAdapter = moshi.adapter<Map<String, String>>(uriMapType)

    fun saveSession(
        context: Context,
        scheduleJson: String?,
        masterVolume: Double,
        fadeOut30s: Boolean,
        filename: String?,
        chL: Double,
        chR: Double
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
            putFloat(KEY_CH_L, chL.toFloat())
            putFloat(KEY_CH_R, chR.toFloat())
            commit()
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

    data class SessionData(
        val schedule: String?,
        val masterVolume: Double,
        val fadeOut30s: Boolean,
        val filename: String?,
        val chL: Double,
        val chR: Double
    )

    fun getSessionData(context: Context): SessionData? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val schedule = prefs.getString(KEY_LAST_SCHEDULE, null) ?: return null
        return SessionData(
            schedule = schedule,
            masterVolume = prefs.getFloat(KEY_MASTER_VOLUME, 1.0f).toDouble(),
            fadeOut30s = prefs.getBoolean(KEY_FADE_OUT_30S, false),
            filename = prefs.getString(KEY_CURRENT_FILENAME, null),
            chL = prefs.getFloat(KEY_CH_L, 1.0f).toDouble(),
            chR = prefs.getFloat(KEY_CH_R, 1.0f).toDouble()
        )
    }

    fun getLastSession(context: Context): String? {
        val data = getSessionData(context) ?: return null

        // Return a combined JSON for the JS bridge
        val map = mapOf<String, Any?>(
            "schedule" to data.schedule,
            "masterVolume" to data.masterVolume,
            "fadeOut30s" to data.fadeOut30s,
            "filename" to data.filename,
            "chL" to data.chL,
            "chR" to data.chR
        )
        val type = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        return moshi.adapter<Map<String, Any?>>(type).toJson(map)
    }
}
