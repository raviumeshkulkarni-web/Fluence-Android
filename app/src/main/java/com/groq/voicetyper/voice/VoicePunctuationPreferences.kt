package com.groq.voicetyper.voice

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent preferences for spoken punctuation conversion.
 * By default, spoken punctuation is opt-in (disabled) to ensure transcripts
 * stay verbatim and natural speech is not corrupted.
 */
object VoicePunctuationPreferences {
    private const val PREFS_NAME = "fluence_prefs"
    private const val KEY_SPOKEN_PUNCTUATION_ENABLED = "spoken_punctuation_enabled"

    private val lock = Any()

    @Volatile
    private var spokenPunctuationEnabledSnapshot = false

    @Volatile
    private var registeredPreferences: SharedPreferences? = null

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == KEY_SPOKEN_PUNCTUATION_ENABLED) {
                publishSnapshot(preferences)
            }
        }

    fun isSpokenPunctuationEnabled(context: Context): Boolean {
        ensurePreferences(context)
        return spokenPunctuationEnabledSnapshot
    }

    fun setSpokenPunctuationEnabled(context: Context, enabled: Boolean) {
        synchronized(lock) {
            val preferences = ensurePreferences(context)
            spokenPunctuationEnabledSnapshot = enabled
            preferences.edit().putBoolean(KEY_SPOKEN_PUNCTUATION_ENABLED, enabled).apply()
        }
    }

    private fun ensurePreferences(context: Context): SharedPreferences {
        val preferences = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (registeredPreferences !== preferences) {
            synchronized(lock) {
                if (registeredPreferences !== preferences) {
                    registeredPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
                    registeredPreferences = preferences
                    publishSnapshot(preferences)
                    preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
                }
            }
        }
        return preferences
    }

    private fun publishSnapshot(preferences: SharedPreferences) {
        spokenPunctuationEnabledSnapshot =
            preferences.getBoolean(KEY_SPOKEN_PUNCTUATION_ENABLED, false)
    }

    /** Test-only reset for JVM tests sharing singleton. */
    internal fun resetForTests() {
        synchronized(lock) {
            registeredPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            registeredPreferences = null
            spokenPunctuationEnabledSnapshot = false
        }
    }
}
