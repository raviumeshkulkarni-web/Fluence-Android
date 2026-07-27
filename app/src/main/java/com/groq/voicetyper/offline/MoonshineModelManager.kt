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

/**
 * Manages download, verification, and lifecycle of Moonshine Base v1 model files.
 * Mirrors the implementation pattern of ModelAssetManager for SenseVoice.
 *
 * Model files (Moonshine Base v1 English int8):
 *   - preprocess.onnx (~14 MB)
 *   - encode.int8.onnx (~50 MB)
 *   - uncached_decode.int8.onnx (~122 MB)
 *   - cached_decode.int8.onnx (~100 MB)
 *   - tokens.txt (~437 KB)
 *
 * Download source: HuggingFace (csukuangfj/sherpa-onnx-moonshine-base-en-int8)
 */
object MoonshineModelManager {
    const val MODEL_DIR_NAME = "moonshine_base_v1"
    const val PREPROCESSOR_FILENAME = "preprocess.onnx"
    const val ENCODER_FILENAME = "encode.int8.onnx"
    const val UNCACHED_DECODER_FILENAME = "uncached_decode.int8.onnx"
    const val CACHED_DECODER_FILENAME = "cached_decode.int8.onnx"
    const val TOKENS_FILENAME = "tokens.txt"

    // Minimum file sizes for fast sync check (no hashing)
    private const val MIN_PREPROCESSOR_SIZE = 10_000_000L   // 10 MB
    private const val MIN_ENCODER_SIZE = 40_000_000L        // 40 MB
    private const val MIN_UNCACHED_DECODER_SIZE = 100_000_000L // 100 MB
    private const val MIN_CACHED_DECODER_SIZE = 80_000_000L // 80 MB
    private const val MIN_TOKENS_SIZE = 1000L               // 1 KB

    internal var baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-moonshine-base-en-int8/resolve/main/"

    // SHA256 checksums (lowercased)
    // These are Git LFS object hashes from the HuggingFace repo metadata.
    // They should be verified by downloading files and computing SHA256 before production use.
    internal var fileChecksums = mapOf(
        PREPROCESSOR_FILENAME to "ffa630d395c5ccf76f5d4954be5b882df76aaf6491519ec01fd82ea7a3819fb2",
        ENCODER_FILENAME to "7e38770f776f2e5583a53b052936005df2ba5c833d7e09c2a5fd796b94bf73e2",
        UNCACHED_DECODER_FILENAME to "c01f4b35093bcac20d352d23a75a539e772964579f9d024a90e5e6f09cae9987",
        CACHED_DECODER_FILENAME to "2db74e51cedf64a8b1be3c8192e0bb5e4923af0e90bd9e87f8e8771873f8ea03",
        TOKENS_FILENAME to "1165c2aeb9f72f457a83be2d459a09054f27490acd9b41bd43794dfd25e296ea"
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

        val preprocessorFile = File(dir, PREPROCESSOR_FILENAME)
        val encoderFile = File(dir, ENCODER_FILENAME)
        val uncachedDecoderFile = File(dir, UNCACHED_DECODER_FILENAME)
        val cachedDecoderFile = File(dir, CACHED_DECODER_FILENAME)
        val tokensFile = File(dir, TOKENS_FILENAME)

        return preprocessorFile.exists() && preprocessorFile.length() > MIN_PREPROCESSOR_SIZE &&
                encoderFile.exists() && encoderFile.length() > MIN_ENCODER_SIZE &&
                uncachedDecoderFile.exists() && uncachedDecoderFile.length() > MIN_UNCACHED_DECODER_SIZE &&
                cachedDecoderFile.exists() && cachedDecoderFile.length() > MIN_CACHED_DECODER_SIZE &&
                tokensFile.exists() && tokensFile.length() > MIN_TOKENS_SIZE
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

        val filesToCheck = listOf(
            PREPROCESSOR_FILENAME,
            ENCODER_FILENAME,
            UNCACHED_DECODER_FILENAME,
            CACHED_DECODER_FILENAME,
            TOKENS_FILENAME
        )

        for (fileName in filesToCheck) {
            val file = File(dir, fileName)
            if (!file.exists()) return@withContext false

            val expectedHash = fileChecksums[fileName]
            if (expectedHash.isNullOrEmpty()) continue // Skip verification if no hash available

            val actualHash = calculateSHA256(file)
            if (actualHash != expectedHash) return@withContext false
        }

        return@withContext true
    }

    fun getModelDir(context: Context): File {
        val dir = File(context.filesDir, MODEL_DIR_NAME)
        if (!isModelReadySync(context)) {
            throw IllegalStateException("Moonshine model is not ready. Call downloadModel first.")
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

        val filesToDownload = listOf(
            TOKENS_FILENAME,
            PREPROCESSOR_FILENAME,
            ENCODER_FILENAME,
            UNCACHED_DECODER_FILENAME,
            CACHED_DECODER_FILENAME
        )
        // Approximate total download size: ~287 MB
        val totalDownloadSize = 287_000_000L
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
            if (expectedHash.isNotEmpty() && finalFile.exists() && calculateSHA256(finalFile) == expectedHash) {
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

            // Verify checksum (skip if no checksum available)
            if (expectedHash.isNotEmpty()) {
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
