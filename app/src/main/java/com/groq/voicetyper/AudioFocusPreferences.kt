package com.groq.voicetyper

import android.content.Context

/**
 * Reads/writes the audio focus mode preference from SharedPreferences("fluence_prefs").
 * Stateless utility — no singletons, no caching. Defaults to OFF so the feature
 * is inert until the user opts in.
 *
 * Additive V2: OFF / DUCK / PAUSE. The legacy boolean [KEY_DUCKING_ENABLED] is
 * kept for migration only — [getMode] falls back to it when [KEY_AUDIO_FOCUS_MODE]
 * was never written, and [setMode] keeps it in sync so old readers stay coherent.
 */
enum class AudioFocusMode {
    OFF,
    DUCK,
    PAUSE,
}

object AudioFocusPreferences {
    private const val PREFS_NAME = "fluence_prefs"
    const val KEY_DUCKING_ENABLED = "audio_focus_ducking_enabled"
    const val KEY_AUDIO_FOCUS_MODE = "audio_focus_mode"

    fun getMode(context: Context): AudioFocusMode {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val stored = try {
                prefs.getString(KEY_AUDIO_FOCUS_MODE, null)
            } catch (_: ClassCastException) {
                null
            }
            if (stored != null) {
                return try {
                    AudioFocusMode.valueOf(stored)
                } catch (_: IllegalArgumentException) {
                    fallbackFromLegacy(prefs)
                }
            }
            return fallbackFromLegacy(prefs)
        } catch (_: Exception) {
            return AudioFocusMode.OFF
        }
    }

    fun setMode(context: Context, mode: AudioFocusMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AUDIO_FOCUS_MODE, mode.name)
            .putBoolean(KEY_DUCKING_ENABLED, mode == AudioFocusMode.DUCK)
            .apply()
    }

    fun isDuckingEnabled(context: Context): Boolean {
        return try {
            getMode(context) == AudioFocusMode.DUCK
        } catch (_: Exception) {
            false
        }
    }

    fun setDuckingEnabled(context: Context, enabled: Boolean) {
        setMode(context, if (enabled) AudioFocusMode.DUCK else AudioFocusMode.OFF)
    }

    fun isPauseEnabled(context: Context): Boolean {
        return try {
            getMode(context) == AudioFocusMode.PAUSE
        } catch (_: Exception) {
            false
        }
    }

    private fun fallbackFromLegacy(prefs: android.content.SharedPreferences): AudioFocusMode {
        return try {
            if (prefs.getBoolean(KEY_DUCKING_ENABLED, false)) AudioFocusMode.DUCK
            else AudioFocusMode.OFF
        } catch (_: Exception) {
            AudioFocusMode.OFF
        }
    }
}
