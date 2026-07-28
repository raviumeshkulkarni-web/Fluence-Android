package com.groq.voicetyper.update

import android.content.Context
import android.content.SharedPreferences

class UpdatePreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var lastCheckedTimestamp: Long
        get() = prefs.getLong(KEY_LAST_CHECKED, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECKED, value).apply()

    var skippedVersionCode: Int
        get() = prefs.getInt(KEY_SKIPPED_VERSION, -1)
        set(value) = prefs.edit().putInt(KEY_SKIPPED_VERSION, value).apply()

    var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CHECK, value).apply()

    var cachedEtag: String?
        get() = prefs.getString(KEY_CACHED_ETAG, null)
        set(value) = prefs.edit().putString(KEY_CACHED_ETAG, value).apply()

    var downloadedVersionCode: Int
        get() = prefs.getInt(KEY_DOWNLOADED_VERSION, -1)
        set(value) = prefs.edit().putInt(KEY_DOWNLOADED_VERSION, value).apply()

    fun resetSkippedVersion() {
        prefs.edit().remove(KEY_SKIPPED_VERSION).apply()
    }

    fun resetDownloadedVersion() {
        prefs.edit().remove(KEY_DOWNLOADED_VERSION).apply()
    }

    companion object {
        private const val PREFS_NAME = "fluence_update_prefs"
        private const val KEY_LAST_CHECKED = "last_checked_timestamp"
        private const val KEY_SKIPPED_VERSION = "skipped_version_code"
        private const val KEY_AUTO_CHECK = "auto_check_enabled"
        private const val KEY_CACHED_ETAG = "cached_etag"
        private const val KEY_DOWNLOADED_VERSION = "downloaded_version_code"
    }
}
