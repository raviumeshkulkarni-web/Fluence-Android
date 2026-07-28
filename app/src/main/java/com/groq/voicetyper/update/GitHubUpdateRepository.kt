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

                val tagName = releaseJson.optString("tag_name", "").removePrefix("v").trim()

                preferences.lastCheckedTimestamp = System.currentTimeMillis()

                if (releaseJsonDownloadUrl != null) {
                    val metadata = downloadReleaseMetadata(releaseJsonDownloadUrl)
                        ?: return@withContext UpdateCheckResult.Error("Failed to parse release.json metadata asset")

                    val downloadUrl = apkDownloadUrl
                        ?: return@withContext UpdateCheckResult.Error("No APK asset found in latest GitHub release")

                    if (metadata.versionCode > localVersionCode) {
                        UpdateCheckResult.UpdateAvailable(
                            metadata = metadata,
                            releaseNotes = releaseNotes,
                            apkDownloadUrl = downloadUrl,
                            releaseUrl = releaseUrl
                        )
                    } else {
                        UpdateCheckResult.UpToDate
                    }
                } else {
                    // release.json asset is missing from this GitHub release
                    val isNewerTag = tagName.isNotEmpty() && isVersionNewer(tagName, BuildConfig.VERSION_NAME)
                    if (isNewerTag) {
                        android.util.Log.w(
                            "GitHubUpdateRepo",
                            "Missing release.json metadata asset in GitHub release tag v$tagName"
                        )
                        UpdateCheckResult.Error("The latest update is incomplete and cannot be installed. Please try again later.")
                    } else {
                        // Current installed app is equal or newer than latest GitHub release tag
                        UpdateCheckResult.UpToDate
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GitHubUpdateRepo", "Failed to check for updates", e)
            UpdateCheckResult.Error("Unable to connect to update server. Please check your network and try again.", e)
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
        } catch (e: Exception) {
            android.util.Log.e("GitHubUpdateRepo", "Failed to parse release.json metadata", e)
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

        internal fun isVersionNewer(tagVersion: String, currentVersion: String): Boolean {
            return try {
                val cleanTag = tagVersion.removePrefix("v").trim()
                val cleanCurrent = currentVersion.removePrefix("v").trim()
                val tagParts = cleanTag.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
                val currentParts = cleanCurrent.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
                val maxLength = maxOf(tagParts.size, currentParts.size)
                for (i in 0 until maxLength) {
                    val tagPart = tagParts.getOrElse(i) { 0 }
                    val currentPart = currentParts.getOrElse(i) { 0 }
                    if (tagPart > currentPart) return true
                    if (tagPart < currentPart) return false
                }
                false
            } catch (_: Exception) {
                false
            }
        }
    }
}
