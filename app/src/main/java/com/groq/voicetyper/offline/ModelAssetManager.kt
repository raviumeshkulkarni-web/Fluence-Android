package com.groq.voicetyper.offline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

object ModelAssetManager {
    const val MODEL_DIR_NAME = "sensevoice_v2"
    const val MODEL_FILENAME = "model.int8.onnx"
    const val TOKENS_FILENAME = "tokens.txt"

    internal var baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/"

    // SHA256 checksums (lowercased)
    internal var fileChecksums = mapOf(
        MODEL_FILENAME to "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
        TOKENS_FILENAME to "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
    )

    data class DownloadProgress(
        val state: DownloadState,
        val bytesDownloaded: Long = 0,
        val totalBytes: Long = 0,
        val currentFile: String = "",
        val errorMessage: String? = null
    )

    enum class DownloadState {
        IDLE, DOWNLOADING, VERIFYING, COMPLETED, FAILED, CANCELLED
    }

    private val _progress = MutableStateFlow(DownloadProgress(DownloadState.IDLE))
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    private val isCancelled = AtomicBoolean(false)
    private var activeCall: okhttp3.Call? = null
    private val okHttpClient = OkHttpClient()

    /**
     * Fast synchronous check for UI routing (checks existence and minimum size).
     * Does NOT calculate hashes to avoid blocking the main thread.
     */
    fun isModelReadySync(context: Context): Boolean {
        val currentState = _progress.value.state
        if (currentState == DownloadState.DOWNLOADING || currentState == DownloadState.VERIFYING) {
            return false
        }
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (!dir.exists() || !dir.isDirectory) return false

        val modelFile = File(dir, MODEL_FILENAME)
        val tokensFile = File(dir, TOKENS_FILENAME)

        // Check if both files exist and are larger than 0 bytes
        return modelFile.exists() && modelFile.length() > 10_000_000L &&
                tokensFile.exists() && tokensFile.length() > 1000L
    }

    /**
     * Comprehensive asynchronous check including SHA256 validation.
     */
    suspend fun isModelReady(context: Context): Boolean = withContext(Dispatchers.IO) {
        val currentState = _progress.value.state
        if (currentState == DownloadState.DOWNLOADING || currentState == DownloadState.VERIFYING) {
            return@withContext false
        }
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (!dir.exists() || !dir.isDirectory) return@withContext false

        val modelFile = File(dir, MODEL_FILENAME)
        val tokensFile = File(dir, TOKENS_FILENAME)

        if (!modelFile.exists() || !tokensFile.exists()) return@withContext false

        // Validate checksums
        val modelHash = calculateSHA256(modelFile)
        val tokensHash = calculateSHA256(tokensFile)

        return@withContext modelHash == fileChecksums[MODEL_FILENAME] &&
                tokensHash == fileChecksums[TOKENS_FILENAME]
    }

    fun getModelDir(context: Context): File {
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (!isModelReadySync(context)) {
            throw IllegalStateException("Model is not ready. Call downloadModel first.")
        }
        return dir
    }

    fun cancelDownload() {
        isCancelled.set(true)
        activeCall?.cancel()
        _progress.value = DownloadProgress(DownloadState.CANCELLED)
    }

    suspend fun deleteModel(context: Context): Long = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (!dir.exists()) return@withContext 0L

        var bytesFreed = 0L
        dir.listFiles()?.forEach { file ->
            bytesFreed += file.length()
            file.delete()
        }
        dir.delete()
        _progress.value = DownloadProgress(DownloadState.IDLE)
        return@withContext bytesFreed
    }

    suspend fun getModelSizeOnDisk(context: Context): Long = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (!dir.exists()) return@withContext 0L
        return@withContext dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    suspend fun downloadModel(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        isCancelled.set(false)
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val filesToDownload = listOf(TOKENS_FILENAME, MODEL_FILENAME)
        // Approximate total download size: 239MB for model + 300KB for tokens
        val totalDownloadSize = 239_550_000L 
        var cumulativeBytesDownloaded = 0L

        _progress.value = DownloadProgress(DownloadState.DOWNLOADING, 0, totalDownloadSize, "")

        for (fileName in filesToDownload) {
            if (isCancelled.get()) {
                cleanUpTempFiles(dir)
                return@withContext Result.failure(IOException("Download cancelled by user"))
            }

            val targetUrl = baseUrl + fileName
            val tmpFile = File(dir, "$fileName.tmp")
            val finalFile = File(dir, fileName)

            // If final file already exists and passes checksum, skip it
            val expectedHash = fileChecksums[fileName] ?: ""
            if (finalFile.exists() && calculateSHA256(finalFile) == expectedHash) {
                val size = finalFile.length()
                cumulativeBytesDownloaded += size
                _progress.value = DownloadProgress(
                    DownloadState.DOWNLOADING,
                    cumulativeBytesDownloaded,
                    totalDownloadSize,
                    fileName
                )
                continue
            }

            // Download file
            val result = downloadFile(targetUrl, tmpFile) { bytesInChunk ->
                cumulativeBytesDownloaded += bytesInChunk
                _progress.value = DownloadProgress(
                    DownloadState.DOWNLOADING,
                    cumulativeBytesDownloaded,
                    totalDownloadSize,
                    fileName
                )
            }

            if (result.isFailure) {
                cleanUpTempFiles(dir)
                if (isCancelled.get()) {
                    return@withContext Result.failure(IOException("Download cancelled by user"))
                }
                val err = result.exceptionOrNull()
                val errMsg = err?.localizedMessage ?: "Failed to download $fileName"
                _progress.value = DownloadProgress(DownloadState.FAILED, errorMessage = errMsg)
                return@withContext Result.failure(err ?: IOException(errMsg))
            }

            // Verify checksum
            _progress.value = DownloadProgress(
                DownloadState.VERIFYING,
                cumulativeBytesDownloaded,
                totalDownloadSize,
                fileName
            )

            val calculatedHash = calculateSHA256(tmpFile)
            if (calculatedHash != expectedHash) {
                tmpFile.delete()
                cleanUpTempFiles(dir)
                val errMsg = "Verification failed for $fileName: checksum mismatch"
                _progress.value = DownloadProgress(DownloadState.FAILED, errorMessage = errMsg)
                return@withContext Result.failure(IOException(errMsg))
            }

            // Atomic rename
            if (!tmpFile.renameTo(finalFile)) {
                tmpFile.delete()
                cleanUpTempFiles(dir)
                val errMsg = "Failed to finalize file $fileName"
                _progress.value = DownloadProgress(DownloadState.FAILED, errorMessage = errMsg)
                return@withContext Result.failure(IOException(errMsg))
            }
        }

        _progress.value = DownloadProgress(DownloadState.COMPLETED, totalDownloadSize, totalDownloadSize)
        return@withContext Result.success(Unit)
    }

    private fun downloadFile(
        url: String,
        destFile: File,
        onProgress: (Int) -> Unit
    ): Result<Unit> {
        val request = Request.Builder().url(url).build()
        val call = okHttpClient.newCall(request)
        activeCall = call

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("Unexpected response code: ${response.code}"))
                }
                val body = response.body ?: return Result.failure(IOException("Response body is null"))
                
                destFile.outputStream().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (isCancelled.get()) {
                                return Result.failure(IOException("Download cancelled"))
                            }
                            output.write(buffer, 0, bytesRead)
                            onProgress(bytesRead)
                        }
                    }
                }
                return Result.success(Unit)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            activeCall = null
        }
    }

    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        FileInputStream(file).use { input ->
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        val hashBytes = digest.digest()
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun cleanUpTempFiles(dir: File) {
        try {
            dir.listFiles()?.forEach { file ->
                if (file.name.endsWith(".tmp")) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            // Ignore clean up errors
        }
    }
}
