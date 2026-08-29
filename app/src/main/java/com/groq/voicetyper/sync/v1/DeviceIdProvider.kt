package com.groq.voicetyper.sync.v1

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

object DeviceIdProvider {
    private const val PREFS_NAME = "fluence_sync_secure_prefs"
    private const val KEY_DEVICE_ID = "sync_device_id"

    fun getDeviceId(context: Context): String {
        // The device id is an identifier, not a secret. Keep the encrypted
        // store as the normal path, but do not make dictionary/snippet writes
        // fail when Android's keystore is temporarily unavailable (and allow
        // lightweight test contexts that do not provide a keystore).
        val prefs = runCatching { encryptedPrefs(context) }
            .getOrElse {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    private fun encryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
