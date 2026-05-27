package com.groq.voicetyper

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import com.groq.voicetyper.offline.*

class VoiceInputIME : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    private val store by lazy { ViewModelStore() }
    private val savedStateRegistryController by lazy { SavedStateRegistryController.create(this) }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var scope: CoroutineScope
    private lateinit var composeView: ComposeView

    private var isOfflineMode by mutableStateOf(false)

    // IME State (delegated to TranscriptionSessionManager)
    private var apiKey by mutableStateOf<String?>(null)
    private var recordingState by mutableStateOf(RecordingState.IDLE)
    private var isAgentMode by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    // Backspace Swipe-to-Delete state
    private var initialCursorPos = -1
    private var swipeSelectLength = 0
    private var swipeTextBefore = ""

    private fun getCharsForWords(text: String, wordCount: Int): Int {
        if (text.isEmpty() || wordCount <= 0) return 0
        var count = 0
        var wordsFound = 0
        var i = text.length - 1
        
        while (i >= 0 && text[i].isWhitespace()) {
            count++
            i--
        }
        
        while (i >= 0 && wordsFound < wordCount) {
            while (i >= 0 && !text[i].isWhitespace()) {
                count++
                i--
            }
            wordsFound++
            while (i >= 0 && text[i].isWhitespace()) {
                count++
                i--
            }
        }
        return count
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Initializing VoiceInputIME service")
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        // Collect state from the centralized manager to update Compose states
        scope.launch {
            TranscriptionSessionManager.recordingState.collect {
                recordingState = it
            }
        }
        scope.launch {
            TranscriptionSessionManager.isAgentMode.collect {
                isAgentMode = it
            }
        }
        scope.launch {
            TranscriptionSessionManager.errorMessage.collect {
                errorMessage = it
            }
        }
    }

    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView: Creating Compose input view")
        composeView = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
        }

        // Set the window background to transparent so the app behind is visible around the floating pill
        window?.window?.let { win ->
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                win.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                win.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                win.isNavigationBarContrastEnforced = false
            }
        }

        // Set lifecycle and VM store owners on the Window DecorView for correct tree resolution
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        // Set owners on the Compose View as well to be absolutely safe
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            IMEScreen(
                audioRecorder = AudioRecorder(this), // Pass a dummy unused instance
                apiKey = apiKey,
                onBackspace = {
                    val conn = currentInputConnection
                    if (conn != null) {
                        val selectedText = conn.getSelectedText(0)
                        if (!selectedText.isNullOrEmpty()) {
                            conn.commitText("", 1)
                        } else {
                            conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                            conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                        }
                    }
                },
                onBackspaceSelect = { words ->
                    val conn = currentInputConnection ?: return@IMEScreen
                    if (initialCursorPos == -1) {
                        val extracted = conn.getExtractedText(ExtractedTextRequest(), 0)
                        initialCursorPos = extracted?.selectionStart ?: -1
                        swipeTextBefore = conn.getTextBeforeCursor(300, 0)?.toString() ?: ""
                    }
                    val lengthToSelect = getCharsForWords(swipeTextBefore, words)
                    swipeSelectLength = lengthToSelect
                    if (initialCursorPos != -1 && lengthToSelect > 0) {
                        val start = (initialCursorPos - lengthToSelect).coerceAtLeast(0)
                        conn.setSelection(start, initialCursorPos)
                    }
                },
                onBackspaceDeleteSelected = {
                    val conn = currentInputConnection
                    if (conn != null && swipeSelectLength > 0) {
                        conn.commitText("", 1)
                    }
                    initialCursorPos = -1
                    swipeSelectLength = 0
                    swipeTextBefore = ""
                },
                onBackspaceCancelSelect = {
                    val conn = currentInputConnection
                    if (conn != null && initialCursorPos != -1) {
                        conn.setSelection(initialCursorPos, initialCursorPos)
                    }
                    initialCursorPos = -1
                    swipeSelectLength = 0
                    swipeTextBefore = ""
                },
                recordingState = recordingState,
                isAgentMode = isAgentMode,
                errorMessage = errorMessage,
                onCancelRecording = {
                    TranscriptionSessionManager.cancelRecording(this@VoiceInputIME)
                },
                onStartRecording = { agentMode ->
                    val isOffline = OfflinePreferences.isOfflineModeEnabled(this@VoiceInputIME)
                    TranscriptionSessionManager.startRecording(
                        context = this@VoiceInputIME,
                        isOffline = isOffline,
                        agentMode = agentMode,
                        listener = object : SessionListener {
                            override fun onTranscription(text: String) {
                                currentInputConnection?.commitText("$text ", 1)
                            }

                            override fun onCommand(command: CommandResult, contextText: String) {
                                executeCommandAction(command, contextText)
                            }

                            override fun getContextText(): String {
                                return currentInputConnection?.getTextBeforeCursor(5000, 0)?.toString() ?: ""
                            }

                            override fun onError(message: String) {
                                // State handled by manager
                            }
                        }
                    )
                },
                onStopRecording = {
                    TranscriptionSessionManager.stopRecording(this@VoiceInputIME)
                },
                onSwitchKeyboard = {
                    try {
                        var switched = false
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                            switched = this@VoiceInputIME.switchToNextInputMethod(false)
                        } else {
                            val token = this@VoiceInputIME.window?.window?.attributes?.token
                            if (token != null) {
                                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                                switched = imm.switchToNextInputMethod(token, false)
                            }
                        }
                        if (!switched) {
                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                            imm.showInputMethodPicker()
                        }
                    } catch (e: Throwable) {
                        android.util.Log.e("VoiceInputIME", "Failed to switch keyboard", e)
                    }
                },
                isOfflineReady = ModelAssetManager.isModelReadySync(this),
                isOfflineMode = isOfflineMode
            )
        }
        return composeView
    }

    override fun onComputeInsets(outInsets: Insets?) {
        super.onComputeInsets(outInsets)
        if (outInsets == null) return
        // Guard: composeView must be initialized AND laid out (height > 0).
        // onComputeInsets can be called by the framework before onCreateInputView
        // completes its first layout pass, in which case height is 0.
        if (!::composeView.isInitialized) return
        val view = composeView
        val windowHeight = view.height
        if (windowHeight <= 0) return  // Not yet laid out; skip inset computation.

        val navBarHeight = 0 // Optional: adjust if nav bar padding is needed

        // Touch transparent padding around pill
        val density = resources.displayMetrics.density
        val pillWidth = (240 * density).toInt()
        val pillHeight = (64 * density).toInt()
        
        val left = (view.width - pillWidth) / 2
        val right = left + pillWidth
        val top = windowHeight - pillHeight - (16 * density).toInt()
        val bottom = windowHeight - navBarHeight

        val rect = android.graphics.Rect(left, top.coerceAtLeast(0), right, bottom.coerceAtLeast(0))

        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(rect)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView: Starting input view, restarting=$restarting, inputType=${info?.inputType}")
        apiKey = SecurityUtils.getApiKey(this)
        isOfflineMode = OfflinePreferences.isOfflineModeEnabled(this)

        TranscriptionSessionManager.preWarmOfflinePipeline(this)

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        TranscriptionSessionManager.cancelPreWarm()
        
        if (recordingState == RecordingState.RECORDING) {
            TranscriptionSessionManager.cancelRecording(this)
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        TranscriptionSessionManager.onTrimMemory(level)
    }

    override fun onDestroy() {
        // Cancel any active recording FIRST, before we destroy anything.
        // This ensures the SessionManager's state is clean before we reset it.
        if (recordingState == RecordingState.RECORDING || recordingState == RecordingState.TRANSCRIBING) {
            TranscriptionSessionManager.cancelRecording(this)
        }

        // Destroy the session manager state (resets all StateFlows, releases pipeline).
        TranscriptionSessionManager.destroy()

        // Explicitly clear ViewTree owners from the decor view to prevent leaking
        // references to the destroyed service instance when switching keyboards.
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(null)
            decorView.setViewTreeViewModelStoreOwner(null)
            decorView.setViewTreeSavedStateRegistryOwner(null)
        }

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        // Cancel the IME's own coroutine scope (the state collectors launched in onCreate).
        // This is safe to cancel because TranscriptionSessionManager has its own process-level scope.
        scope.cancel()
        super.onDestroy()
    }

    private fun executeCommandAction(result: CommandResult, contextText: String) {
        val conn = currentInputConnection ?: return
        Log.d(TAG, "Executing IME command action: ${result.action}")
        when (result.action) {
            "DELETE_CHARS" -> {
                if (result.deleteCount > 0) {
                    conn.deleteSurroundingText(result.deleteCount, 0)
                }
            }
            "REPLACE_TEXT" -> {
                val charsToDelete = contextText.length
                if (charsToDelete > 0) {
                    conn.deleteSurroundingText(charsToDelete, 0)
                }
                conn.commitText(result.replacementText ?: "", 1)
            }
            "INSERT_TEXT" -> {
                conn.commitText(result.insertionText ?: "", 1)
            }
            "SELECT_ALL" -> {
                conn.performContextMenuAction(android.R.id.selectAll)
            }
            "MOVE_CURSOR" -> {
                val textBefore = conn.getTextBeforeCursor(10000, 0)?.length ?: 0
                val textAfter = conn.getTextAfterCursor(10000, 0)?.length ?: 0
                val totalLength = textBefore + textAfter
                val targetPos = if (result.cursorPosition?.equals("START", ignoreCase = true) == true) 0 else totalLength
                conn.setSelection(targetPos, targetPos)
            }
            "SEND" -> {
                val editorInfo = currentInputEditorInfo
                if (editorInfo != null && editorInfo.actionId != 0) {
                    conn.performEditorAction(editorInfo.actionId)
                } else {
                    conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
        }
    }

    companion object {
        private const val TAG = "VoiceInputIME"
    }
}
