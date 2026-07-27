package com.groq.voicetyper.offline

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Interface representing the underlying speech-to-text recognizer engine.
 * Extracted to allow unit testing of OfflineTranscriber without JNI dependencies.
 */
interface RecognizerEngine {
    fun initialize(modelDir: String, numThreads: Int)
    fun transcribe(samples: FloatArray, sampleRate: Int): String
    fun release()
}

/**
 * Concrete implementation of RecognizerEngine wrapping the native sherpa-onnx JNI library.
 */
class SherpaRecognizerEngine : RecognizerEngine {
    private var recognizer: OfflineRecognizer? = null

    override fun initialize(modelDir: String, numThreads: Int) {
        val modelPath = "$modelDir/${ModelAssetManager.MODEL_FILENAME}"
        val tokensPath = "$modelDir/${ModelAssetManager.TOKENS_FILENAME}"

        val senseVoiceConfig = OfflineSenseVoiceModelConfig(
            model = modelPath,
            language = "en",
            useInverseTextNormalization = true
        )

        val modelConfig = OfflineModelConfig(
            senseVoice = senseVoiceConfig,
            tokens = tokensPath,
            numThreads = numThreads,
            provider = "cpu",
            debug = false,
            modelType = "sensevoice"
        )

        val config = OfflineRecognizerConfig(
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )

        // Pass null for AssetManager since model files are on the file system
        recognizer = OfflineRecognizer(null, config)
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val engine = recognizer ?: return ""
        var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
        try {
            stream = engine.createStream()
            stream.acceptWaveform(samples, sampleRate)
            engine.decode(stream)
            val result = engine.getResult(stream)
            return result.text.trim()
        } finally {
            try {
                stream?.release()
            } catch (e: Exception) {
                Log.w("SherpaRecognizerEngine", "Error releasing stream", e)
            }
        }
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
    }
}

/**
 * Thread-safe wrapper around sherpa-onnx OfflineRecognizer for SenseVoice-Small.
 *
 * Lifecycle:
 *   1. initialize(modelDir) — loads ONNX model into memory (~180-230MB RAM)
 *   2. transcribe(samples) — runs inference on a PCM buffer
 *   3. release() — destroys native handles, frees RAM
 *
 * CRITICAL: All methods are guarded by a Kotlin Mutex to prevent JNI race
 * conditions. Never call release() while transcribe() is running on another
 * coroutine — the Mutex serializes access automatically.
 */
class OfflineTranscriber(
    private val engine: RecognizerEngine = SherpaRecognizerEngine()
) {

    enum class EngineState { UNLOADED, LOADING, READY, RELEASING }

    private val _engineState = MutableStateFlow(EngineState.UNLOADED)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    private val mutex = Mutex()
    private var activeJob: Job? = null

    companion object {
        private const val TAG = "OfflineTranscriber"

        /**
         * Factory method to create an OfflineTranscriber with the specified engine type.
         * Defaults to SenseVoice (SherpaRecognizerEngine) for backward compatibility.
         */
        fun create(engineType: OfflineEngineType = OfflineEngineType.SENSEVOICE): OfflineTranscriber {
            val engine: RecognizerEngine = when (engineType) {
                OfflineEngineType.SENSEVOICE -> SherpaRecognizerEngine()
                OfflineEngineType.MOONSHINE_BASE -> MoonshineRecognizerEngine()
            }
            return OfflineTranscriber(engine)
        }
    }

    /**
     * Initializes the sherpa-onnx OfflineRecognizer with SenseVoice-Small config.
     * Must be called from Dispatchers.IO (heavy file I/O + native init).
     */
    suspend fun initialize(modelDir: String, numThreads: Int = 2) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_engineState.value == EngineState.READY || _engineState.value == EngineState.LOADING) {
                return@withLock
            }

            _engineState.value = EngineState.LOADING
            Log.d(TAG, "Initializing engine from dir: $modelDir")

            try {
                engine.initialize(modelDir, numThreads)
                _engineState.value = EngineState.READY
                Log.d(TAG, "Engine initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize engine", e)
                _engineState.value = EngineState.UNLOADED
                throw e
            }
        }
    }

    /**
     * Runs SenseVoice inference on a PCM audio buffer.
     * Returns the transcribed text with native punctuation/capitalization.
     */
    suspend fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String = withContext(Dispatchers.IO) {
        // Store current Job so release() can cancel it if necessary
        val currentJob = coroutineContext[Job]
        
        mutex.withLock {
            if (_engineState.value != EngineState.READY) {
                Log.e(TAG, "Engine not ready. Current state: ${_engineState.value}")
                return@withContext ""
            }

            activeJob = currentJob
            try {
                Log.d(TAG, "Starting inference on ${samples.size} samples")
                val text = engine.transcribe(samples, sampleRate)
                Log.d(TAG, "Inference completed: '$text'")
                return@withContext text
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error running inference", e)
                return@withContext ""
            } finally {
                activeJob = null
            }
        }
    }

    /**
     * Releases all native sherpa-onnx handles and frees RAM.
     *
     * THREAD SAFETY: Acquires Mutex, cancels any active inference Job,
     * waits for cancellation to propagate, THEN destroys native handles.
     * This prevents JNI segmentation faults from concurrent access.
     */
    suspend fun release() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_engineState.value == EngineState.UNLOADED || _engineState.value == EngineState.RELEASING) {
                return@withLock
            }

            _engineState.value = EngineState.RELEASING
            Log.d(TAG, "Releasing engine")

            try {
                // Cancel active job if there's one running
                activeJob?.let { job ->
                    Log.d(TAG, "Canceling active inference job before release")
                    job.cancel()
                    try {
                        job.join()
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception waiting for inference job cancellation", e)
                    }
                }
                
                engine.release()
                _engineState.value = EngineState.UNLOADED
                Log.d(TAG, "Engine released successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing engine", e)
                _engineState.value = EngineState.UNLOADED
            }
        }
    }

    fun isReady(): Boolean {
        return _engineState.value == EngineState.READY
    }
}
