package com.groq.voicetyper.offline

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Orchestrates the full offline transcription pipeline:
 *   Mic (OfflineAudioCapture) → Silero VAD → OfflineTranscriber (SenseVoice or Moonshine)
 *
 * Implements VAD-driven rolling chunked inference:
 *   - Audio frames stream continuously from the microphone
 *   - Silero VAD detects speech segments and pauses
 *   - On speech-end (or 25-second max chunk), the accumulated audio is sent
 *     to the selected engine for transcription
 *   - Transcribed text is immediately committed via the callback
 *   - Audio buffer is flushed; recording continues seamlessly
 *
 * Thread safety: Guarded by state checks and structured coroutine scopes.
 */
class OfflineTranscriptionPipeline(
    private val context: Context,
    private val engineType: OfflineEngineType = OfflineEngineType.SENSEVOICE
) {
    companion object {
        private const val TAG = "OfflineTranscriptionPipeline"
        private const val MAX_CHUNK_DURATION_SEC = 25.0f   // Force-flush at 25s
        private const val VAD_SILENCE_THRESHOLD_SEC = 0.8f // Pause detection
        private const val SAMPLE_RATE = 16000
        private const val IDLE_RELEASE_DELAY_MS = 60_000L // 1 minute idle cache
        private const val SEGMENT_QUEUE_CAPACITY = 32     // Bounded queue under load
    }

    private val audioCapture = OfflineAudioCapture()
    private val transcriber = OfflineTranscriber.create(engineType)
    private var vad: Vad? = null
    private val vadLock = Any()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // Expose amplitude from audio capture
    val amplitude: StateFlow<Float> = audioCapture.amplitude

    // Expose model loading/ready state to UI callers
    val engineState: StateFlow<OfflineTranscriber.EngineState> = transcriber.engineState

    // Expose a model-level failure (corrupt/missing files, init failure) so IME/bubble
    // UX can surface it instead of silently dropping all offline transcription.
    private val _modelError = MutableStateFlow<String?>(null)
    val modelError: StateFlow<String?> = _modelError.asStateFlow()

    /** Returns true if both the VAD and transcriber engines are initialized and ready. */
    fun isReady(): Boolean = transcriber.isReady() && synchronized(vadLock) { vad != null }

    private val pipelineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var idleReleaseJob: Job? = null
    private var segmentChannel: Channel<FloatArray>? = null
    private var workerJob: Job? = null

    var onTextTranscribed: ((String) -> Unit)? = null

    /**
     * Synchronously loads the VAD model. Fast enough for main-thread execution (~1-2ms).
     */
    fun initializeVadSync() {
        synchronized(vadLock) {
            if (vad == null) {
                Log.d(TAG, "Initializing Silero VAD from APK assets synchronously")
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
                    Log.d(TAG, "Silero VAD initialized successfully (sync)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize Vad synchronously", e)
                    throw e
                }
            }
        }
    }

    /**
     * Initializes the transcriber engine.
     * Must be called before start() or during pre-warm. Can be called from Dispatchers.IO.
     */
    suspend fun initialize(modelDir: String) = withContext(Dispatchers.IO) {
        cancelIdleRelease()
        // Set the pending-init latch BEFORE the slow integrity hash so a concurrent
        // worker's first transcribe() waits instead of dropping the first utterance.
        transcriber.markInitializationPending()
        try {
            verifyModelIntegrity()
            transcriber.initialize(modelDir)
            _modelError.value = null
        } catch (e: Throwable) {
            transcriber.failPendingInitialization(e)
            throw e
        } finally {
            scheduleIdleRelease()
        }
    }

    /**
     * Verifies the downloaded model files against their SHA-256 checksums before the
     * native engine is loaded, so corrupt-but-large files never reach the JNI layer.
     * Must be called from Dispatchers.IO.
     */
    private suspend fun verifyModelIntegrity() {
        val verified = when (engineType) {
            OfflineEngineType.SENSEVOICE -> ModelAssetManager.isModelReady(context)
            OfflineEngineType.MOONSHINE_BASE -> MoonshineModelManager.isModelReady(context)
            OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING -> com.groq.voicetyper.offline.v2.MoonshineV2ModelManager.isModelReady(context, com.groq.voicetyper.offline.v2.MoonshineV2ModelType.SMALL)
            OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING -> com.groq.voicetyper.offline.v2.MoonshineV2ModelManager.isModelReady(context, com.groq.voicetyper.offline.v2.MoonshineV2ModelType.MEDIUM)
        }
        if (!verified) {
            throw IllegalStateException(
                "Offline ${engineType.displayName} model files are missing or corrupted. Re-download the model."
            )
        }
    }

    /**
     * Starts the recording → VAD → transcription pipeline.
     * Captures audio immediately while loading the heavy transcription model in the background if not ready.
     */
    fun start(modelDir: String) {
        if (_isRunning.value) return
        cancelIdleRelease()

        // 1. Ensure VAD is initialized immediately
        initializeVadSync()
        val activeVad = synchronized(vadLock) { vad } ?: throw IllegalStateException("VAD is not initialized.")
        synchronized(vadLock) { activeVad.reset() }

        _isRunning.value = true
        _modelError.value = null

        // Set the pending-init latch up front (before the slow integrity hash below)
        // so the worker's first transcribe() waits instead of returning "" on cold start.
        if (!transcriber.isReady()) {
            transcriber.markInitializationPending()
        }

        // Create a bounded FIFO queue; if transcription falls behind, drop the
        // oldest queued segment instead of letting the queue grow without bound,
        // and log an overflow warning so the loss is visible.
        val channel = Channel<FloatArray>(
            capacity = SEGMENT_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
            onUndeliveredElement = {
                Log.w(TAG, "Segment queue overflow: dropped oldest queued segment (cap $SEGMENT_QUEUE_CAPACITY)")
            }
        )
        segmentChannel = channel

        // 2. Launch model initialization concurrently in the background if not ready.
        // transcriber.transcribe() awaits the pending initialization before running inference.
        pipelineScope.launch(Dispatchers.IO) {
            try {
                if (!transcriber.isReady()) {
                    verifyModelIntegrity()
                    transcriber.initialize(modelDir)
                }
            } catch (e: CancellationException) {
                transcriber.failPendingInitialization(e)
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Background transcription engine initialization failed", e)
                transcriber.failPendingInitialization(e)
                _modelError.value = "Offline model unavailable: ${e.localizedMessage ?: "initialization failed"}"
            }
        }

        // Launch a single sequential worker coroutine
        workerJob = pipelineScope.launch {
            for (samples in channel) {
                try {
                    val text = transcriber.transcribe(samples, SAMPLE_RATE)
                    if (text.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            onTextTranscribed?.invoke(text)
                        }
                    } else if (!transcriber.isReady()) {
                        // Silent "" from not-READY would otherwise drop speech without feedback
                        Log.w(TAG, "Transcription dropped: engine not ready for ${samples.size} samples")
                        _modelError.value = "Offline transcription unavailable — engine not ready"
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Error in sequential transcription worker", e)
                }
            }
        }

        audioCapture.startCapture(object : OfflineAudioCapture.AudioFrameListener {
            override fun onAudioFrame(samples: FloatArray, sampleCount: Int) {
                if (!_isRunning.value) return

                synchronized(vadLock) {
                    // isRunning was checked before acquiring lock; re-check under lock
                    // so stop()/forceRelease() cannot interleave with accept/flush.
                    if (!_isRunning.value) return
                    activeVad.acceptWaveform(samples)
                    processVadSegmentsLocked(activeVad)
                }
            }
        })

        Log.d(TAG, "Pipeline started")
    }

    private fun processVadSegments(activeVad: Vad) {
        synchronized(vadLock) {
            processVadSegmentsLocked(activeVad)
        }
    }

    private fun processVadSegmentsLocked(activeVad: Vad) {
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

        // Flush VAD and process final segment — under vadLock so an in-flight
        // audio-thread acceptWaveform cannot race with flush.
        synchronized(vadLock) {
            vad?.let { activeVad ->
                activeVad.flush()
                processVadSegmentsLocked(activeVad)
            }
        }

        // Close channel and wait for the sequential worker to finish transcribing queued items
        segmentChannel?.close()
        try {
            workerJob?.join()
        } catch (e: CancellationException) {
            throw e
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ignore
        }
        segmentChannel = null
        workerJob = null

        try {
            audioCapture.stopCapture()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio capture during release", e)
        }

        try {
            synchronized(vadLock) {
                vad?.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Vad JNI resources", e)
        } finally {
            synchronized(vadLock) { vad = null }
        }

        try {
            transcriber.release()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing transcriber engine", e)
        }
    }

    private fun scheduleIdleRelease() {
        cancelIdleRelease()
        idleReleaseJob = pipelineScope.launch {
            delay(IDLE_RELEASE_DELAY_MS)
            Log.d(TAG, "Pipeline idle for ${IDLE_RELEASE_DELAY_MS / 1000}s. Releasing resources to reclaim memory.")
            idleReleaseJob = null
            forceRelease()
        }
    }

    private fun cancelIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = null
    }
}
