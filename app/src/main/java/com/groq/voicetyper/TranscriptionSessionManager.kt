package com.groq.voicetyper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.groq.voicetyper.offline.MoonshineModelManager
import com.groq.voicetyper.offline.ModelAssetManager
import com.groq.voicetyper.offline.OfflineEngineType
import com.groq.voicetyper.offline.OfflinePipelineProvider
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.offline.OfflineTranscriber
import com.groq.voicetyper.history.HistoryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

enum class OfflineEngineState {
    UNLOADED,
    LOADING,
    READY
}

interface SessionListener {
    fun onTranscription(text: String)
    fun onCommand(command: CommandResult, contextText: String)
    fun getContextText(): String
    fun onError(message: String)
    fun onPartialTranscription(text: String) {}
}

object TranscriptionSessionManager {
    private const val TAG = "TranscriptionSessionMgr"

    private enum class SessionOwner { IME, BUBBLE }

    @Volatile
    private var sessionOwner = SessionOwner.BUBBLE

    private var audioRecorder: AudioRecorder? = null
    @Volatile
    private var currentListener: SessionListener? = null

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _isAgentMode = MutableStateFlow(false)
    val isAgentMode: StateFlow<Boolean> = _isAgentMode.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _offlineEngineState = MutableStateFlow(OfflineEngineState.UNLOADED)
    val offlineEngineState: StateFlow<OfflineEngineState> = _offlineEngineState.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText.asStateFlow()

    private var streamingAudioCapture: com.groq.voicetyper.streaming.StreamingAudioCapture? = null
    private var streamingTranscriber: com.groq.voicetyper.streaming.StreamingTranscriber? = null
    private var streamingCollectJob: Job? = null
    private var activeStreaming = false


    // This scope lives for the entire app process lifetime. We never cancel it,
    // because TranscriptionSessionManager is a process-level singleton object.
    // Individual sessions are managed through job cancellation, not scope cancellation.
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var noisyReceiver: BroadcastReceiver? = null

    private var amplitudeCollectJob: Job? = null
    private var engineStateCollectJob: Job? = null
    private var modelErrorCollectJob: Job? = null
    private var preWarmJob: Job? = null
    private var activeOffline = false
    @Volatile
    private var activeEngineType: OfflineEngineType? = null
    private var recordingStartTimestampMs = 0L
    private val offlineTextAccumulator = StringBuilder()

    /** Delay before retrying a streaming mic start (mic may be transiently held). */
    internal const val MIC_START_RETRY_DELAY_MS = 400L

    /** How long to wait for a Final/Error/Closed after stopAndFinalize before force-teardown. */
    internal const val STREAMING_FINALIZE_TIMEOUT_MS = 5_000L

    // Monotonic session id. startRecordingInternal increments it; async cleanup
    // (offline force-release on cancel) captures it and bails if a newer session
    // started, so a stale teardown never stops a freshly-started session's capture.
    @Volatile
    private var sessionGeneration = 0L

    private fun isEngineModelReady(context: Context, engineType: OfflineEngineType): Boolean {
        return when (engineType) {
            OfflineEngineType.SENSEVOICE -> ModelAssetManager.isModelReadySync(context)
            OfflineEngineType.MOONSHINE_BASE -> MoonshineModelManager.isModelReadySync(context)
        }
    }

    private fun getModelDir(context: Context, engineType: OfflineEngineType): File {
        return when (engineType) {
            OfflineEngineType.SENSEVOICE -> ModelAssetManager.getModelDir(context)
            OfflineEngineType.MOONSHINE_BASE -> MoonshineModelManager.getModelDir(context)
        }
    }

    private fun getModelName(engineType: OfflineEngineType): String {
        return when (engineType) {
            OfflineEngineType.SENSEVOICE -> "sensevoice-small"
            OfflineEngineType.MOONSHINE_BASE -> "moonshine-base-v1"
        }
    }

    @Synchronized
    private fun initRecorder(context: Context) {
        if (audioRecorder == null) {
            audioRecorder = AudioRecorder(context.applicationContext)
        }
    }

    fun preWarmOfflinePipeline(context: Context) {
        val isOfflineMode = OfflinePreferences.isOfflineModeEnabled(context)
        val engineType = OfflinePreferences.getEngineType(context)
        if (isOfflineMode && isEngineModelReady(context, engineType)) {
            preWarmJob?.cancel()
            preWarmJob = scope.launch {
                delay(600) // Let entry animations finish
                withContext(Dispatchers.IO) {
                    try {
                        val modelDir = getModelDir(context, engineType).absolutePath
                        val pipeline = OfflinePipelineProvider.getInstance(context, engineType)
                        pipeline.initialize(modelDir)
                    } catch (e: Exception) {
                        Log.w(TAG, "Pre-initialization of offline pipeline failed", e)
                    } catch (e: Error) {
                        // Catch native JNI errors (UnsatisfiedLinkError, NoSuchFieldError, etc.)
                        // to prevent killing the entire app process
                        Log.e(TAG, "FATAL: Pre-warm hit a JNI/native error. Disabling offline mode.", e)
                    }
                }
            }
        }
    }

    fun cancelPreWarm() {
        preWarmJob?.cancel()
        preWarmJob = null
    }

    fun startRecording(context: Context, isOffline: Boolean, agentMode: Boolean, listener: SessionListener) {
        startRecordingInternal(context, isOffline, agentMode, listener, SessionOwner.BUBBLE)
    }

    internal fun startImeRecording(context: Context, isOffline: Boolean, agentMode: Boolean, listener: SessionListener) {
        startRecordingInternal(context, isOffline, agentMode, listener, SessionOwner.IME)
    }

    private fun startRecordingInternal(
        context: Context,
        isOffline: Boolean,
        agentMode: Boolean,
        listener: SessionListener,
        owner: SessionOwner
    ) {
        if (_recordingState.value != RecordingState.IDLE && _recordingState.value != RecordingState.ERROR) {
            return
        }
        sessionGeneration++
        sessionOwner = owner
        appContext = context.applicationContext
        cancelPreWarm()
        currentListener = listener
        _errorMessage.value = null
        _isAgentMode.value = agentMode
        recordingStartTimestampMs = System.currentTimeMillis()

        val engineType = OfflinePreferences.getEngineType(context)
        val useOffline = isOffline && !agentMode && isEngineModelReady(context, engineType)
        val sttPreset = SecurityUtils.getSttPreset(context)
        val isStreamingConfigured = SecurityUtils.isStreamingEnabled(context)
        // Agent Mode is orthogonal to the transcription mode: the streaming Final is
        // routed through the same command-processing path as batch (deliverTranscript).
        val useStreaming = isStreamingConfigured && !isOffline && (sttPreset == "mistral" || sttPreset == "custom")
        activeStreaming = useStreaming
        activeOffline = if (useStreaming) false else useOffline
        activeEngineType = if (useOffline) engineType else null
        _partialText.value = ""

        registerNoisyReceiver(context)

        if (useStreaming) {
            startStreamingSessionInternal(context, listener)
        } else if (useOffline) {

            _recordingState.value = RecordingState.RECORDING
            scope.launch {
                try {
                    val pipeline = OfflinePipelineProvider.getInstance(context, engineType)
                    offlineTextAccumulator.setLength(0)
                    pipeline.onTextTranscribed = { text ->
                        val cleanText = text.trim()
                        if (cleanText.isNotEmpty()) {
                            offlineTextAccumulator.append(cleanText).append(" ")
                        }
                    }

                    val modelDir = getModelDir(context, engineType).absolutePath

                    // Collect amplitude and engineState from the offline pipeline
                    amplitudeCollectJob?.cancel()
                    amplitudeCollectJob = scope.launch {
                        pipeline.amplitude.collect {
                            _amplitude.value = it
                        }
                    }

                    engineStateCollectJob?.cancel()
                    engineStateCollectJob = scope.launch {
                        pipeline.engineState.collect { state ->
                            _offlineEngineState.value = when (state) {
                                OfflineTranscriber.EngineState.UNLOADED -> OfflineEngineState.UNLOADED
                                OfflineTranscriber.EngineState.LOADING -> OfflineEngineState.LOADING
                                OfflineTranscriber.EngineState.READY -> OfflineEngineState.READY
                                OfflineTranscriber.EngineState.RELEASING -> OfflineEngineState.UNLOADED
                            }
                        }
                    }

                    // Surface model-level corruption/init failures to IME/bubble UX
                    // instead of silently dropping all offline transcription.
                    modelErrorCollectJob?.cancel()
                    modelErrorCollectJob = scope.launch {
                        pipeline.modelError.collect { error ->
                            if (!error.isNullOrBlank()) {
                                val generation = sessionGeneration
                                if (sessionGeneration == generation) {
                                    showError("Offline transcription unavailable: $error")
                                    unregisterNoisyReceiver(context)
                                    scope.launch {
                                        if (sessionGeneration == generation) {
                                            OfflinePipelineProvider.releaseInstance()
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Guard against a stop/cancel (or focus/noisy loss) that landed while
                    // the pipeline was being spun up. pipeline.start() only checks its own
                    // isRunning flag, so without this check it would start audio capture
                    // even though the session already ended (mic hot with state IDLE).
                    if (_recordingState.value != RecordingState.RECORDING) {
                        amplitudeCollectJob?.cancel()
                        amplitudeCollectJob = null
                        engineStateCollectJob?.cancel()
                        engineStateCollectJob = null
                        modelErrorCollectJob?.cancel()
                        modelErrorCollectJob = null
                        return@launch
                    }

                    pipeline.start(modelDir)
                } catch (e: Exception) {
                    showError("Offline recording start failed: ${e.localizedMessage}")
                } catch (e: Error) {
                    Log.e(TAG, "FATAL: Offline recording hit a JNI/native error", e)
                    showError("Offline engine error. Please restart the app.")
                }
            }
        } else {
            initRecorder(context)
            _offlineEngineState.value = OfflineEngineState.UNLOADED
            
            amplitudeCollectJob?.cancel()
            amplitudeCollectJob = scope.launch {
                audioRecorder?.amplitude?.collect {
                    _amplitude.value = it
                }
            }

            _recordingState.value = RecordingState.RECORDING
            if (!(audioRecorder?.startRecording() ?: false)) {
                amplitudeCollectJob?.cancel()
                amplitudeCollectJob = null
                recordingStartTimestampMs = 0L
                _isAgentMode.value = false
                // Return to a clean IDLE so the next mic tap can start again, and
                // release the noisy resources we just claimed.
                _recordingState.value = RecordingState.IDLE
                unregisterNoisyReceiver(context)
                showError("Could not start the microphone. Check that microphone permission is granted and try again.")
            }
        }
    }

    private fun startStreamingSessionInternal(context: Context, listener: SessionListener) {
        val generation = sessionGeneration
        _recordingState.value = RecordingState.RECORDING

        val capture = com.groq.voicetyper.streaming.StreamingAudioCapture()
        streamingAudioCapture = capture

        val transcriber = com.groq.voicetyper.streaming.MistralVoxtralTranscriber()
        streamingTranscriber = transcriber

        amplitudeCollectJob?.cancel()
        amplitudeCollectJob = scope.launch {
            capture.amplitude.collect {
                _amplitude.value = it
            }
        }

        val frameListener = object : com.groq.voicetyper.streaming.StreamingAudioCapture.AudioFrameListener {
            override fun onAudioFrame(pcmBytes: ByteArray, length: Int) {
                if (sessionGeneration == generation && _recordingState.value == RecordingState.RECORDING) {
                    transcriber.sendAudioChunk(pcmBytes, length)
                }
            }
        }

        try {
            capture.startCapture(frameListener)
        } catch (e: SecurityException) {
            // No permission — fail immediately, never retry.
            Log.e(TAG, "Streaming capture start denied", e)
            scope.launch { failStreamingStart(context, generation) }
        } catch (e: IllegalStateException) {
            // The mic can be transiently held by the HAL right after a process death
            // or force-close. Retry once shortly after; a newer session or a cancel
            // invalidates the retry through the generation/state guards below.
            Log.w(TAG, "Streaming capture start failed, retrying once: ${e.localizedMessage}")
            scope.launch {
                delay(MIC_START_RETRY_DELAY_MS)
                if (sessionGeneration != generation || _recordingState.value != RecordingState.RECORDING) {
                    return@launch
                }
                try {
                    capture.startCapture(frameListener)
                } catch (e2: Exception) {
                    Log.e(TAG, "Streaming capture start failed after retry", e2)
                    failStreamingStart(context, generation)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming capture start failed", e)
            scope.launch { failStreamingStart(context, generation) }
        }

        streamingCollectJob?.cancel()
        streamingCollectJob = scope.launch(Dispatchers.IO) {
            val sttPreset = SecurityUtils.getSttPreset(context)
            val apiKey = SecurityUtils.getProviderApiKey(context, "stt", sttPreset)
            if (apiKey.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    if (sessionGeneration == generation) {
                        showError("API Key is missing for STT provider: ${sttPreset.uppercase()}")
                    }
                }
                // Full teardown: without stopping the capture here, it keeps running
                // and a second tap would start a second AudioRecord (two mics active).
                endStreamingSession(context, generation)
                _recordingState.value = RecordingState.IDLE
                return@launch
            }

            val baseUrl = SecurityUtils.getSttBaseUrl(context, sttPreset)
            val model = SecurityUtils.getSttModel(context, sttPreset)
            val language = getEffectiveLanguage(context)

            try {
                transcriber.connect(baseUrl, apiKey, model, language).collect { event ->
                    if (sessionGeneration != generation) return@collect
                    when (event) {
                        is com.groq.voicetyper.streaming.StreamingTranscriptEvent.Partial -> {
                            _partialText.value = event.cumulativeText
                            withContext(Dispatchers.Main) {
                                if (sessionGeneration == generation) {
                                    try {
                                        currentListener?.onPartialTranscription(event.cumulativeText)
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "Error invoking onPartialTranscription listener", e)
                                    }
                                }
                            }
                        }
                        is com.groq.voicetyper.streaming.StreamingTranscriptEvent.Final -> {
                            val rawText = event.finalText.trim()
                            if (rawText.isNotEmpty()) {
                                val processedText = com.groq.voicetyper.dictionary.DictionaryTextPostProcessor.process(context, rawText)
                                val durationMs = if (recordingStartTimestampMs > 0L) {
                                    (System.currentTimeMillis() - recordingStartTimestampMs).coerceAtLeast(0L)
                                } else 0L
                                deliverTranscript(context, processedText, sttPreset, model, language, durationMs)
                            } else {
                                withContext(Dispatchers.Main) {
                                    if (sessionGeneration == generation) {
                                        _recordingState.value = RecordingState.IDLE
                                        currentListener = null
                                    }
                                }
                                if (sessionGeneration == generation) {
                                    _isAgentMode.value = false
                                }
                            }
                            endStreamingSession(context, generation)
                        }
                        is com.groq.voicetyper.streaming.StreamingTranscriptEvent.Error -> {
                            if (sessionGeneration == generation) {
                                withContext(Dispatchers.Main) {
                                    showError("Streaming error: ${event.message}")
                                }
                                endStreamingSession(context, generation)
                            }
                        }
                        com.groq.voicetyper.streaming.StreamingTranscriptEvent.Closed -> {
                            // The server closed the socket without a Final. If the
                            // session is still active, end it explicitly so the user
                            // can retry; previously the session hung in RECORDING with
                            // a dead socket, or in TRANSCRIBING forever.
                            if (_recordingState.value == RecordingState.RECORDING ||
                                _recordingState.value == RecordingState.TRANSCRIBING
                            ) {
                                // showError FIRST: endStreamingSession cancels this
                                // collect job, and any suspension point after that
                                // would throw CancellationException, silently killing
                                // the error surfacing (the hang S5c described).
                                withContext(Dispatchers.Main) {
                                    if (sessionGeneration == generation) {
                                        showError("Streaming connection closed before the transcript finished.")
                                    }
                                }
                                endStreamingSession(context, generation)
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Intentional session end (cancel/stop/watchdog/teardown). Never
                // surface a spurious "connection failed" error for an intentional stop.
                throw e
            } catch (e: Exception) {
                if (sessionGeneration == generation) {
                    withContext(Dispatchers.Main) {
                        showError("Streaming connection failed: ${e.localizedMessage}")
                    }
                    endStreamingSession(context, generation)
                }
            }
        }
    }

    /**
     * Single authoritative teardown for the streaming session. Idempotent; safe to
     * call from every streaming exit (Final, Error, Closed, missing API key, cancel,
     * mic-start failure, watchdog, destroy) and from inside the collect job itself
     * (the job is cancelled, and the statements after this call in the job body are
     * plain state writes with no suspension points).
     *
     * Every exit path to IDLE must converge here so that:
     * - the AudioRecord is always stopped (never two captures on the next tap),
     * - the WebSocket is closed and the writer job cancelled,
     * - stale async callbacks cannot touch the next session's listener/state.
     */
    private fun endStreamingSession(context: Context, generation: Long) {
        streamingAudioCapture?.stopCapture()
        streamingAudioCapture = null
        streamingTranscriber?.close()
        streamingTranscriber = null
        streamingCollectJob?.cancel()
        streamingCollectJob = null
        activeStreaming = false
        if (sessionGeneration == generation) {
            currentListener = null
            sessionOwner = SessionOwner.BUBBLE
            recordingStartTimestampMs = 0L
            _partialText.value = ""
        }
        unregisterNoisyReceiver(context)
    }

    private suspend fun failStreamingStart(context: Context, generation: Long) {
        withContext(Dispatchers.Main) {
            if (sessionGeneration == generation) {
                showError("Could not start streaming microphone. Check permissions and try again.")
            }
        }
        endStreamingSession(context, generation)
        _recordingState.value = RecordingState.IDLE
    }

    fun stopRecording(context: Context) {
        if (_recordingState.value != RecordingState.RECORDING) return
        _recordingState.value = RecordingState.TRANSCRIBING
        unregisterNoisyReceiver(context)

        val durationMs = if (recordingStartTimestampMs > 0L) {
            (System.currentTimeMillis() - recordingStartTimestampMs).coerceAtLeast(0L)
        } else 0L

        amplitudeCollectJob?.cancel()
        amplitudeCollectJob = null
        if (activeStreaming) {
            val capture = streamingAudioCapture
            val transcriber = streamingTranscriber
            val generation = sessionGeneration
            scope.launch {
                capture?.stopCapture()
                transcriber?.stopAndFinalize()
                // Watchdog: if the provider never delivers a Final/Error/Closed (dead
                // socket, silent close), force teardown so the session cannot hang in
                // TRANSCRIBING forever (previously it did exactly that).
                delay(STREAMING_FINALIZE_TIMEOUT_MS)
                if (sessionGeneration == generation && _recordingState.value == RecordingState.TRANSCRIBING) {
                    withContext(Dispatchers.Main) {
                        showError("Streaming transcription did not complete. Please try again.")
                    }
                    endStreamingSession(context, generation)
                    _recordingState.value = RecordingState.IDLE
                }
            }
            return
        }

        // Batch and offline sessions have no async Final path — the session clock
        // stops now. For streaming, the timestamp stays live until the Final arrives
        // (the Final handler computes durationMs; endStreamingSession zeroes it).
        recordingStartTimestampMs = 0L

        if (activeOffline) {

            engineStateCollectJob?.cancel()
            engineStateCollectJob = null
            modelErrorCollectJob?.cancel()
            modelErrorCollectJob = null
            _offlineEngineState.value = OfflineEngineState.UNLOADED

            scope.launch {
                try {
                    val pipeline = OfflinePipelineProvider.getInstance(context, activeEngineType ?: OfflineEngineType.SENSEVOICE)
                    if (pipeline.isRunning.value) {
                        pipeline.stop()
                    } else {
                        pipeline.forceRelease()
                    }
                    val rawTranscription = offlineTextAccumulator.toString().trim()
                    if (rawTranscription.isNotEmpty()) {
                        val finalTranscription = com.groq.voicetyper.dictionary.DictionaryTextPostProcessor.process(context, rawTranscription)
                        val lang = getEffectiveLanguage(context)
                        val engineModelName = getModelName(activeEngineType ?: OfflineEngineType.SENSEVOICE)
                        CoroutineScope(Dispatchers.IO).launch {
                            HistoryRepository.save(context.applicationContext, finalTranscription, "offline", engineModelName, lang, durationMs, false)
                        }
                        withContext(Dispatchers.Main) {
                            currentListener?.onTranscription(finalTranscription)
                        }
                    }
                    offlineTextAccumulator.setLength(0)
                    activeEngineType = null
                    _recordingState.value = RecordingState.IDLE
                    _isAgentMode.value = false
                    currentListener = null
                } catch (e: Exception) {
                    showError("Offline transcription failed: ${e.localizedMessage}")
                    activeEngineType = null
                    _isAgentMode.value = false
                }
            }
        } else {
            val file = audioRecorder?.stopRecording()
            if (file != null) {
                transcribeAudioOnline(context, file, durationMs)
            } else {
                _recordingState.value = RecordingState.IDLE
                _isAgentMode.value = false
                currentListener = null
            }
        }
    }

    fun cancelRecording(context: Context) {
        if (sessionOwner != SessionOwner.BUBBLE) return
        cancelSessionInternal(context)
    }

    internal fun cancelImeRecording(context: Context) {
        if (sessionOwner != SessionOwner.IME) return
        cancelSessionInternal(context)
    }

    private fun cancelSessionInternal(context: Context) {
        val generation = sessionGeneration
        recordingStartTimestampMs = 0L
        _recordingState.value = RecordingState.IDLE
        amplitudeCollectJob?.cancel()
        amplitudeCollectJob = null

        _isAgentMode.value = false
        _partialText.value = ""
        if (activeStreaming) {
            endStreamingSession(context, generation)
        } else if (activeOffline) {

            engineStateCollectJob?.cancel()
            engineStateCollectJob = null
            modelErrorCollectJob?.cancel()
            modelErrorCollectJob = null
            _offlineEngineState.value = OfflineEngineState.UNLOADED

            scope.launch {
                // A new session may have started while this teardown was queued;
                // never tear down the new session's pipeline.
                if (sessionGeneration != generation) return@launch
                try {
                    val pipeline = OfflinePipelineProvider.getInstance(context, activeEngineType ?: OfflineEngineType.SENSEVOICE)
                    pipeline.forceRelease()
                } catch (e: Exception) {
                    Log.w(TAG, "Error force releasing offline pipeline on cancel", e)
                } finally {
                    offlineTextAccumulator.setLength(0)
                    activeEngineType = null
                    currentListener = null
                }
            }
        } else {
            audioRecorder?.cancelRecording()
            currentListener = null
        }
        unregisterNoisyReceiver(context)
    }

    private fun transcribeAudioOnline(context: Context, file: File, durationMs: Long) {
        val sttPreset = SecurityUtils.getSttPreset(context)
        val sttKey = SecurityUtils.getProviderApiKey(context, "stt", sttPreset)
        if (sttKey.isNullOrBlank()) {
            showError("API Key is missing for STT provider: ${sttPreset.uppercase()}")
            file.delete()
            return
        }

        val llmPreset = SecurityUtils.getLlmPreset(context)
        val llmKey = SecurityUtils.getProviderApiKey(context, "llm", llmPreset)
        if (_isAgentMode.value && llmKey.isNullOrBlank()) {
            showError("API Key is missing for Agent provider: ${llmPreset.uppercase()}")
            file.delete()
            return
        }

        scope.launch {
            val generation = sessionGeneration
            val languageCode = getEffectiveLanguage(context)
            val sttBaseUrl = SecurityUtils.getSttBaseUrl(context, sttPreset)
            val sttModel = SecurityUtils.getSttModel(context, sttPreset)

            // Auto-detect: only send a language hint to the STT API when the user
            // explicitly chose one. Passing null makes Whisper detect the spoken
            // language itself instead of assuming the keyboard/device language.
            val sttLanguage = SecurityUtils.getSttLanguage(context).ifBlank { null }

            val result = GroqClient.transcribe(sttBaseUrl, sttModel, sttKey, file, sttLanguage)
            result.fold(
                onSuccess = { rawText ->
                    if (rawText.isNotBlank()) {
                        val text = com.groq.voicetyper.dictionary.DictionaryTextPostProcessor.process(context, rawText)
                        deliverTranscript(context, text, sttPreset, sttModel, languageCode, durationMs)
                    } else {
                        if (sessionGeneration == generation) {
                            _recordingState.value = RecordingState.IDLE
                            currentListener = null
                            _isAgentMode.value = false
                        }
                    }
                },
                onFailure = { error ->
                    if (sessionGeneration == generation) {
                        showError(error.localizedMessage ?: "Transcription failed")
                        _isAgentMode.value = false
                    }
                }
            )
        }
    }

    /**
     * Delivers a completed transcript through the session's listener exactly once,
     * routing through the Agent command pipeline when Agent Mode is on. Shared by the
     * batch path ([transcribeAudioOnline]) and the streaming Final path so Agent Mode
     * behaves identically in both transcription modes. Generation-guarded on every
     * async hop; a newer session can never receive a stale transcript.
     */
    private suspend fun deliverTranscript(
        context: Context,
        text: String,
        sttPreset: String,
        model: String,
        language: String,
        durationMs: Long
    ) {
        val generation = sessionGeneration
        val isAgent = _isAgentMode.value
        val listener = currentListener

        CoroutineScope(Dispatchers.IO).launch {
            HistoryRepository.save(context.applicationContext, text, sttPreset, model, language, durationMs, isAgent)
        }

        if (isAgent) {
            val contextText = listener?.getContextText() ?: ""
            val llmPreset = SecurityUtils.getLlmPreset(context)
            val llmKey = SecurityUtils.getProviderApiKey(context, "llm", llmPreset)
            if (llmKey.isNullOrBlank()) {
                showError("API Key is missing for Agent provider: ${llmPreset.uppercase()}")
                return
            }
            val llmBaseUrl = SecurityUtils.getLlmBaseUrl(context, llmPreset)
            val llmModel = SecurityUtils.getLlmModel(context, llmPreset)

            val cmdResult = CommandProcessor.processCommand(llmBaseUrl, llmModel, llmKey, text, contextText)
            cmdResult.fold(
                onSuccess = { commandResult ->
                    withContext(Dispatchers.Main) {
                        if (sessionGeneration == generation) {
                            listener?.onCommand(commandResult, contextText)
                            _recordingState.value = RecordingState.IDLE
                            currentListener = null
                        }
                    }
                },
                onFailure = { error ->
                    if (sessionGeneration == generation) {
                        showError(error.localizedMessage ?: "Agent processing failed")
                    }
                }
            )
        } else {
            withContext(Dispatchers.Main) {
                if (sessionGeneration == generation) {
                    listener?.onTranscription(text)
                    _recordingState.value = RecordingState.IDLE
                    currentListener = null
                }
            }
        }
        if (sessionGeneration == generation) {
            _isAgentMode.value = false
        }
    }

    private fun getEffectiveLanguage(context: Context): String {
        val saved = SecurityUtils.getSttLanguage(context)
        return if (saved.isNotBlank()) saved else getKeyboardLanguageCode(context)
    }

    private fun getKeyboardLanguageCode(context: Context): String {
        return try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            val subtype = imm?.currentInputMethodSubtype
            val tag = subtype?.languageTag
            if (!tag.isNullOrBlank()) {
                val lang = tag.split("-")[0].lowercase()
                if (lang.length == 2) lang else "en"
            } else {
                val localeLang = java.util.Locale.getDefault().language
                if (!localeLang.isNullOrBlank() && localeLang.length == 2) localeLang else "en"
            }
        } catch (e: Exception) {
            "en"
        }
    }

    private fun showError(message: String) {
        _errorMessage.value = message
        _recordingState.value = RecordingState.ERROR
        currentListener?.onError(message)
        Log.e(TAG, "Operation failed.")

        // Auto-clear error state back to IDLE after 4 seconds
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (_recordingState.value == RecordingState.ERROR) {
                _recordingState.value = RecordingState.IDLE
                _errorMessage.value = null
                currentListener = null
            }
        }, 4000)
    }

    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || 
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            if (_recordingState.value == RecordingState.RECORDING || _recordingState.value == RecordingState.TRANSCRIBING) {
                Log.d(TAG, "onTrimMemory: Skipping offline pipeline release while a session is active.")
                return
            }
            Log.d(TAG, "onTrimMemory: Releasing offline pipeline resources to reclaim RAM.")
            val generation = sessionGeneration
            scope.launch {
                cancelPreWarm()
                if (sessionGeneration != generation) return@launch
                OfflinePipelineProvider.releaseInstance()
            }
        }
    }

    fun destroy() {
        if (sessionOwner != SessionOwner.IME) return

        handler.removeCallbacksAndMessages(null)
        cancelPreWarm()
        amplitudeCollectJob?.cancel()
        amplitudeCollectJob = null
        engineStateCollectJob?.cancel()
        engineStateCollectJob = null
        modelErrorCollectJob?.cancel()
        modelErrorCollectJob = null
        currentListener = null

        audioRecorder?.cancelRecording()
        val ctxForTeardown = appContext
        if (ctxForTeardown != null) {
            endStreamingSession(ctxForTeardown, sessionGeneration)
        }

        // Reset all state flows so the next IME session starts clean.
        // This is critical: if the IME is destroyed mid-recording (e.g. keyboard switch),
        // these flows would otherwise carry stale state into the next session.
        _recordingState.value = RecordingState.IDLE
        _isAgentMode.value = false
        _errorMessage.value = null
        _offlineEngineState.value = OfflineEngineState.UNLOADED
        _amplitude.value = 0f
        _partialText.value = ""

        activeOffline = false
        activeEngineType = null
        offlineTextAccumulator.setLength(0)
        sessionOwner = SessionOwner.BUBBLE

        val ctx = appContext
        if (ctx != null) {
            unregisterNoisyReceiver(ctx)
        }

        // Release the offline pipeline synchronously on a background thread.
        // We CANNOT use scope.launch here because the caller (onDestroy) will cancel
        // the scope immediately after this call, preventing the coroutine from running.
        // Using a fire-and-forget thread ensures cleanup actually completes.
        Thread {
            try {
                // runBlocking on this background thread ensures release() is awaited
                kotlinx.coroutines.runBlocking {
                    OfflinePipelineProvider.releaseInstance()
                }
            } catch (e: Exception) {
                Log.w(TAG, "destroy: Error releasing offline pipeline", e)
            }
        }.apply {
            isDaemon = true
            name = "ime-cleanup"
            start()
        }
    }

    private fun registerNoisyReceiver(context: Context) {
        if (noisyReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
                val appCtx = appContext ?: return
                val generation = sessionGeneration
                handler.post {
                    if (sessionGeneration != generation) return@post
                    if (_recordingState.value == RecordingState.RECORDING || _recordingState.value == RecordingState.TRANSCRIBING) {
                        cancelSessionInternal(appCtx)
                    }
                }
            }
        }
        try {
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register audio becoming noisy receiver", e)
        }
    }

    private fun unregisterNoisyReceiver(context: Context) {
        val receiver = noisyReceiver ?: return
        noisyReceiver = null
        try {
            context.applicationContext.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister audio becoming noisy receiver", e)
        }
    }
}
