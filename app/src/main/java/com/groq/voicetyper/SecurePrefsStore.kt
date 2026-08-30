package com.groq.voicetyper

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.ConcurrentHashMap

/**
 * Guarded access to EncryptedSharedPreferences stores.
 *
 * Android's keystore can invalidate its keys (PIN/biometric change, device
 * restore, key eviction). In that state [EncryptedSharedPreferences.create]
 * throws on every call, which — without a guard — crashes the app on each
 * startup. This object:
 *
 *  1. Never lets the exception escape (treat as signed out instead of crash).
 *  2. Erases the encrypted file ONLY when the key is permanently invalidated;
 *     transient failures (e.g. device locked at first-unlock) leave the file
 *     intact so nothing is destroyed that a later healthy keystore could read.
 *  3. Degrades to a non-persistent in-memory store — plaintext is never
 *     written to disk in the encrypted file's place (see AccountStore's
 *     documented invariant).
 */
object SecurePrefsStore {

    private const val TAG = "SecurePrefsStore"

    private val inMemoryStores = ConcurrentHashMap<String, MutableMap<String, Any?>>()

    fun open(context: Context, encryptedName: String): SharedPreferences {
        val masterKey = try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: GeneralSecurityException) {
            return degrade(encryptedName, e)
        } catch (e: IOException) {
            return degrade(encryptedName, e)
        }

        return try {
            EncryptedSharedPreferences.create(
                context,
                encryptedName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: GeneralSecurityException) {
            if (isPermanentInvalidation(e)) {
                // Data is unrecoverable (key evicted/invalidated). Quarantine the
                // unusable file so a later fresh session starts clean.
                runCatching { context.deleteSharedPreferences(encryptedName) }
            }
            degrade(encryptedName, e)
        } catch (e: IOException) {
            degrade(encryptedName, e)
        }
    }

    /** True when the keystore key is permanently gone (not a transient lock). */
    internal fun isPermanentInvalidation(cause: Throwable?): Boolean {
        var current = cause
        while (current != null) {
            if (current is KeyPermanentlyInvalidatedException) return true
            if (current.message?.contains("permanently invalidated", ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
    }

    private fun degrade(encryptedName: String, cause: Throwable): SharedPreferences {
        Log.w(TAG, "Encrypted store '$encryptedName' unavailable; treating as signed out. ${cause}")
        return InMemorySharedPreferences(encryptedName)
    }

    /** True when [prefs] is the in-memory fallback (keystore unavailable). */
    fun isDegraded(prefs: SharedPreferences): Boolean = prefs is DegradedPrefs

    /** Marker for the non-persistent error fallback (see [InMemorySharedPreferences]). */
    internal interface DegradedPrefs

    /** In-memory, per-process, non-persistent store. Never touches disk. */
    private class InMemorySharedPreferences(name: String) : SharedPreferences, DegradedPrefs {

        private val values: MutableMap<String, Any?> = inMemoryStores.getOrPut(name) { ConcurrentHashMap() }

        override fun getAll(): MutableMap<String, *> = values

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            values[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = key != null && values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor(values)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            // No-op: per-process degenerate store has no persistence to notify about.
        }

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
            // No-op.
        }
    }

    private class Editor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) map[key] = values
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) map[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) map.remove(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            map.clear()
            return this
        }

        override fun commit(): Boolean = true

        override fun apply() {
            // In-memory only; nothing to persist.
        }
    }
}