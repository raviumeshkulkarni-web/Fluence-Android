package com.groq.voicetyper.formatting

import android.content.Context
import android.content.SharedPreferences

/**
 * Slice V1: persistent policy for rules-only app-aware text formatting.
 *
 * Snapshot pattern copied from PrivacyPreferences: reads serve the in-memory
 * snapshot, writes publish it synchronously and persist asynchronously.
 *
 * All defaults preserve current behavior: master switch OFF and no
 * per-package overrides.
 */
object FormattingPreferences {
    private const val PREFS_NAME = "fluence_prefs"
    private const val KEY_MASTER_ENABLED = "formatting_master_enabled"
    private const val KEY_PACKAGE_OVERRIDES = "formatting_package_overrides"

    private val lock = Any()

    @Volatile
    private var masterEnabledSnapshot = false

    /** Stored as "packageName:CATEGORY" entries. */
    @Volatile
    private var packageOverridesSnapshot: Map<String, String> = emptyMap()

    @Volatile
    private var registeredPreferences: SharedPreferences? = null

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == KEY_MASTER_ENABLED || key == KEY_PACKAGE_OVERRIDES) {
                publishSnapshot(preferences)
            }
        }

    fun isMasterEnabled(context: Context): Boolean {
        ensurePreferences(context)
        return masterEnabledSnapshot
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        synchronized(lock) {
            val preferences = ensurePreferences(context)
            masterEnabledSnapshot = enabled
            preferences.edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
        }
    }

    /** Per-package overrides as packageName -> category. */
    fun getOverrides(context: Context): Map<String, FormattingCategory> {
        ensurePreferences(context)
        return decodeCategories(packageOverridesSnapshot)
    }

    fun setOverride(context: Context, packageName: String, category: FormattingCategory?) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            val preferences = ensurePreferences(context)
            val updated = packageOverridesSnapshot.toMutableMap().apply {
                if (category == null || category == FormattingCategory.NEUTRAL) {
                    remove(packageName)
                } else {
                    put(packageName, category.name)
                }
            }
            packageOverridesSnapshot = updated
            preferences.edit().putStringSet(KEY_PACKAGE_OVERRIDES, encodeMap(updated)).apply()
        }
    }

    private fun styleForSnapshot(
        category: FormattingCategory,
        snapshot: Map<String, String>
    ): FormattingCategory {
        val raw = snapshot[category.name] ?: return category
        val parsed = FormattingCategory.fromName(raw)
        // A stored NEUTRAL means "Off"; any other value applies as-is.
        // fromName already fails corrupt values to NEUTRAL, which is safe.
        return parsed
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
        masterEnabledSnapshot =
            preferences.getBoolean(KEY_MASTER_ENABLED, false)
        packageOverridesSnapshot =
            decodeEntries(preferences.getStringSet(KEY_PACKAGE_OVERRIDES, emptySet()).orEmpty())
    }

    private fun decodeEntries(entries: Set<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (entry in entries) {
            val separator = entry.lastIndexOf(':')
            if (separator <= 0 || separator >= entry.length - 1) continue
            val key = entry.substring(0, separator)
            val value = entry.substring(separator + 1)
            if (key.isNotBlank() && value.isNotBlank()) {
                result[key] = value
            }
        }
        return result
    }

    private fun decodeCategories(entries: Map<String, String>): Map<String, FormattingCategory> {
        val result = mutableMapOf<String, FormattingCategory>()
        for ((key, value) in entries) {
            val parsed = FormattingCategory.fromName(value)
            if (parsed != FormattingCategory.NEUTRAL) {
                result[key] = parsed
            }
        }
        return result
    }

    private fun encodeMap(map: Map<String, String>): Set<String> {
        return map.map { (key, value) -> "$key:$value" }.toSet()
    }

    /** Test-only reset for JVM tests that share the process singleton. */
    internal fun resetForTests() {
        synchronized(lock) {
            registeredPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            registeredPreferences = null
            masterEnabledSnapshot = false
            packageOverridesSnapshot = emptyMap()
        }
    }
}
