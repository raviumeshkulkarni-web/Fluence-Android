package com.groq.voicetyper.cleanup

import android.content.Context

/**
 * Slice V2 flags: optional online-only AI cleanup (default OFF) plus one
 * explicit cleanup model pick (preset + model, default blank = follow
 * AI Agent Mode).
 *
 * Stored in SharedPreferences("fluence_prefs") alongside the other Fluence
 * feature flags. Fail-safe: any read failure returns the inert default.
 */
object CleanupPreferences {
    private const val PREFS_NAME = "fluence_prefs"
    const val KEY_AI_CLEANUP_ENABLED = "ai_cleanup_enabled"
    const val KEY_CLEANUP_PRESET = "cleanup_provider_preset"
    const val KEY_CLEANUP_MODEL = "cleanup_model"

    fun isCleanupEnabled(context: Context): Boolean {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AI_CLEANUP_ENABLED, false)
        } catch (_: Exception) {
            false
        }
    }

    fun setCleanupEnabled(context: Context, enabled: Boolean) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AI_CLEANUP_ENABLED, enabled)
                .apply()
        } catch (_: Exception) {
            // Best-effort write; the feature simply stays off on failure.
        }
    }

    /** Cleanup provider preset; blank means follow AI Agent Mode. */
    fun getCleanupPreset(context: Context): String {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CLEANUP_PRESET, "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun setCleanupPreset(context: Context, preset: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CLEANUP_PRESET, preset.trim().lowercase())
                .apply()
        } catch (_: Exception) {
        }
    }

    /** Cleanup model name; blank means follow AI Agent Mode. */
    fun getCleanupModel(context: Context): String {
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_CLEANUP_MODEL, "") ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    fun setCleanupModel(context: Context, model: String) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CLEANUP_MODEL, model.trim())
                .apply()
        } catch (_: Exception) {
        }
    }

    /** Clears the explicit pick so cleanup follows AI Agent Mode again. */
    fun clearCleanupModel(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CLEANUP_PRESET)
                .remove(KEY_CLEANUP_MODEL)
                .apply()
        } catch (_: Exception) {
        }
    }
}
