package com.groq.voicetyper.update

/**
 * Represents the machine-readable release.json metadata asset attached to a GitHub Release.
 */
data class ReleaseMetadata(
    val versionCode: Int,
    val versionName: String,
    val apkName: String,
    val apkSize: Long,
    val sha256: String,
    val minSupportedVersionCode: Int? = null,
    val mandatory: Boolean = false
)

/**
 * Result of checking GitHub for an update.
 */
sealed class UpdateCheckResult {
    data class UpdateAvailable(
        val metadata: ReleaseMetadata,
        val releaseNotes: String,
        val apkDownloadUrl: String,
        val releaseUrl: String
    ) : UpdateCheckResult()

    object UpToDate : UpdateCheckResult()
    object NotModified : UpdateCheckResult()
    data class Error(val message: String, val throwable: Throwable? = null) : UpdateCheckResult()
}
