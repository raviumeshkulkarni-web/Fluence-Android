package com.groq.voicetyper

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurityUtils {
    private const val PREFS_NAME = "groq_voice_typer_secure_prefs"
    private const val KEY_API_KEY = "groq_api_key"

    private fun getSharedPrefs(context: Context): SharedPreferences {
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

    fun saveApiKey(context: Context, apiKey: String) {
        getSharedPrefs(context).edit().putString(KEY_API_KEY, apiKey.trim()).apply()
    }

    fun getApiKey(context: Context): String? {
        return getSharedPrefs(context).getString(KEY_API_KEY, null)
    }

    fun clearApiKey(context: Context) {
        getSharedPrefs(context).edit().remove(KEY_API_KEY).apply()
    }

    fun getSttPreset(context: Context): String {
        return getSharedPrefs(context).getString("stt_provider_preset", "groq") ?: "groq"
    }

    fun saveSttPreset(context: Context, preset: String) {
        getSharedPrefs(context).edit().putString("stt_provider_preset", preset).apply()
    }

    fun getSttLanguage(context: Context): String {
        return getSharedPrefs(context).getString("stt_language", "") ?: ""
    }

    fun saveSttLanguage(context: Context, code: String) {
        getSharedPrefs(context).edit().putString("stt_language", code).apply()
    }

    fun getSttBaseUrl(context: Context, preset: String): String {
        val defaultUrl = when (preset.lowercase()) {
            "groq" -> "https://api.groq.com/openai"
            "mistral" -> "https://api.mistral.ai"
            else -> ""
        }
        return getSharedPrefs(context).getString("stt_base_url_$preset", defaultUrl) ?: defaultUrl
    }

    fun saveSttBaseUrl(context: Context, preset: String, url: String) {
        getSharedPrefs(context).edit().putString("stt_base_url_$preset", url.trim()).apply()
    }

    fun getSttModel(context: Context, preset: String): String {
        val defaultModel = when (preset.lowercase()) {
            "groq" -> "whisper-large-v3"
            "mistral" -> "voxtral-mini-latest"
            else -> "whisper-1"
        }
        return getSharedPrefs(context).getString("stt_model_$preset", defaultModel) ?: defaultModel
    }

    fun saveSttModel(context: Context, preset: String, model: String) {
        getSharedPrefs(context).edit().putString("stt_model_$preset", model.trim()).apply()
    }

    fun getLlmPreset(context: Context): String {
        return getSharedPrefs(context).getString("llm_provider_preset", "groq") ?: "groq"
    }

    fun saveLlmPreset(context: Context, preset: String) {
        getSharedPrefs(context).edit().putString("llm_provider_preset", preset).apply()
    }

    fun getLlmBaseUrl(context: Context, preset: String): String {
        val defaultUrl = when (preset.lowercase()) {
            "groq" -> "https://api.groq.com/openai"
            "mistral" -> "https://api.mistral.ai"
            else -> ""
        }
        return getSharedPrefs(context).getString("llm_base_url_$preset", defaultUrl) ?: defaultUrl
    }

    fun saveLlmBaseUrl(context: Context, preset: String, url: String) {
        getSharedPrefs(context).edit().putString("llm_base_url_$preset", url.trim()).apply()
    }

    fun getLlmModel(context: Context, preset: String): String {
        val defaultModel = when (preset.lowercase()) {
            "groq" -> "llama-3.3-70b-versatile"
            "mistral" -> "mistral-large-latest"
            else -> "gpt-4o"
        }
        return getSharedPrefs(context).getString("llm_model_$preset", defaultModel) ?: defaultModel
    }

    fun saveLlmModel(context: Context, preset: String, model: String) {
        getSharedPrefs(context).edit().putString("llm_model_$preset", model.trim()).apply()
    }

    fun getProviderApiKey(context: Context, providerType: String, preset: String): String? {
        val specificKey = getSharedPrefs(context).getString("${providerType}_api_key_${preset.lowercase()}", null)
        if (!specificKey.isNullOrBlank()) {
            return specificKey
        }
        if (preset.lowercase() == "groq") {
            return getApiKey(context)
        }
        return null
    }

    fun saveProviderApiKey(context: Context, providerType: String, preset: String, key: String) {
        getSharedPrefs(context).edit().putString("${providerType}_api_key_${preset.lowercase()}", key.trim()).apply()
        if (preset.lowercase() == "groq") {
            saveApiKey(context, key)
        }
    }

    fun clearProviderApiKey(context: Context, providerType: String, preset: String) {
        getSharedPrefs(context).edit().remove("${providerType}_api_key_${preset.lowercase()}").apply()
        if (preset.lowercase() == "groq") {
            clearApiKey(context)
        }
    }

    fun buildApiUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/v1", ignoreCase = true)) {
            "$base/${path.trimStart('/')}"
        } else {
            "$base/v1/${path.trimStart('/')}"
        }
    }
}
