package com.groq.voicetyper.offline.v2

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class MoonshineV2ModelType(
    val dirName: String,
    val displayName: String,
    val baseUrl: String,
    val totalBytes: Long,
    val modelArch: Int,
    val fileSizes: Map<String, Long>
) {
    SMALL(
        dirName = "moonshine_v2_small",
        displayName = "Moonshine v2 Small Streaming",
        baseUrl = "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21/",
        totalBytes = 142300974L,
        modelArch = ai.moonshine.voice.JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING,
        fileSizes = mapOf(
            "adapter.ort" to 2870368L,
            "cross_kv.ort" to 5356536L,
            "decoder_kv.ort" to 81878600L,
            "encoder.ort" to 44148576L,
            "frontend.model.ort" to 26944L,
            "frontend.weights.ort" to 7769464L,
            "streaming_config.json" to 512L,
            "tokenizer.bin" to 249974L
        )
    ),
    MEDIUM(
        dirName = "moonshine_v2_medium",
        displayName = "Moonshine v2 Medium Streaming",
        baseUrl = "https://download.moonshine.ai/model/medium-streaming-en/quantized_26_08_21/",
        totalBytes = 269141623L,
        modelArch = ai.moonshine.voice.JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING,
        fileSizes = mapOf(
            "adapter.ort" to 3651296L,
            "cross_kv.ort" to 11643776L,
            "decoder_kv.ort" to 146972408L,
            "encoder.ort" to 94705376L,
            "frontend.model.ort" to 28720L,
            "frontend.weights.ort" to 11889560L,
            "streaming_config.json" to 513L,
            "tokenizer.bin" to 249974L
        )
    )
}

object MoonshineV2ModelManager {
    private const val TAG = "MoonshineV2ModelMgr"
    internal const val VERIFIED_MARKER = ".verified"

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

    private val progressMap = ConcurrentHashMap<MoonshineV2ModelType, MutableStateFlow<DownloadProgress>>().apply {
        put(MoonshineV2ModelType.SMALL, MutableStateFlow(DownloadProgress(DownloadState.IDLE)))
        put(MoonshineV2ModelType.MEDIUM, MutableStateFlow(DownloadProgress(DownloadState.IDLE)))
    }

    private val cancellationMap = ConcurrentHashMap<MoonshineV2ModelType, AtomicBoolean>().apply {
        put(MoonshineV2ModelType.SMALL, AtomicBoolean(false))
        put(MoonshineV2ModelType.MEDIUM, AtomicBoolean(false))
    }

    private val activeCalls = ConcurrentHashMap<MoonshineV2ModelType, okhttp3.Call?>()
    private val okHttpClient = OkHttpClient()

    fun getProgress(type: MoonshineV2ModelType): StateFlow<DownloadProgress> {
        return progressMap.getValue(type).asStateFlow()
    }

    fun getModelDir(context: Context, type: MoonshineV2ModelType): File {
        return File(context.filesDir, type.dirName)
    }

    fun isModelReadySync(context: Context, type: MoonshineV2ModelType): Boolean {
        val currentState = progressMap.getValue(type).value.state
        if (currentState == DownloadState.DOWNLOADING || currentState == DownloadState.VERIFYING) {
            return false
        }
        val dir = getModelDir(context, type)
        if (!dir.exists()) return false

        val marker = File(dir, VERIFIED_MARKER)
        if (marker.exists()) {
            return true
        }

        for ((fileName, minSize) in type.fileSizes) {
            val f = File(dir, fileName)
            if (!f.exists() || f.length() < minSize * 0.9) {
                return false
            }
        }
        return true
    }

    suspend fun isModelReady(context: Context, type: MoonshineV2ModelType): Boolean = withContext(Dispatchers.IO) {
        val currentState = progressMap.getValue(type).value.state
        if (currentState == DownloadState.DOWNLOADING || currentState == DownloadState.VERIFYING) {
            return@withContext false
        }

        val dir = getModelDir(context, type)
        if (!dir.exists()) return@withContext false

        val marker = File(dir, VERIFIED_MARKER)
        if (marker.exists()) {
            val expectedMarkerContent = type.fileSizes.entries.sortedBy { it.key }
                .joinToString("\n") { "${it.key}:${it.value}" }
            if (marker.readText().trim() == expectedMarkerContent) {
                return@withContext true
            }
        }

        for ((fileName, expectedSize) in type.fileSizes) {
            val f = File(dir, fileName)
            if (!f.exists() || f.length() != expectedSize) {
                marker.delete()
                return@withContext false
            }
        }

        val markerContent = type.fileSizes.entries.sortedBy { it.key }
            .joinToString("\n") { "${it.key}:${it.value}" }
        marker.writeText(markerContent)
        true
    }

    suspend fun downloadModel(context: Context, type: MoonshineV2ModelType): Boolean = withContext(Dispatchers.IO) {
        val isCancelled = cancellationMap.getValue(type)
        val progress = progressMap.getValue(type)

        if (progress.value.state == DownloadState.DOWNLOADING || progress.value.state == DownloadState.VERIFYING) {
            Log.w(TAG, "Download already in progress for ${type.name}")
            return@withContext false
        }

        isCancelled.set(false)
        val dir = getModelDir(context, type)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val marker = File(dir, VERIFIED_MARKER)
        marker.delete()

        progress.value = DownloadProgress(
            state = DownloadState.DOWNLOADING,
            bytesDownloaded = 0,
            totalBytes = type.totalBytes,
            currentFile = "Starting download..."
        )

        var totalDownloaded = 0L

        try {
            for ((fileName, expectedSize) in type.fileSizes) {
                if (isCancelled.get()) {
                    progress.value = DownloadProgress(DownloadState.CANCELLED)
                    return@withContext false
                }

                val targetFile = File(dir, fileName)
                val tempFile = File(dir, "$fileName.tmp")

                if (targetFile.exists() && targetFile.length() == expectedSize) {
                    totalDownloaded += expectedSize
                    progress.value = DownloadProgress(
                        state = DownloadState.DOWNLOADING,
                        bytesDownloaded = totalDownloaded,
                        totalBytes = type.totalBytes,
                        currentFile = fileName
                    )
                    continue
                }

                val fileUrl = type.baseUrl + fileName
                progress.value = DownloadProgress(
                    state = DownloadState.DOWNLOADING,
                    bytesDownloaded = totalDownloaded,
                    totalBytes = type.totalBytes,
                    currentFile = fileName
                )

                val request = Request.Builder().url(fileUrl).build()
                val call = okHttpClient.newCall(request)
                activeCalls[type] = call

                val response = call.execute()
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code} downloading $fileName")
                }

                val body = response.body ?: throw IOException("Empty body for $fileName")
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (isCancelled.get()) {
                                tempFile.delete()
                                progress.value = DownloadProgress(DownloadState.CANCELLED)
                                return@withContext false
                            }
                            output.write(buffer, 0, read)
                            totalDownloaded += read
                            progress.value = DownloadProgress(
                                state = DownloadState.DOWNLOADING,
                                bytesDownloaded = totalDownloaded,
                                totalBytes = type.totalBytes,
                                currentFile = fileName
                            )
                        }
                        output.flush()
                    }
                }

                if (tempFile.length() != expectedSize) {
                    tempFile.delete()
                    throw IOException("File size mismatch for $fileName (expected $expectedSize, got ${tempFile.length()})")
                }

                if (targetFile.exists()) {
                    targetFile.delete()
                }
                if (!tempFile.renameTo(targetFile)) {
                    throw IOException("Failed to rename $fileName.tmp to $fileName")
                }
            }

            progress.value = DownloadProgress(
                state = DownloadState.VERIFYING,
                bytesDownloaded = totalDownloaded,
                totalBytes = type.totalBytes,
                currentFile = "Finalizing..."
            )

            val markerContent = type.fileSizes.entries.sortedBy { it.key }
                .joinToString("\n") { "${it.key}:${it.value}" }
            marker.writeText(markerContent)

            progress.value = DownloadProgress(
                state = DownloadState.COMPLETED,
                bytesDownloaded = totalDownloaded,
                totalBytes = type.totalBytes,
                currentFile = "Completed"
            )
            Log.i(TAG, "Successfully downloaded and verified ${type.displayName}")
            true
        } catch (e: Exception) {
            if (isCancelled.get()) {
                progress.value = DownloadProgress(DownloadState.CANCELLED)
            } else {
                Log.e(TAG, "Error downloading ${type.name}", e)
                progress.value = DownloadProgress(
                    state = DownloadState.FAILED,
                    bytesDownloaded = totalDownloaded,
                    totalBytes = type.totalBytes,
                    errorMessage = e.localizedMessage ?: "Download failed"
                )
            }
            false
        } finally {
            activeCalls.remove(type)
        }
    }

    fun cancelDownload(type: MoonshineV2ModelType) {
        cancellationMap.getValue(type).set(true)
        activeCalls[type]?.cancel()
        activeCalls.remove(type)
        progressMap.getValue(type).value = DownloadProgress(DownloadState.CANCELLED)
    }

    fun deleteModel(context: Context, type: MoonshineV2ModelType): Boolean {
        cancelDownload(type)
        val dir = getModelDir(context, type)
        val deleted = if (dir.exists()) dir.deleteRecursively() else true
        progressMap.getValue(type).value = DownloadProgress(DownloadState.IDLE)
        return deleted
    }

    fun getModelSizeOnDisk(context: Context, type: MoonshineV2ModelType): Long {
        val dir = getModelDir(context, type)
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
