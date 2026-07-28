package com.groq.voicetyper.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class ApkDownloadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL)
            ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Missing download URL"))
        val expectedSize = inputData.getLong(KEY_EXPECTED_SIZE, 0L)
        val expectedSha256 = inputData.getString(KEY_EXPECTED_SHA256) ?: ""

        val updatesDir = File(context.filesDir, "updates")
        if (!updatesDir.exists()) {
            updatesDir.mkdirs()
        }

        // Clean up any old download files prior to starting new download
        val tempFile = File(updatesDir, "app-update.tmp")
        val finalApkFile = File(updatesDir, "app-update.apk")
        if (tempFile.exists()) tempFile.delete()
        if (finalApkFile.exists()) finalApkFile.delete()

        val client = OkHttpClient.Builder().build()
        val request = Request.Builder()
            .url(downloadUrl)
            .addHeader("User-Agent", "Fluence-Transcribe-Android")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    workDataOf(KEY_ERROR to "HTTP error ${response.code} downloading update APK")
                )
            }

            val body = response.body
                ?: return@withContext Result.failure(workDataOf(KEY_ERROR to "Empty download body"))

            val contentLength = if (expectedSize > 0) expectedSize else body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    var lastProgressUpdate = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isStopped) {
                            tempFile.delete()
                            return@withContext Result.failure(workDataOf(KEY_ERROR to "Download cancelled"))
                        }

                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 300 || totalBytesRead == contentLength) {
                            lastProgressUpdate = now
                            val percent = if (contentLength > 0) {
                                ((totalBytesRead * 100) / contentLength).toInt()
                            } else 0

                            setProgress(
                                workDataOf(
                                    KEY_PROGRESS_BYTES to totalBytesRead,
                                    KEY_TOTAL_BYTES to contentLength,
                                    KEY_PROGRESS_PERCENT to percent
                                )
                            )
                        }
                    }
                }
            }

            // 1. File Size Validation
            if (expectedSize > 0 && tempFile.length() != expectedSize) {
                tempFile.delete()
                return@withContext Result.failure(
                    workDataOf(
                        KEY_ERROR to "File size mismatch. Expected $expectedSize bytes, got ${tempFile.length()} bytes."
                    )
                )
            }

            // 2. SHA-256 Integrity Check
            if (expectedSha256.isNotBlank()) {
                val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!computedHash.equals(expectedSha256, ignoreCase = true)) {
                    tempFile.delete()
                    return@withContext Result.failure(
                        workDataOf(
                            KEY_ERROR to "SHA-256 hash validation failed. Expected: $expectedSha256, Computed: $computedHash"
                        )
                    )
                }
            }

            // Rename temp file to final APK file
            if (!tempFile.renameTo(finalApkFile)) {
                return@withContext Result.failure(workDataOf(KEY_ERROR to "Failed to finalize downloaded APK file"))
            }

            Result.success(workDataOf(KEY_APK_PATH to finalApkFile.absolutePath))
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            Result.failure(workDataOf(KEY_ERROR to "Download error: ${e.localizedMessage}"))
        }
    }

    companion object {
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_EXPECTED_SIZE = "expected_size"
        const val KEY_EXPECTED_SHA256 = "expected_sha256"

        const val KEY_PROGRESS_BYTES = "progress_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_PROGRESS_PERCENT = "progress_percent"

        const val KEY_APK_PATH = "apk_path"
        const val KEY_ERROR = "error_message"
    }
}
