package com.groq.voicetyper.sync.v1

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted access to the signed-in sync account email (frozen v1.1).
 *
 * CRITICAL: `fluence_sync_secure_prefs` is an EncryptedSharedPreferences
 * file — it MUST only be read through this ESP-backed accessor. Reading it
 * with plain SharedPreferences returns null/garbage (keys are SIV-encrypted)
 * and would silently break accountHash stamping.
 *
 * accountHash = SHA-256(lower(trim(email))) truncated to 16 hex chars —
 * the single canonical format used for stamping and sync_metadata keys.
 */
object AccountStore {
    private const val PREFS_NAME = "fluence_sync_secure_prefs"
    private const val KEY_ACCOUNT_EMAIL = "sync_account_email"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null
    private val prefsLock = Any()

    fun getAccountEmail(context: Context): String? {
        return encryptedPrefs(context.applicationContext).getString(KEY_ACCOUNT_EMAIL, null)
    }

    /** Internal: SyncAuthSession owns writes; this accessor is read-mostly. */
    internal fun writeAccountEmail(context: Context, email: String?) {
        val prefs = encryptedPrefs(context.applicationContext)
        if (email == null) prefs.edit().remove(KEY_ACCOUNT_EMAIL).apply()
        else prefs.edit().putString(KEY_ACCOUNT_EMAIL, email).apply()
    }

    fun currentAccountHash(context: Context): String? =
        AccountHash.of(getAccountEmail(context))

    private fun encryptedPrefs(context: Context): SharedPreferences {
        cachedPrefs?.let { return it }
        synchronized(prefsLock) {
            cachedPrefs?.let { return it }
            // Guarded build: keystore invalidation degrades to a signed-out
            // in-memory store rather than crashing the sync worker at startup.
            cachedPrefs = com.groq.voicetyper.SecurePrefsStore.open(context, PREFS_NAME)
            return cachedPrefs!!
        }
    }
}
