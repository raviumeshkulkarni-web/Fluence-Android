package com.groq.voicetyper.streaming

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
 * Designed exclusively for the real-time online streaming pipeline.
 *
 * DOES NOT touch AudioRecorder (MediaRecorder/m4a workflow) or OfflineAudioCapture.
 * Each instance manages its own dedicated AudioRecord lifecycle.
 */
class StreamingAudioCapture {

    companion object {
        const val SAMPLE_RATE = 16000          // 16kHz PCM
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE_SAMPLES = 640     // 40ms frames at 16kHz (1280 bytes)
        private const val TAG = "StreamingAudioCapture"
    }

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    @Volatile
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    
    @Volatile
    private var isRunning = false

    interface AudioFrameListener {
        /**
         * Callback fired on background thread with raw PCM 16-bit little-endian audio bytes.
         * @param pcmBytes ByteArray containing raw 16-bit PCM samples
         * @param length Number of valid bytes in pcmBytes
         */
        fun onAudioFrame(pcmBytes: ByteArray, length: Int)

        /**
         * Fired on the capture thread when the audio pipeline fails after capture
         * started (AudioRecord startRecording failure or a read error). Not fired
         * for a normal stopCapture().
         */
        fun onCaptureFailed(error: String) {}
    }


    /**
     * Starts capturing audio from microphone on a dedicated high-priority background thread.
     *
     * @param listener Callback for each audio frame (~40ms intervals)
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
        val bufferSize = maxOf(minBufferSize, FRAME_SIZE_SAMPLES * 2 * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize for streaming. State: ${record.state}")
        }

        audioRecord = record
        isRunning = true
        _isCapturing.value = true

        captureThread = Thread({
            readLoop(record, listener)
        }, "StreamingAudioCaptureThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        Log.d(TAG, "Streaming audio capture thread started")
    }

    private fun readLoop(record: AudioRecord, listener: AudioFrameListener) {
        var failure: String? = null
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Failed to start AudioRecord recording state", e)
            failure = "Could not start the audio capture: ${e.localizedMessage ?: "AudioRecord state error"}"
        }

        if (failure == null) {
            val byteBuffer = ByteArray(FRAME_SIZE_SAMPLES * 2) // 16-bit PCM = 2 bytes per sample

            while (isRunning) {
                val readResult = try {
                    record.read(byteBuffer, 0, byteBuffer.size)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error reading streaming audio", e)
                    failure = "Audio capture read failed: ${e.localizedMessage ?: "unknown error"}"
                    break
                }
                if (readResult <= 0) {
                    failure = when (readResult) {
                        AudioRecord.ERROR_INVALID_OPERATION -> "Audio capture read failed (invalid operation)"
                        AudioRecord.ERROR_BAD_VALUE -> "Audio capture read failed (bad value)"
                        AudioRecord.ERROR_DEAD_OBJECT -> "Audio capture read failed (recorder released)"
                        else -> "Audio capture stopped unexpectedly"
                    }
                    Log.e(TAG, failure)
                    break
                }

                // Calculate peak amplitude for visualization
                var maxVal = 0
                var i = 0
                while (i < readResult - 1) {
                    val sample = (byteBuffer[i].toInt() and 0xFF) or (byteBuffer[i + 1].toInt() shl 8)
                    val absVal = abs(sample.toShort().toInt())
                    if (absVal > maxVal) {
                        maxVal = absVal
                    }
                    i += 2
                }
                _amplitude.value = (maxVal.toFloat() / 32767f).coerceIn(0f, 1f)

                // Deliver raw PCM bytes to listener
                val frameBytes = if (readResult < byteBuffer.size) byteBuffer.copyOf(readResult) else byteBuffer
                listener.onAudioFrame(frameBytes, readResult)
            }
        }

        try {
            record.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioRecord", e)
        }
        releaseRecord(record)
        _isCapturing.value = false
        if (failure != null && isRunning) {
            Log.w(TAG, "Streaming audio capture failed: $failure")
            listener.onCaptureFailed(failure)
        }
        Log.d(TAG, "Streaming audio capture thread finished")
    }

    private fun releaseRecord(record: AudioRecord) {
        if (audioRecord !== record) return
        audioRecord = null
        try {
            record.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
    }

    /**
     * Stops capturing and safely joins the background thread.
     */
    fun stopCapture() {
        if (!isRunning) return
        isRunning = false

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord not recording; nothing to stop", e)
        }

        captureThread?.interrupt()
        try {
            captureThread?.join(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        captureThread = null

        val record = audioRecord
        audioRecord = null
        if (record != null) {
            try {
                record.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioRecord", e)
            }
        }
        _amplitude.value = 0f
        _isCapturing.value = false
        Log.d(TAG, "Streaming audio capture stopped")
    }
}
