package com.groq.voicetyper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.groq.voicetyper.offline.ModelAssetManager
import com.groq.voicetyper.offline.OfflinePipelineProvider
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.offline.OfflineTranscriber
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
    private var preWarmJob: Job? = null
    private var activeOffline = false
    private val offlineTextAccumulator = StringBuilder()

    @Synchronized
    private fun initRecorder(context: Context) {
        if (audioRecorder == null) {
            audioRecorder = AudioRecorder(context.applicationContext)
        }
    }

    fun preWarmOfflinePipeline(context: Context) {
        val isOfflineMode = OfflinePreferences.isOfflineModeEnabled(context)
        if (isOfflineMode && ModelAssetManager.isModelReadySync(context)) {
            preWarmJob?.cancel()
            preWarmJob = scope.launch {
                delay(600) // Let entry animations finish
                withContext(Dispatchers.IO) {
                    try {
                        val modelDir = ModelAssetManager.getModelDir(context).absolutePath
                        val pipeline = OfflinePipelineProvider.getInstance(context)
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

        val useOffline = isOffline && !agentMode && ModelAssetManager.isModelReadySync(context)
        activeOffline = useOffline

        if (useOffline) {
            scope.launch {
                try {
                    val pipeline = OfflinePipelineProvider.getInstance(context)
                    offlineTextAccumulator.setLength(0)
                    pipeline.onTextTranscribed = { text ->
                        val cleanText = text.trim()
                        if (cleanText.isNotEmpty()) {
                            offlineTextAccumulator.append(cleanText).append(" ")
                        }
                    }

                    val modelDir = ModelAssetManager.getModelDir(context).absolutePath

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

        amplitudeCollectJob?.cancel()
        amplitudeCollectJob = null

        if (activeOffline) {
            engineStateCollectJob?.cancel()
            engineStateCollectJob = null
            _offlineEngineState.value = OfflineEngineState.UNLOADED

            scope.launch {
                try {
                    val pipeline = OfflinePipelineProvider.getInstance(context)
                    if (pipeline.isRunning.value) {
                        pipeline.stop()
                    } else {
                        pipeline.forceRelease()
                    }
                    val finalTranscription = offlineTextAccumulator.toString().trim()
                    if (finalTranscription.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            currentListener?.onTranscription(finalTranscription)
                        }
                    }
                    offlineTextAccumulator.setLength(0)
                    _recordingState.value = RecordingState.IDLE
                    _isAgentMode.value = false
                    currentListener = null
                } catch (e: Exception) {
                    showError("Offline transcription failed: ${e.localizedMessage}")
                    _isAgentMode.value = false
                }
            }
        } else {
            val file = audioRecorder?.stopRecording()
            if (file != null) {
                transcribeAudioOnline(context, file)
            } else {
                _recordingState.value = RecordingState.IDLE
                _isAgentMode.value = false
                currentListener = null
            }
        }
    }

    fun cancelRecording(context: Context) {
        _recordingState.value = RecordingState.IDLE
        amplitudeCollectJob?.cancel()
        amplitudeCollectJob = null

        _isAgentMode.value = false
        if (activeOffline) {
            engineStateCollectJob?.cancel()
            engineStateCollectJob = null
            _offlineEngineState.value = OfflineEngineState.UNLOADED

            scope.launch {
                try {
                    val pipeline = OfflinePipelineProvider.getInstance(context)
                    pipeline.forceRelease()
                } catch (e: Exception) {
                    Log.w(TAG, "Error force releasing offline pipeline on cancel", e)
                } finally {
                    offlineTextAccumulator.setLength(0)
                    currentListener = null
                }
            }
        } else {
            audioRecorder?.cancelRecording()
            currentListener = null
        }
    }

    private fun transcribeAudioOnline(context: Context, file: File) {
        val apiKey = SecurityUtils.getApiKey(context)
        if (apiKey.isNullOrBlank()) {
            showError("API Key is missing. Set it in the app.")
            file.delete()
            return
        }

        scope.launch {
            val languageCode = getKeyboardLanguageCode(context)
            val result = GroqClient.transcribe(apiKey, file, languageCode)
            result.fold(
                onSuccess = { text ->
                    if (text.isNotBlank()) {
                        if (_isAgentMode.value) {
                            val contextText = currentListener?.getContextText() ?: ""
                            val cmdResult = CommandProcessor.processCommand(apiKey, text, contextText)
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
        Log.e(TAG, "Error: $message")

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
