package com.groq.voicetyper.dictionary

import android.content.Context
import android.content.SharedPreferences

object DictionaryPreferences {
    private const val PREFS_NAME = "fluence_prefs"
    private const val KEY_DICTIONARY_ENABLED = "custom_dictionary_enabled"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isDictionaryEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_DICTIONARY_ENABLED, true)
    }

    fun setDictionaryEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_DICTIONARY_ENABLED, enabled).apply()
    }
}
