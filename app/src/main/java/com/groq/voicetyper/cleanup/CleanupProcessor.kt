package com.groq.voicetyper.cleanup

// ── V2 HOOK (wired by main agent in TranscriptionSessionManager) ──
// applyCleanupThenFormat(): dictionary -> maybeCleanup (online-only, OFF by
// default, skipped for Agent Mode + offline) -> V1 AppAwareFormatter.
// Order preserved at all 3 sites: streaming Final, offline batch, online batch.

import android.content.Context
import android.util.Log
import com.groq.voicetyper.SecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Slice V2: optional online-only LLM cleanup (filler-word removal, grammar /
 * punctuation / capitalization fixes). Completely additive, default OFF.
 *
 * - Effective LLM config via [resolveEffectiveLlm]: the explicit cleanup
 *   pick from the Text Formatting sheet when set, else the shared AI Agent
 *   Mode config (keys reused, no duplicate key management).
 * - Hidden system prompt (constants below, never user-editable); the style
 *   suffix is injected by the caller via [CleanupStyle].
 * - Plain-text chat response (NOT JSON CommandResult), temperature 0.0.
 * - Fail-safe: any error or blank response returns the input unchanged.
 *   Never throws; main-safe (Dispatchers.IO internally, like CommandProcessor).
 * - Never calls V1 ([com.groq.voicetyper.formatting.AppAwareFormatter]). The
 *   pipeline order (dictionary, V2, V1) is wired in TranscriptionSessionManager.
 */
object CleanupProcessor {
    private const val TAG = "CleanupProcessor"

    /** Hard cap on characters sent per cleanup request (mirrors CommandProcessor). */
    const val MAX_INPUT_CHARS = 5000

    /** Low temperature for deterministic cleanup edits. 0.0 = strictest. */
    internal const val TEMPERATURE = 0.0

    /**
     * Hard-constraint cleaner prompt. Written for weak/entry-level models:
     * short sentences, numbered rules, explicit NEVERs, two mini-examples.
     * The model must preserve facts verbatim and only fix surface form.
     */
    internal const val BASE_SYSTEM_PROMPT =
        "You clean raw voice-typing transcripts. Input is one dictation. " +
            "HARD RULES. " +
            "1. NEVER invent, add, or remove facts, names, numbers, or items. " +
            "2. NEVER reword or reorder meaning. Keep every word the user said unless it is filler. " +
            "3. ONLY remove filler: um, uh, like, you know, false starts. " +
            "4. ONLY fix grammar, punctuation, and capitalization. " +
            "5. If speech lists items (one two three, or 1 2 3), format as numbered lines: 1. item. Keep item words exact. Example: in: i am going to the market to buy the following items one apples two bananas three milk. out: I am going to the market to buy the following items:\n1. Apples\n2. Bananas\n3. Milk. " +
            "6. If style is email/formal, keep sentences and greeting structure. Do not lowercase. " +
            "OUTPUT. Return ONLY the cleaned transcript. No quotes. No explanation. No preamble. If unsure, return the input unchanged."

    internal const val FORMAL_SUFFIX = " Style: formal. Keep capitalization and periods."
    internal const val CASUAL_SUFFIX = " Style: casual. Use lighter punctuation."
    internal const val VERY_CASUAL_SUFFIX = " Style: very casual. Minimal punctuation, natural casing."

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Injectable HTTP layer so unit tests never hit the network. Production
     * path is [postChatCompletion]; tests replace [httpCall] with a fake.
     */
    internal var httpCall: suspend (
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String
    ) -> String? = { baseUrl, apiKey, model, systemPrompt, userText ->
        postChatCompletion(baseUrl, apiKey, model, systemPrompt, userText)
    }

    /** Builds the hidden system prompt for the given style. Pure helper. */
    fun buildSystemPrompt(style: CleanupStyle): String {
        val suffix = when (style) {
            CleanupStyle.FORMAL -> FORMAL_SUFFIX
            CleanupStyle.CASUAL -> CASUAL_SUFFIX
            CleanupStyle.VERY_CASUAL -> VERY_CASUAL_SUFFIX
        }
        return BASE_SYSTEM_PROMPT + suffix
    }

    /**
     * Maps a caller-supplied category name to a [CleanupStyle]. Unknown or
     * blank names fall back to FORMAL so the main agent can pass V1 category
     * strings through without coupling to this enum. Pure helper.
     */
    fun styleForName(name: String?): CleanupStyle {
        return when (name?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_')) {
            "CASUAL" -> CleanupStyle.CASUAL
            "VERY_CASUAL" -> CleanupStyle.VERY_CASUAL
            else -> CleanupStyle.FORMAL
        }
    }

    /** Truncates overlong input to [MAX_INPUT_CHARS]. Pure helper. */
    fun truncateForRequest(text: String): String {
        return if (text.length > MAX_INPUT_CHARS) text.substring(0, MAX_INPUT_CHARS) else text
    }

    /**
     * Pure guard matrix: true when cleanup must be skipped and the input
     * returned unchanged. Kept separate from [maybeCleanup] so the full
     * matrix is unit-testable without Android or network dependencies.
     */
    fun shouldSkip(
        cleanupEnabled: Boolean,
        rawText: String,
        llmApiKey: String?,
        isOfflineActive: Boolean
    ): Boolean {
        return !cleanupEnabled ||
            rawText.isBlank() ||
            llmApiKey.isNullOrBlank() ||
            isOfflineActive
    }

    /**
     * Resolves the effective LLM config: the explicit cleanup pick
     * (preset + model from the Text Formatting sheet) when set, else the
     * shared AI Agent Mode config. API keys always come from the Agent
     * screen's stored keys, so no duplicate key management.
     * Returns Triple(baseUrl, apiKey, model); any blank means caller skips.
     */
    fun resolveEffectiveLlm(context: Context): Triple<String, String, String> {
        return try {
            val preset = CleanupPreferences.getCleanupPreset(context).lowercase()
            val savedModel = CleanupPreferences.getCleanupModel(context)
            if (preset.isNotBlank() && savedModel.isNotBlank()) {
                val apiKey = SecurityUtils.getProviderApiKey(context, "llm", preset) ?: ""
                val baseUrl = SecurityUtils.getLlmBaseUrl(context, preset)
                return Triple(baseUrl, apiKey, savedModel)
            }
            val agentPreset = SecurityUtils.getLlmPreset(context)
            Triple(
                SecurityUtils.getLlmBaseUrl(context, agentPreset),
                SecurityUtils.getProviderApiKey(context, "llm", agentPreset) ?: "",
                SecurityUtils.getLlmModel(context, agentPreset)
            )
        } catch (_: Exception) {
            Triple("", "", "")
        }
    }

    /**
     * Runs LLM cleanup when enabled, otherwise returns [rawText] unchanged.
     *
     * Skips (identity return, never throws) when: the feature flag is off,
     * [rawText] is blank, the resolved LLM key is blank, [isOfflineActive]
     * is true (defaults to true so offline stays truly offline unless the
     * caller explicitly opts in), or on any exception / blank model response.
     */
    suspend fun maybeCleanup(
        context: Context,
        rawText: String,
        category: CleanupStyle = CleanupStyle.FORMAL,
        isOfflineActive: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        try {
            // Cheap guards first: no prefs / keystore reads on these paths.
            if (rawText.isBlank() || isOfflineActive) return@withContext rawText
            if (!CleanupPreferences.isCleanupEnabled(context)) return@withContext rawText

            val (baseUrl, apiKey, model) = resolveEffectiveLlm(context)
            if (apiKey.isBlank() || baseUrl.isBlank() || model.isBlank()) return@withContext rawText

            val cleaned = httpCall(
                baseUrl,
                apiKey,
                model,
                buildSystemPrompt(category),
                truncateForRequest(rawText)
            )
            if (cleaned.isNullOrBlank()) rawText else cleaned.trim()
        } catch (_: Exception) {
            rawText
        }
    }

    /**
     * Production HTTP path: OpenAI-compatible chat/completions, plain-text
     * response. Returns null on any failure so the caller falls back to the
     * input. Mirrors CommandProcessor timeouts (30s) and the Mistral header.
     */
    private suspend fun postChatCompletion(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userText: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val messagesArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userText)
                })
            }
            val requestJson = JSONObject().apply {
                put("model", model)
                put("messages", messagesArray)
                put("temperature", TEMPERATURE)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = requestJson.toString().toRequestBody(mediaType)
            val finalUrl = SecurityUtils.buildApiUrl(baseUrl, "chat/completions")

            val requestBuilder = Request.Builder()
                .url(finalUrl)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
            if (baseUrl.contains("mistral.ai")) {
                requestBuilder.header("x-api-key", apiKey)
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                val bodyString = response.body?.string()
                if (!response.isSuccessful || bodyString.isNullOrEmpty()) return@withContext null
                try {
                    val responseJson = JSONObject(bodyString)
                    val choices = responseJson.getJSONArray("choices")
                    val message = choices.getJSONObject(0).getJSONObject("message")
                    message.getString("content")
                } catch (_: Exception) {
                    Log.w(TAG, "Failed to parse cleanup response; returning input unchanged.")
                    null
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Cleanup network request failed; returning input unchanged.", e)
            null
        } catch (_: Exception) {
            null
        }
    }
}

/** V2 style suffix selector. The caller injects the style; prompt stays hidden. */
enum class CleanupStyle {
    FORMAL,
    CASUAL,
    VERY_CASUAL
}
