package com.groq.voicetyper

import android.content.Context

object FloatingBubblePreferences {
    private const val PREFS_NAME = "fluence_prefs"
    const val KEY_OPACITY = "floating_bubble_opacity"
    const val DEFAULT_OPACITY = 0.35f
    const val MIN_OPACITY = 0.10f
    const val MAX_OPACITY = 1.00f

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
