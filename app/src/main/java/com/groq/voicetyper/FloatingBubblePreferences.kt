package com.groq.voicetyper

import android.content.Context

object FloatingBubblePreferences {
    private const val PREFS_NAME = "fluence_prefs"

    // Master on/off switch. Same key the accessibility service and the
    // Permissions screen already use — helpers only, value unchanged.
    const val KEY_BUBBLE_ENABLED = "floating_bubble_enabled"

    fun isBubbleEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_BUBBLE_ENABLED, false)
    }

    fun setBubbleEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BUBBLE_ENABLED, enabled)
            .apply()
    }

    // Opt-in visibility mode: show the bubble only while the soft keyboard
    // (IME window) is visible. Default OFF preserves the existing
    // focus-based behavior exactly; every reader must early-return when off.
    const val KEY_BUBBLE_IME_ONLY = "floating_bubble_ime_only"

    fun isImeOnly(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_BUBBLE_IME_ONLY, false)
    }

    fun setImeOnly(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BUBBLE_IME_ONLY, enabled)
            .apply()
    }

    // Collapsed-bubble style, INDEPENDENT from the expanded-pill theme.
    // original = today's colorful orb (fixed obsidian paints + logo PNG).
    // classic  = pre-orb amethyst glass equalizer (fixed obsidian paints).
    // minimal  = quiet mono mark (neutral paints + waveform vector).
    // Unknown values fall back to original (today's look).
    const val KEY_COLLAPSED_STYLE = "floating_bubble_collapsed_style"
    const val COLLAPSED_ORIGINAL = "original"
    const val COLLAPSED_CLASSIC = "classic"
    const val COLLAPSED_MINIMAL = "minimal"

    fun getCollapsedStyle(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_COLLAPSED_STYLE, COLLAPSED_ORIGINAL)
        return if (raw == COLLAPSED_MINIMAL || raw == COLLAPSED_CLASSIC) raw else COLLAPSED_ORIGINAL
    }

    fun setCollapsedStyle(context: Context, style: String) {
        val safe = if (style == COLLAPSED_MINIMAL || style == COLLAPSED_CLASSIC) style else COLLAPSED_ORIGINAL
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COLLAPSED_STYLE, safe)
            .apply()
    }

    // Day/night auto-switch (opt-in, default OFF = today's manual behavior).
    // When on, the overlay ignores the manual pill/collapsed selections above
    // and resolves to a fixed pair instead: light styles by day, dark styles
    // by night, so users never reconfigure twice a day. Manual prefs are
    // never overwritten; the switch resolves at read time only, and turning
    // follow off restores the exact manual look.
    // Day   = Light pill   + Minimal bubble (legible in bright light).
    // Night = Obsidian pill + Classic bubble (no glare).
    // Pair constants live below, next to the pill/collapsed constants they
    // alias (Kotlin const ordering): see DAY_PILL_THEME etc.
    const val KEY_FOLLOW_SYSTEM = "floating_bubble_follow_system"

    fun isFollowSystem(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FOLLOW_SYSTEM, false)
    }

    fun setFollowSystem(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FOLLOW_SYSTEM, enabled)
            .apply()
    }

    fun getEffectivePillTheme(context: Context, systemDark: Boolean): String {
        if (!isFollowSystem(context)) return getPillTheme(context)
        return if (systemDark) NIGHT_PILL_THEME else DAY_PILL_THEME
    }

    fun getEffectiveCollapsedStyle(context: Context, systemDark: Boolean): String {
        if (!isFollowSystem(context)) return getCollapsedStyle(context)
        return if (systemDark) NIGHT_COLLAPSED_STYLE else DAY_COLLAPSED_STYLE
    }

    const val KEY_OPACITY = "floating_bubble_opacity"
    const val DEFAULT_OPACITY = 0.35f
    const val MIN_OPACITY = 0.10f
    const val MAX_OPACITY = 1.00f

    // Expanded-pill theme preset. Unknown values fall back to obsidian (today's look).
    const val KEY_PILL_THEME = "floating_bubble_pill_theme"
    const val PILL_THEME_OBSIDIAN = "obsidian"
    const val PILL_THEME_MONO = "mono"
    const val PILL_THEME_HIGH_CONTRAST = "high_contrast"
    const val PILL_THEME_LIGHT = "light"

    // Day/night pair aliases (declared here: Kotlin const initializers cannot
    // forward-reference consts declared later in the object).
    const val DAY_PILL_THEME = PILL_THEME_LIGHT
    const val DAY_COLLAPSED_STYLE = COLLAPSED_MINIMAL
    const val NIGHT_PILL_THEME = PILL_THEME_OBSIDIAN
    const val NIGHT_COLLAPSED_STYLE = COLLAPSED_CLASSIC

    // Outer glow halo around the pill (collapsed orb included). Default on
    // preserves today's look; the dimmed idle branch never had a halo.
    const val KEY_GLOW_ENABLED = "floating_bubble_glow_enabled"

    fun isGlowEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_GLOW_ENABLED, true)
    }

    fun setGlowEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GLOW_ENABLED, enabled)
            .apply()
    }

    fun getPillTheme(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PILL_THEME, PILL_THEME_OBSIDIAN)
        return if (raw == PILL_THEME_MONO || raw == PILL_THEME_HIGH_CONTRAST || raw == PILL_THEME_LIGHT) raw
            else PILL_THEME_OBSIDIAN
    }

    fun setPillTheme(context: Context, theme: String) {
        val safe = if (theme == PILL_THEME_MONO || theme == PILL_THEME_HIGH_CONTRAST || theme == PILL_THEME_LIGHT) theme
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
