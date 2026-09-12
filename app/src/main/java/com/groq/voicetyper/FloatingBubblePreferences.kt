package com.groq.voicetyper

import android.content.Context

object FloatingBubblePreferences {
    private const val PREFS_NAME = "fluence_prefs"
    const val KEY_OPACITY = "floating_bubble_opacity"
    const val DEFAULT_OPACITY = 0.35f
    const val MIN_OPACITY = 0.10f
    const val MAX_OPACITY = 1.00f

    // Expanded-pill theme preset. Unknown values fall back to obsidian (today's look).
    const val KEY_PILL_THEME = "floating_bubble_pill_theme"
    const val PILL_THEME_OBSIDIAN = "obsidian"
    const val PILL_THEME_MONO = "mono"
    const val PILL_THEME_HIGH_CONTRAST = "high_contrast"

    fun getPillTheme(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PILL_THEME, PILL_THEME_OBSIDIAN)
        return if (raw == PILL_THEME_MONO || raw == PILL_THEME_HIGH_CONTRAST) raw
            else PILL_THEME_OBSIDIAN
    }

    fun setPillTheme(context: Context, theme: String) {
        val safe = if (theme == PILL_THEME_MONO || theme == PILL_THEME_HIGH_CONTRAST) theme
            else PILL_THEME_OBSIDIAN
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PILL_THEME, safe)
            .apply()
    }

    fun getOpacity(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_OPACITY, DEFAULT_OPACITY).coerceIn(MIN_OPACITY, MAX_OPACITY)
    }

    fun setOpacity(context: Context, opacity: Float) {
        val clamped = opacity.coerceIn(MIN_OPACITY, MAX_OPACITY)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_OPACITY, clamped)
            .apply()
    }
}
