package com.groq.voicetyper

import android.content.Context

/**
 * Reads/writes the audio focus ducking preference from SharedPreferences("fluence_prefs").
 * Stateless utility — no singletons, no caching. Defaults to OFF so the feature
 * is inert until the user opts in.
 */
object AudioFocusPreferences {
    private const val PREFS_NAME = "fluence_prefs"
    const val KEY_DUCKING_ENABLED = "audio_focus_ducking_enabled"

    fun isDuckingEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DUCKING_ENABLED, false)
    }

    fun setDuckingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DUCKING_ENABLED, enabled)
            .apply()
    }
}
