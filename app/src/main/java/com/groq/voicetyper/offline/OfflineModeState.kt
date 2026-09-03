package com.groq.voicetyper.offline

import android.content.Context

/**
 * State machine for the offline transcription subsystem.
 * Read by VoiceInputIME and BubbleController to determine routing.
 */
enum class OfflineModeState {
    DISABLED,              // User has not enabled offline mode
    MODEL_NOT_DOWNLOADED,  // Toggle ON but model files not present
    DOWNLOADING_MODEL,     // Download in progress
    READY,                 // Model downloaded + verified, ready for inference
    ENGINE_LOADING,        // sherpa-onnx engine being initialized (lazy load)
    TRANSCRIBING           // Active inference in progress
}

/**
 * Reads/writes the offline mode preference from SharedPreferences("fluence_prefs").
 * Stateless utility — no singletons, no caching.
 */
object OfflinePreferences {
    private const val PREFS_NAME = "fluence_prefs"
    private const val KEY_OFFLINE_ENABLED = "offline_mode_enabled"
    private const val KEY_ENGINE_TYPE = "offline_engine_type"

    fun isOfflineModeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_OFFLINE_ENABLED, false)
    }

    fun setOfflineModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_OFFLINE_ENABLED, enabled)
            .apply()
    }

    /**
     * Returns the selected offline engine type.
     * Defaults to the recommended Fast (English) model for new users.
     */
    fun getEngineType(context: Context): OfflineEngineType {
        val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ENGINE_TYPE, null)
        return try {
            OfflineEngineType.valueOf(saved ?: OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING.name)
        } catch (_: Exception) {
            OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING
        }
    }

    fun setEngineType(context: Context, engineType: OfflineEngineType) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENGINE_TYPE, engineType.name)
            .apply()
    }
}
