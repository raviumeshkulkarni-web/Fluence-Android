package com.groq.voicetyper.offline

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orchestrates the full offline transcription pipeline:
 *   Mic (OfflineAudioCapture) → Silero VAD → SenseVoice (OfflineTranscriber)
 *
 * Implements VAD-driven rolling chunked inference:
 *   - Audio frames stream continuously from the microphone
 *   - Silero VAD detects speech segments and pauses
 *   - On speech-end (or 25-second max chunk), the accumulated audio is sent
 *     to SenseVoice for transcription
 *   - Transcribed text is immediately committed via the callback
 *   - Audio buffer is flushed; recording continues seamlessly
 *
 * Thread safety: Guarded by state checks and structured coroutine scopes.
 */
class OfflineTranscriptionPipeline(
    private val context: Context
) {
    companion object {
        private const val TAG = "OfflineTranscriptionPipeline"
        private const val MAX_CHUNK_DURATION_SEC = 25.0f   // Force-flush at 25s
        private const val VAD_SILENCE_THRESHOLD_SEC = 0.8f // Pause detection
        private const val SAMPLE_RATE = 16000
        private const val IDLE_RELEASE_DELAY_MS = 60_000L // 1 minute idle cache
    }

    private val audioCapture = OfflineAudioCapture()
    private val transcriber = OfflineTranscriber()
    private var vad: Vad? = null

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // Expose amplitude from audio capture
    val amplitude: StateFlow<Float> = audioCapture.amplitude

    /** Returns true if both the VAD and transcriber engines are initialized and ready. */
    fun isReady(): Boolean = transcriber.isReady() && vad != null

    private val pipelineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var idleReleaseJob: Job? = null
    private var segmentChannel: Channel<FloatArray>? = null
    private var workerJob: Job? = null

    var onTextTranscribed: ((String) -> Unit)? = null

    /**
     * Initializes the pipeline:
     *   1. Loads Silero VAD from APK assets (if not already loaded)
     *   2. Ensures OfflineTranscriber engine is initialized
     *
     * Must be called before start(). Can be called from Dispatchers.IO.
     */
    suspend fun initialize(modelDir: String) = withContext(Dispatchers.IO) {
        cancelIdleRelease()

        // 1. Initialize transcriber
        transcriber.initialize(modelDir)

        // 2. Initialize Silero VAD
        if (vad == null) {
            Log.d(TAG, "Initializing Silero VAD from APK assets")
            try {
                val sileroConfig = SileroVadModelConfig(
                    model = "silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = VAD_SILENCE_THRESHOLD_SEC,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = MAX_CHUNK_DURATION_SEC
                )
                val vadConfig = VadModelConfig(
                    sileroVadModelConfig = sileroConfig,
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false
                )
                vad = Vad(context.assets, vadConfig)
                Log.d(TAG, "Silero VAD initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Vad", e)
                throw e
            }
        }
        
        // Schedule idle timeout to release model memory if not used
        scheduleIdleRelease()
    }

    /**
     * Starts the recording → VAD → transcription pipeline.
     */
    fun start() {
        if (_isRunning.value) return
        cancelIdleRelease()

        val activeVad = vad ?: throw IllegalStateException("Pipeline not initialized. Call initialize first.")
        activeVad.reset()

        _isRunning.value = true

        // Create a new FIFO queue channel for sequential processing
        val channel = Channel<FloatArray>(Channel.UNLIMITED)
        segmentChannel = channel

        // Launch a single sequential worker coroutine
        workerJob = pipelineScope.launch {
            for (samples in channel) {
                try {
                    val text = transcriber.transcribe(samples, SAMPLE_RATE)
                    if (text.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            onTextTranscribed?.invoke(text)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in sequential transcription worker", e)
                }
            }
        }

        audioCapture.startCapture(object : OfflineAudioCapture.AudioFrameListener {
            override fun onAudioFrame(samples: FloatArray, sampleCount: Int) {
                if (!_isRunning.value) return

                activeVad.acceptWaveform(samples)
                processVadSegments(activeVad)
            }
        })

        Log.d(TAG, "Pipeline started")
    }

    private fun processVadSegments(activeVad: Vad) {
        while (!activeVad.empty()) {
            val segment = activeVad.front()
            val segmentSamples = segment.samples.clone() // Clone to safely pass to background thread
            activeVad.pop()

            Log.d(TAG, "Speech segment detected (size: ${segmentSamples.size} samples). Queueing for transcription.")
            segmentChannel?.trySend(segmentSamples)
        }
    }

    /**
     * Stops the pipeline:
     *   1. Stops audio capture
     *   2. Flushes the VAD buffer and runs final inference on remaining audio
     *   3. Schedules lazy idle release of the models
     */
    suspend fun stop() = withContext(Dispatchers.Default) {
        if (!_isRunning.value) return@withContext
        _isRunning.value = false

        Log.d(TAG, "Stopping pipeline audio capture")
        audioCapture.stopCapture()

        // Flush VAD and process final segment
        vad?.let { activeVad ->
            activeVad.flush()
            processVadSegments(activeVad)
        }

        // Close channel and wait for the sequential worker to finish transcribing queued items
        segmentChannel?.close()
        try {
            workerJob?.join()
        } catch (e: Exception) {
            Log.w(TAG, "Error waiting for sequential worker completion", e)
        }
        segmentChannel = null
        workerJob = null

        // Schedule idle timeout to release model memory if keyboard stays open/unused
        scheduleIdleRelease()
    }

    /**
     * Releases VAD, audio capture, and transcriber.
     */
    suspend fun release() {
        forceRelease()
    }

    /**
     * Immediately releases all native engine resources (bypasses idle timer).
     * Must be called when keyboard is hidden or destroyed.
     */
    suspend fun forceRelease() = withContext(Dispatchers.IO) {
        cancelIdleRelease()
        _isRunning.value = false

        Log.d(TAG, "Force releasing pipeline resources")

        // Cancel the worker job immediately
        workerJob?.cancel()
        segmentChannel?.close()
        try {
            workerJob?.join()
        } catch (e: Exception) {
            // Ignore
        }
        segmentChannel = null
        workerJob = null

        try {
            audioCapture.stopCapture()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio capture during release", e)
        }

        try {
            vad?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Vad JNI resources", e)
        } finally {
            vad = null
        }

        try {
            transcriber.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing transcriber engine", e)
        }
    }

    private fun scheduleIdleRelease() {
        cancelIdleRelease()
        idleReleaseJob = pipelineScope.launch {
            delay(IDLE_RELEASE_DELAY_MS)
            Log.d(TAG, "Pipeline idle for ${IDLE_RELEASE_DELAY_MS / 1000}s. Releasing resources to reclaim memory.")
            forceRelease()
        }
    }

    private fun cancelIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = null
    }
}
