package com.groq.voicetyper.offline

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Captures raw 16kHz mono 16-bit PCM audio using Android's AudioRecord API.
 * Designed exclusively for the offline SenseVoice pipeline.
 *
 * DOES NOT touch the existing AudioRecorder (MediaRecorder/m4a workflow).
 * Each instance manages its own AudioRecord lifecycle.
 */
class OfflineAudioCapture {

    companion object {
        const val SAMPLE_RATE = 16000          // Hz — SenseVoice requirement
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE_SAMPLES = 512     // ~32ms per frame at 16kHz
        private const val TAG = "OfflineAudioCapture"
    }

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile
    private var isRunning = false

    interface AudioFrameListener {
        /**
         * @param samples Float array of normalized audio samples [-1.0, 1.0]
         * @param sampleCount Number of valid samples in the array
         */
        fun onAudioFrame(samples: FloatArray, sampleCount: Int)
    }

    /**
     * Starts capturing audio from the microphone.
     * Spawns a dedicated background thread for AudioRecord.read() loop.
     *
     * @param listener Callback for each audio frame (~32ms intervals)
     * @throws SecurityException if RECORD_AUDIO permission is not granted
     * @throws IllegalStateException if AudioRecord fails to initialize
     */
    @SuppressLint("MissingPermission")
    fun startCapture(listener: AudioFrameListener) {
        if (isRunning) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        val bufferSize = maxOf(minBufferSize, FRAME_SIZE_SAMPLES * 2 * 2)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize. State: ${record.state}")
        }

        audioRecord = record
        isRunning = true
        _isCapturing.value = true

        captureThread = Thread({
            readLoop(record, listener)
        }, "OfflineAudioCaptureThread").apply {
            priority = Thread.MAX_PRIORITY // Give it high priority to avoid frame drops
            start()
        }

        Log.d(TAG, "Audio capture started")
    }

    private fun readLoop(record: AudioRecord, listener: AudioFrameListener) {
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to start recording state", e)
            isRunning = false
            _isCapturing.value = false
            return
        }

        val shortBuffer = ShortArray(FRAME_SIZE_SAMPLES)
        val floatBuffer = FloatArray(FRAME_SIZE_SAMPLES)

        while (isRunning) {
            val readResult = record.read(shortBuffer, 0, FRAME_SIZE_SAMPLES)
            if (readResult <= 0) {
                if (readResult == AudioRecord.ERROR_INVALID_OPERATION) {
                    Log.e(TAG, "Invalid operation error reading audio")
                } else if (readResult == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "Bad value error reading audio")
                }
                break
            }

            var sumSquare = 0f
            var maxVal = 0f

            // Convert and normalize samples to [-1.0f, 1.0f]
            for (i in 0 until readResult) {
                val shortVal = shortBuffer[i].toFloat()
                val normalizedVal = shortVal / 32768.0f
                floatBuffer[i] = normalizedVal

                val absVal = abs(normalizedVal)
                if (absVal > maxVal) {
                    maxVal = absVal
                }
                sumSquare += normalizedVal * normalizedVal
            }

            // Calculate peak amplitude (max abs value) and RMS
            // Emit peak amplitude as it behaves closer to MediaRecorder's maxAmplitude
            _amplitude.value = maxVal.coerceIn(0f, 1f)

            // Pass normalized float samples to listener
            // Slice buffer to exact read length to prevent stale trailing data
            val samplesToSend = if (readResult < FRAME_SIZE_SAMPLES) floatBuffer.copyOf(readResult) else floatBuffer
            listener.onAudioFrame(samplesToSend, readResult)
        }

        try {
            record.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        _isCapturing.value = false
        Log.d(TAG, "Audio capture thread finished")
    }

    /**
     * Stops capturing and releases the AudioRecord instance.
     * Safe to call multiple times or when not capturing.
     */
    fun stopCapture() {
        if (!isRunning) return
        isRunning = false

        captureThread?.interrupt()
        try {
            captureThread?.join(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        captureThread = null

        audioRecord?.release()
        audioRecord = null
        _amplitude.value = 0f
        _isCapturing.value = false
        Log.d(TAG, "Audio capture stopped")
    }
}
