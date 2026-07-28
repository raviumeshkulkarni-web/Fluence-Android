package com.groq.voicetyper.update

import android.content.Context
import com.groq.voicetyper.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GitHubUpdateRepository(
    private val context: Context,
    private val repoOwner: String = DEFAULT_OWNER,
    private val repoName: String = DEFAULT_REPO,
    private val client: OkHttpClient = defaultClient
) {
    private val preferences = UpdatePreferences(context)

    suspend fun checkForUpdate(
        forceCheck: Boolean = false,
        localVersionCode: Int = BuildConfig.VERSION_CODE
    ): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/$repoOwner/$repoName/releases/latest"
            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "Fluence-Transcribe-Android/${BuildConfig.VERSION_NAME}")

            if (!forceCheck) {
                preferences.cachedEtag?.let { etag ->
                    if (etag.isNotBlank()) {
                        requestBuilder.addHeader("If-None-Match", etag)
                    }
                }
            }

            val response = client.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                if (resp.code == 304) {
                    return@withContext UpdateCheckResult.NotModified
                }

                if (!resp.isSuccessful) {
                    return@withContext UpdateCheckResult.Error(
                        "GitHub API request failed with HTTP ${resp.code}"
                    )
                }

                val etag = resp.header("ETag")
                if (!etag.isNullOrEmpty()) {
                    preferences.cachedEtag = etag
                }

                val bodyString = resp.body?.string()
                    ?: return@withContext UpdateCheckResult.Error("Empty response body from GitHub API")

                val releaseJson = JSONObject(bodyString)
                val releaseNotes = releaseJson.optString("body", "No release notes provided.")
                val releaseUrl = releaseJson.optString("html_url", "https://github.com/$repoOwner/$repoName/releases")
                val assetsArray = releaseJson.optJSONArray("assets") ?: JSONArray()

                var releaseJsonDownloadUrl: String? = null
                var apkDownloadUrl: String? = null

                for (i in 0 until assetsArray.length()) {
                    val asset = assetsArray.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    val downloadUrl = asset.optString("browser_download_url", "")

                    if (assetName.equals("release.json", ignoreCase = true)) {
                        releaseJsonDownloadUrl = downloadUrl
                    } else if (assetName.endsWith(".apk", ignoreCase = true)) {
                        if (apkDownloadUrl == null || assetName.contains("release", ignoreCase = true)) {
                            apkDownloadUrl = downloadUrl
                        }
                    }
                }

                if (apkDownloadUrl == null) {
                    return@withContext UpdateCheckResult.Error("No APK asset found in latest GitHub release")
                }

                val metadata: ReleaseMetadata = if (releaseJsonDownloadUrl != null) {
                    downloadReleaseMetadata(releaseJsonDownloadUrl)
                        ?: return@withContext UpdateCheckResult.Error("Failed to parse release.json metadata asset")
                } else {
                    // Fallback metadata if release.json asset is missing
                    val tagName = releaseJson.optString("tag_name", "v1.0.0").removePrefix("v")
                    ReleaseMetadata(
                        versionCode = localVersionCode + 1, // Assume newer if tag released without metadata
                        versionName = tagName,
                        apkName = "app-release.apk",
                        apkSize = 0L,
                        sha256 = ""
                    )
                }

                preferences.lastCheckedTimestamp = System.currentTimeMillis()

                if (metadata.versionCode > localVersionCode) {
                    UpdateCheckResult.UpdateAvailable(
                        metadata = metadata,
                        releaseNotes = releaseNotes,
                        apkDownloadUrl = apkDownloadUrl,
                        releaseUrl = releaseUrl
                    )
                } else {
                    UpdateCheckResult.UpToDate
                }
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error("Failed to check for updates: ${e.localizedMessage}", e)
        }
    }

    private fun downloadReleaseMetadata(url: String): ReleaseMetadata? {
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Fluence-Transcribe-Android/${BuildConfig.VERSION_NAME}")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val jsonString = response.body?.string() ?: return null
                val json = JSONObject(jsonString)

                ReleaseMetadata(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.optString("versionName", "1.0.0"),
                    apkName = json.optString("apkName", "app-release.apk"),
                    apkSize = json.optLong("apkSize", 0L),
                    sha256 = json.optString("sha256", ""),
                    minSupportedVersionCode = if (json.has("minSupportedVersionCode")) json.getInt("minSupportedVersionCode") else null,
                    mandatory = json.optBoolean("mandatory", false)
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_OWNER = "raviumeshkulkarni-web"
        const val DEFAULT_REPO = "Fluence-Android"

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}
