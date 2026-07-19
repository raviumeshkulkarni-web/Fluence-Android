package com.groq.voicetyper

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GroqClient {
    private const val TAG = "GroqClient"
    private const val TRANSCRIPTION_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL_WHISPER = "whisper-large-v3"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Sends the audio file directly to the Groq Whisper API for transcription.
     * This method takes **ownership** of [audioFile] and guarantees deletion on completion.
     *
     * @param apiKey The Groq API key.
     * @param audioFile The recorded audio file (will be deleted after use).
     * @param language Optional BCP-47 language code (e.g. "en"). Null for auto-detection.
     */
    suspend fun transcribe(
        apiKey: String,
        audioFile: File,
        language: String? = null
    ): Result<String> {
        return transcribe("https://api.groq.com/openai", MODEL_WHISPER, apiKey, audioFile, language)
    }

    suspend fun transcribe(
        baseUrl: String,
        model: String,
        apiKey: String,
        audioFile: File,
        language: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!audioFile.exists() || audioFile.length() == 0L) {
                return@withContext Result.failure(Exception("Audio file is empty or does not exist"))
            }

            val bodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("response_format", "json")
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/m4a".toMediaType())
                )

            // Only set language if explicitly provided; otherwise Whisper auto-detects
            if (!language.isNullOrBlank()) {
                bodyBuilder.addFormDataPart("language", language)
            }

            val requestBody = bodyBuilder.build()
            val finalUrl = SecurityUtils.buildApiUrl(baseUrl, "audio/transcriptions")

            val requestBuilder = Request.Builder()
                .url(finalUrl)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)

            if (baseUrl.contains("mistral.ai")) {
                requestBuilder.header("x-api-key", apiKey)
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                if (!response.isSuccessful) {
                    val errorMessage = parseErrorMessage(bodyString) ?: "HTTP Error ${response.code}"
                    Log.e(TAG, "Transcription failed (HTTP ${response.code}).")
                    return@withContext Result.failure(Exception(errorMessage))
                }

                if (bodyString.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Response body is empty"))
                }

                try {
                    val jsonObject = JSONObject(bodyString)
                    val text = jsonObject.getString("text")
                    Result.success(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse JSON response.", e)
                    Result.failure(Exception("Failed to parse transcription response"))
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network request failed", e)
            Result.failure(Exception("Network error. Please check your internet connection."))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during transcription", e)
            Result.failure(Exception("An unexpected error occurred: ${e.localizedMessage}"))
        } finally {
            // Explicitly delete the audio file after request is completed or failed
            try {
                if (audioFile.exists()) {
                    val deleted = audioFile.delete()
                    Log.d(TAG, "Temporary audio file deleted: $deleted")
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not delete temporary audio file", e)
            }
        }
    }

    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val finalUrl = SecurityUtils.buildApiUrl(baseUrl, "models")
            val requestBuilder = Request.Builder()
                .url(finalUrl)
                .header("Authorization", "Bearer $apiKey")
                .get()

            if (baseUrl.contains("mistral.ai")) {
                requestBuilder.header("x-api-key", apiKey)
            }

            val request = requestBuilder.build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                if (!response.isSuccessful) {
                    val errorMessage = parseErrorMessage(bodyString) ?: "HTTP Error ${response.code}"
                    Log.e(TAG, "Fetch models failed (HTTP ${response.code}).")
                    return@withContext Result.failure(Exception(errorMessage))
                }

                if (bodyString.isNullOrEmpty()) {
                    return@withContext Result.failure(Exception("Response body is empty"))
                }

                try {
                    val jsonObject = JSONObject(bodyString)
                    val dataArray = jsonObject.getJSONArray("data")
                    val models = mutableListOf<String>()
                    for (i in 0 until dataArray.length()) {
                        val mObj = dataArray.getJSONObject(i)
                        models.add(mObj.getString("id"))
                    }
                    Result.success(models.sorted())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse models JSON response.", e)
                    Result.failure(Exception("Failed to parse models response"))
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network request for models failed", e)
            Result.failure(Exception("Network error. Please check your internet connection."))
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during model fetch", e)
            Result.failure(Exception("An unexpected error occurred: ${e.localizedMessage}"))
        }
    }

    private fun parseErrorMessage(responseBody: String?): String? {
        if (responseBody.isNullOrEmpty()) return null
        return try {
            val json = JSONObject(responseBody)
            if (json.has("error")) {
                val errorObj = json.getJSONObject("error")
                if (errorObj.has("message")) errorObj.getString("message") else null
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
