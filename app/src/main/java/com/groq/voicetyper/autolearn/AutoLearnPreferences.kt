package com.groq.voicetyper.autolearn

import android.content.Context
import android.content.SharedPreferences

object AutoLearnPreferences {
    private const val PREF_NAME = "fluence_prefs"
    private const val KEY_AUTO_LEARN_ENABLED = "auto_learn_enabled"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isAutoLearnEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_LEARN_ENABLED, true)
    }

    fun setAutoLearnEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_LEARN_ENABLED, enabled).apply()
    }
}
