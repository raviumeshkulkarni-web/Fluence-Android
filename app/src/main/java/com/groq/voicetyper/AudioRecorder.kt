package com.groq.voicetyper

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null

    @Volatile
    private var isRecording = false

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var amplitudeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val lock = Any()

    fun startRecording(): Boolean {
        synchronized(lock) {
            if (isRecording) return false

            // Use cache directory for privacy (files remain internal to the app)
            val cacheFile = File(context.cacheDir, "groq_voice_record_${System.currentTimeMillis()}.m4a")
            outputFile = cacheFile

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            try {
                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(cacheFile.absolutePath)
                    setAudioSamplingRate(44100)
                    setAudioEncodingBitRate(96000)
                    prepare()
                    start()
                }
                // Only assign on success — avoids holding a reference to a failed recorder
                mediaRecorder = recorder
                isRecording = true
            } catch (e: IOException) {
                Log.e(TAG, "Recording preparation failed", e)
                safeRelease(recorder)
                cleanupFile()
                return false
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Recording start failed", e)
                safeRelease(recorder)
                cleanupFile()
                return false
            } catch (e: SecurityException) {
                Log.e(TAG, "Microphone permission denied at runtime", e)
                safeRelease(recorder)
                cleanupFile()
                return false
            }
        }

        // Start polling outside the lock to avoid holding it during coroutine work
        startAmplitudePolling()
        return true
    }

    private fun startAmplitudePolling() {
        amplitudeJob?.cancel()
        amplitudeJob = scope.launch {
            while (isRecording) {
                try {
                    val maxAmp = mediaRecorder?.maxAmplitude ?: 0
                    // Normalize 0–32767 to 0.0–1.0
                    val normalized = (maxAmp.toFloat() / 32767f).coerceIn(0f, 1f)
                    _amplitude.value = normalized
                } catch (e: IllegalStateException) {
                    // MediaRecorder was released between the null-check and the call
                    break
                }
                delay(50)
            }
        }
    }

    fun stopRecording(): File? {
        synchronized(lock) {
            if (!isRecording) return null

            isRecording = false
            amplitudeJob?.cancel()
            _amplitude.value = 0f

            try {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
            } catch (e: RuntimeException) {
                // stop() can fail if recording duration was too short
                Log.e(TAG, "Stop recording failed (recording too short?)", e)
                outputFile?.delete()
                outputFile = null
            } finally {
                mediaRecorder = null
            }

            return outputFile
        }
    }

    fun cancelRecording() {
        synchronized(lock) {
            isRecording = false
            amplitudeJob?.cancel()
            _amplitude.value = 0f

            try {
                mediaRecorder?.apply {
                    stop()
                    release()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cancel recording failed", e)
            } finally {
                mediaRecorder = null
                outputFile?.delete()
                outputFile = null
            }
        }
    }

    private fun safeRelease(recorder: MediaRecorder) {
        try {
            recorder.release()
        } catch (e: Exception) {
            Log.w(TAG, "Release failed during cleanup", e)
        }
    }

    private fun cleanupFile() {
        outputFile?.delete()
        outputFile = null
    }

    companion object {
        private const val TAG = "AudioRecorder"
    }
}
