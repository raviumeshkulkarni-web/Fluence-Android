package com.groq.voicetyper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
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
}

object TranscriptionSessionManager {
    private const val TAG = "TranscriptionSessionMgr"

    private var audioRecorder: AudioRecorder? = null
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

    // This scope lives for the entire app process lifetime. We never cancel it,
    // because TranscriptionSessionManager is a process-level singleton object.
    // Individual sessions are managed through job cancellation, not scope cancellation.
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var amplitudeCollectJob: Job? = null
    private var engineStateCollectJob: Job? = null
    private var modelErrorCollectJob: Job? = null
    private var preWarmJob: Job? = null
    private var activeOffline = false
    private var activeEngineType: OfflineEngineType? = null
    private var recordingStartTimestampMs = 0L
    private val offlineTextAccumulator = StringBuilder()

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
        if (_recordingState.value != RecordingState.IDLE && _recordingState.value != RecordingState.ERROR) {
            return
        }
        cancelPreWarm()
        currentListener = listener
        _errorMessage.value = null
        _isAgentMode.value = agentMode
        recordingStartTimestampMs = System.currentTimeMillis()

        val engineType = OfflinePreferences.getEngineType(context)
        val useOffline = isOffline && !agentMode && isEngineModelReady(context, engineType)
        activeOffline = useOffline
        activeEngineType = if (useOffline) engineType else null

        if (useOffline) {
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
                                showError("Offline transcription unavailable: $error")
                            }
                        }
                    }

                    _recordingState.value = RecordingState.RECORDING
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
            audioRecorder?.startRecording()
        }
    }

    fun stopRecording(context: Context) {
        if (_recordingState.value != RecordingState.RECORDING) return
        _recordingState.value = RecordingState.TRANSCRIBING

        val durationMs = if (recordingStartTimestampMs > 0L) {
            (System.currentTimeMillis() - recordingStartTimestampMs).coerceAtLeast(0L)
        } else 0L
        recordingStartTimestampMs = 0L

        amplitudeCollectJob?.cancel()
        amplitudeCollectJob = null

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
                        val lang = getKeyboardLanguageCode(context)
                        val engineModelName = getModelName(activeEngineType ?: OfflineEngineType.SENSEVOICE)
                        CoroutineScope(Dispatchers.IO).launch {
                            HistoryRepository.save(finalTranscription, "offline", engineModelName, lang, durationMs, false)
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
        recordingStartTimestampMs = 0L
        _recordingState.value = RecordingState.IDLE
        amplitudeCollectJob?.cancel()
        amplitudeCollectJob = null

        _isAgentMode.value = false
        if (activeOffline) {
            engineStateCollectJob?.cancel()
            engineStateCollectJob = null
            modelErrorCollectJob?.cancel()
            modelErrorCollectJob = null
            _offlineEngineState.value = OfflineEngineState.UNLOADED

            scope.launch {
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
            val languageCode = getKeyboardLanguageCode(context)
            val sttBaseUrl = SecurityUtils.getSttBaseUrl(context, sttPreset)
            val sttModel = SecurityUtils.getSttModel(context, sttPreset)

            val result = GroqClient.transcribe(sttBaseUrl, sttModel, sttKey, file, languageCode)
            result.fold(
                onSuccess = { rawText ->
                    if (rawText.isNotBlank()) {
                        val text = com.groq.voicetyper.dictionary.DictionaryTextPostProcessor.process(context, rawText)
                        val isAgent = _isAgentMode.value
                        CoroutineScope(Dispatchers.IO).launch {
                            HistoryRepository.save(text, sttPreset, sttModel, languageCode, durationMs, isAgent)
                        }
                        if (isAgent) {
                            val contextText = currentListener?.getContextText() ?: ""
                            val llmBaseUrl = SecurityUtils.getLlmBaseUrl(context, llmPreset)
                            val llmModel = SecurityUtils.getLlmModel(context, llmPreset)

                            val cmdResult = CommandProcessor.processCommand(llmBaseUrl, llmModel, llmKey!!, text, contextText)
                            cmdResult.fold(
                                onSuccess = { commandResult ->
                                    withContext(Dispatchers.Main) {
                                        currentListener?.onCommand(commandResult, contextText)
                                        _recordingState.value = RecordingState.IDLE
                                        currentListener = null
                                    }
                                },
                                onFailure = { error ->
                                    showError(error.localizedMessage ?: "Agent processing failed")
                                }
                            )
                        } else {
                            withContext(Dispatchers.Main) {
                                currentListener?.onTranscription(text)
                                _recordingState.value = RecordingState.IDLE
                                currentListener = null
                            }
                        }
                    } else {
                        _recordingState.value = RecordingState.IDLE
                        currentListener = null
                    }
                    _isAgentMode.value = false
                },
                onFailure = { error ->
                    showError(error.localizedMessage ?: "Transcription failed")
                    _isAgentMode.value = false
                }
            )
        }
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
            Log.d(TAG, "onTrimMemory: Releasing offline pipeline resources to reclaim RAM.")
            scope.launch {
                cancelPreWarm()
                OfflinePipelineProvider.releaseInstance()
            }
        }
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
        cancelPreWarm()
        amplitudeCollectJob?.cancel()
        amplitudeCollectJob = null
        engineStateCollectJob?.cancel()
        engineStateCollectJob = null
        modelErrorCollectJob?.cancel()
        modelErrorCollectJob = null
        currentListener = null

        // Reset all state flows so the next IME session starts clean.
        // This is critical: if the IME is destroyed mid-recording (e.g. keyboard switch),
        // these flows would otherwise carry stale state into the next session.
        _recordingState.value = RecordingState.IDLE
        _isAgentMode.value = false
        _errorMessage.value = null
        _offlineEngineState.value = OfflineEngineState.UNLOADED
        _amplitude.value = 0f
        activeOffline = false
        activeEngineType = null
        offlineTextAccumulator.setLength(0)

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
}
