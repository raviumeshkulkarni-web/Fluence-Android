package com.groq.voicetyper

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent policy for user-selected excluded application packages.
 *
 * This object deliberately owns only the policy and its storage. Foreground
 * application state is resolved independently by the accessibility service and
 * the IME because those components have different authoritative target sources.
 */
object PrivacyPreferences {
    private const val PREFS_NAME = "fluence_prefs"
    const val KEY_EXCLUDED_PACKAGES = "privacy_excluded_packages"

    private val lock = Any()

    @Volatile
    private var excludedPackagesSnapshot: Set<String> = emptySet()

    @Volatile
    private var registeredPreferences: SharedPreferences? = null

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (key == KEY_EXCLUDED_PACKAGES) {
                publishSnapshot(preferences)
            }
        }

    fun getExcludedPackages(context: Context): Set<String> {
        val preferences = ensurePreferences(context)
        if (registeredPreferences == null) {
            publishSnapshot(preferences)
        }
        return excludedPackagesSnapshot
    }

    fun isPackageExcluded(context: Context, packageName: String?): Boolean {
        val normalized = packageName?.trim().orEmpty()
        if (normalized.isEmpty()) return false
        return getExcludedPackages(context).contains(normalized)
    }

    fun setPackageExcluded(context: Context, packageName: String, excluded: Boolean) {
        val normalized = packageName.trim()
        if (normalized.isEmpty()) return

        synchronized(lock) {
            val preferences = ensurePreferences(context)
            val updated = excludedPackagesSnapshot.toMutableSet().apply {
                if (excluded) add(normalized) else remove(normalized)
            }
            publishSnapshot(updated)
            preferences.edit()
                .putStringSet(KEY_EXCLUDED_PACKAGES, updated)
                .apply()
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
        publishSnapshot(preferences.getStringSet(KEY_EXCLUDED_PACKAGES, emptySet()).orEmpty())
    }

    private fun publishSnapshot(packages: Set<String>) {
        excludedPackagesSnapshot = packages.mapNotNull { pkg ->
            pkg.trim().takeIf { it.isNotEmpty() }
        }.toSet()
    }

    /** Test-only reset for JVM tests that share the process singleton. */
    internal fun resetForTests() {
        synchronized(lock) {
            registeredPreferences?.unregisterOnSharedPreferenceChangeListener(preferenceListener)
            registeredPreferences = null
            excludedPackagesSnapshot = emptySet()
        }
    }
}
