package com.groq.voicetyper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.groq.voicetyper.offline.*

object BubbleController {
    private const val TAG = "BubbleController"

    private val _isBubbleVisible = MutableStateFlow(false)
    val isBubbleVisible: StateFlow<Boolean> = _isBubbleVisible.asStateFlow()

    private val _isBubbleExpanded = MutableStateFlow(false)
    val isBubbleExpanded: StateFlow<Boolean> = _isBubbleExpanded.asStateFlow()

    // Delegate recording flows to the centralized TranscriptionSessionManager
    val recordingState: StateFlow<RecordingState> = TranscriptionSessionManager.recordingState
    val isAgentMode: StateFlow<Boolean> = TranscriptionSessionManager.isAgentMode
    val errorMessage: StateFlow<String?> = TranscriptionSessionManager.errorMessage
    val amplitude: StateFlow<Float> = TranscriptionSessionManager.amplitude

    private var applicationContext: Context? = null

    /**
     * Strong reference to the currently-focused editable accessibility node.
     * We use obtain() when caching and recycle the previous node to avoid leaks.
     */
    private var activeNode: AccessibilityNodeInfo? = null
    private val nodeLock = Any()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    fun showBubble(context: Context, node: AccessibilityNodeInfo) {
        applicationContext = context.applicationContext

        // Cache a strong reference to the focused node.
        // obtain() creates a copy so the original can be recycled by the caller.
        @Suppress("DEPRECATION")
        synchronized(nodeLock) {
            val newNode = AccessibilityNodeInfo.obtain(node)
            activeNode?.recycle()
            activeNode = newNode
        }

        // Only start the foreground service if bubble wasn't already visible.
        // Avoids redundant startForegroundService calls which can crash on some OEMs.
        val wasVisible = _isBubbleVisible.value
        _isBubbleVisible.value = true

        if (!wasVisible) {
            try {
                val intent = Intent(context, FloatingBubbleService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start FloatingBubbleService", e)
            }
        }

        // Pre-initialize/pre-warm offline pipeline
        val appCtx = context.applicationContext
        TranscriptionSessionManager.preWarmOfflinePipeline(appCtx)
    }

    fun hideBubble() {
        TranscriptionSessionManager.cancelPreWarm()
        // Only cancel if actively recording — don't discard an in-flight transcription
        if (recordingState.value == RecordingState.RECORDING) {
            cancelRecording()
        }
        _isBubbleVisible.value = false
        _isBubbleExpanded.value = false
        @Suppress("DEPRECATION")
        synchronized(nodeLock) {
            activeNode?.recycle()
            activeNode = null
        }
    }

    fun onTrimMemory(level: Int) {
        TranscriptionSessionManager.onTrimMemory(level)
    }

    /**
     * Hides the bubble AND stops the FloatingBubbleService entirely.
     * Call when the feature is disabled or the accessibility service is destroyed.
     */
    fun stopService(context: Context) {
        hideBubble()
        try {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop FloatingBubbleService", e)
        }
    }

    fun startRecording(context: Context, agentMode: Boolean = false) {
        _isBubbleExpanded.value = true
        applicationContext = context.applicationContext

        val isOffline = OfflinePreferences.isOfflineModeEnabled(context)
        TranscriptionSessionManager.startRecording(
            context = context,
            isOffline = isOffline,
            agentMode = agentMode,
            listener = object : SessionListener {
                override fun onTranscription(text: String) {
                    mainHandler.post {
                        injectText(context, "$text ")
                        _isBubbleExpanded.value = false
                    }
                }

                override fun onCommand(command: CommandResult, contextText: String) {
                    mainHandler.post {
                        executeCommandAction(context, command, contextText.length)
                        _isBubbleExpanded.value = false
                    }
                }

                override fun getContextText(): String {
                    return synchronized(nodeLock) {
                        val node = activeNode
                        if (node != null) {
                            try { node.refresh() } catch (_: Exception) {}
                            if (node.isShowingHintText) {
                                ""
                            } else {
                                val fullText = node.text?.toString() ?: ""
                                val selectionStart = node.textSelectionStart
                                val beforeCursorText = if (selectionStart in 0..fullText.length) {
                                    fullText.substring(0, selectionStart)
                                } else {
                                    fullText
                                }
                                if (beforeCursorText.length > 5000) {
                                    beforeCursorText.substring(beforeCursorText.length - 5000)
                                } else {
                                    beforeCursorText
                                }
                            }
                        } else ""
                    }
                }

                override fun onError(message: String) {
                    mainHandler.post {
                        // Error handling UI auto-collapses bubble on error after timeout
                        mainHandler.removeCallbacksAndMessages(null)
                        mainHandler.postDelayed({
                            if (recordingState.value == RecordingState.IDLE) {
                                _isBubbleExpanded.value = false
                            }
                        }, 4000)
                    }
                }
            }
        )
    }

    fun stopRecording(context: Context) {
        TranscriptionSessionManager.stopRecording(context)
    }

    fun cancelRecording() {
        val ctx = applicationContext ?: return
        TranscriptionSessionManager.cancelRecording(ctx)
        _isBubbleExpanded.value = false
    }

    private fun executeCommandAction(context: Context, result: CommandResult, contextTextLength: Int) {
        Log.d(TAG, "Executing command action: ${result.action}")
        when (result.action) {
            "DELETE_CHARS" -> {
                performDeleteChars(result.deleteCount)
            }
            "REPLACE_TEXT" -> {
                result.replacementText?.let { performReplaceText(context, it, contextTextLength) }
            }
            "INSERT_TEXT" -> {
                result.insertionText?.let { injectText(context, it) }
            }
            "SELECT_ALL" -> {
                performSelectAll()
            }
            "MOVE_CURSOR" -> {
                result.cursorPosition?.let { performMoveCursor(it) }
            }
            "SEND" -> {
                val node = synchronized(nodeLock) { activeNode } ?: return
                val actions = node.actionList
                if (actions != null) {
                    for (action in actions) {
                        if (action.id == AccessibilityNodeInfo.ACTION_CLICK) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            break
                        }
                    }
                }
            }
        }
    }

    private fun restoreClipboard(clipboard: ClipboardManager, originalClip: android.content.ClipData?) {
        try {
            if (originalClip != null) {
                clipboard.setPrimaryClip(originalClip)
            } else {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore clipboard", e)
        }
    }

    private fun pasteTextViaClipboard(context: Context, node: AccessibilityNodeInfo, textToPaste: String, fallback: () -> Unit) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: run {
            fallback()
            return
        }
        
        try {
            val primaryClip = clipboard.primaryClip
            clipboard.setPrimaryClip(ClipData.newPlainText("voice_input", textToPaste))
            val success = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!success) {
                Log.w(TAG, "ACTION_PASTE returned false, restoring clipboard and running fallback")
                restoreClipboard(clipboard, primaryClip)
                fallback()
                return
            }
            
            // Only schedule delayed restore when paste succeeded —
            // gives the target app time to process the paste IPC before we swap the clipboard back.
            scope.launch(Dispatchers.Main) {
                kotlinx.coroutines.delay(200)
                restoreClipboard(clipboard, primaryClip)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in pasteTextViaClipboard", e)
            fallback()
        }
    }

    /**
     * Injects text into the active node at the current cursor position.
     */
    fun injectText(context: Context, text: String) {
        val node = synchronized(nodeLock) { activeNode } ?: return

        // Attempt to refresh the node to get up-to-date text/cursor state.
        // If refresh fails (common in WebViews or after window changes), we
        // still try to inject — the cached node often remains functional.
        val refreshed = try { node.refresh() } catch (e: Exception) { false }
        if (!refreshed) {
            Log.w(TAG, "Node refresh returned false — attempting injection anyway")
        }

        val currentText = if (node.isShowingHintText) "" else (node.text ?: "")
        val selectionStart = if (node.isShowingHintText) 0 else node.textSelectionStart
        val selectionEnd = if (node.isShowingHintText) 0 else node.textSelectionEnd
        val textToInsert = "${text.trim()} "

        if (selectionStart >= 0 && selectionEnd >= 0) {
            val selectBundle = Bundle()
            selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selectionStart)
            selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selectionEnd)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            
            pasteTextViaClipboard(context, node, textToInsert) {
                val newText = StringBuilder(currentText)
                    .replace(selectionStart, selectionEnd, textToInsert)
                    .toString()
                val bundle = Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                if (success) {
                    val newCursorPos = selectionStart + textToInsert.length
                    val selectBundle2 = Bundle()
                    selectBundle2.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                    selectBundle2.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle2)
                }
            }
        } else {
            pasteTextViaClipboard(context, node, textToInsert) {
                val newText = currentText.toString() + textToInsert
                val bundle = Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            }
        }
    }

    fun performReplaceText(context: Context, newText: String, contextTextLength: Int) {
        val node = synchronized(nodeLock) { activeNode } ?: return
        try { node.refresh() } catch (_: Exception) {}
        
        val currentText = node.text ?: ""
        val selectionStart = node.textSelectionStart
        val selectionEnd = node.textSelectionEnd
        
        if (selectionStart >= 0 && selectionStart == selectionEnd) {
            val startPos = maxOf(0, selectionStart - contextTextLength)
            val selectBundle = Bundle()
            selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, startPos)
            selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selectionStart)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            
            pasteTextViaClipboard(context, node, newText) {
                val replacedText = StringBuilder(currentText)
                    .replace(startPos, selectionStart, newText)
                    .toString()
                val bundle = Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacedText)
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                if (success) {
                    val newCursorPos = startPos + newText.length
                    val selectBundle2 = Bundle()
                    selectBundle2.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                    selectBundle2.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle2)
                }
            }
        } else {
            // Fallback: replace everything
            val textLength = currentText.length
            val selectBundle = Bundle()
            selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLength)
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            
            pasteTextViaClipboard(context, node, newText) {
                val bundle = Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                if (success) {
                    val selectBundle2 = Bundle()
                    selectBundle2.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
                    selectBundle2.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle2)
                }
            }
        }
    }

    fun performSelectAll() {
        val node = synchronized(nodeLock) { activeNode } ?: return
        try { node.refresh() } catch (_: Exception) {}
        val textLength = node.text?.length ?: 0
        val selectBundle = Bundle()
        selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLength)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
    }

    fun performMoveCursor(position: String) {
        val node = synchronized(nodeLock) { activeNode } ?: return
        try { node.refresh() } catch (_: Exception) {}
        val textLength = node.text?.length ?: 0
        val targetPos = if (position.equals("START", ignoreCase = true)) 0 else textLength
        val selectBundle = Bundle()
        selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, targetPos)
        selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, targetPos)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
    }

    fun performDeleteChars(count: Int) {
        val node = synchronized(nodeLock) { activeNode } ?: return
        try { node.refresh() } catch (_: Exception) {}
        if (node.isShowingHintText || count <= 0) return

        val currentText = node.text ?: ""
        val selectionStart = node.textSelectionStart
        val selectionEnd = node.textSelectionEnd

        if (selectionStart >= count && selectionStart == selectionEnd) {
            val newText = StringBuilder(currentText).delete(selectionStart - count, selectionStart).toString()
            val bundle = Bundle()
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (success) {
                val newCursorPos = selectionStart - count
                val selectBundle = Bundle()
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            }
        } else {
            val textLength = currentText.length
            if (textLength >= count) {
                val newText = StringBuilder(currentText).delete(textLength - count, textLength).toString()
                val bundle = Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                if (success) {
                    val newCursorPos = textLength - count
                    val selectBundle = Bundle()
                    selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                    selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                    node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
                }
            }
        }
    }

    fun performBackspace() {
        val node = synchronized(nodeLock) { activeNode } ?: return
        try { node.refresh() } catch (_: Exception) {}

        if (node.isShowingHintText) {
            return
        }

        val currentText = node.text ?: ""
        val selectionStart = node.textSelectionStart
        val selectionEnd = node.textSelectionEnd

        if (selectionStart > 0 && selectionStart == selectionEnd) {
            val newText = StringBuilder(currentText).deleteAt(selectionStart - 1).toString()
            val bundle = Bundle()
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (success) {
                val newCursorPos = selectionStart - 1
                val selectBundle = Bundle()
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            }
        } else if (selectionStart >= 0 && selectionEnd > selectionStart) {
            val newText = StringBuilder(currentText).delete(selectionStart, selectionEnd).toString()
            val bundle = Bundle()
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (success) {
                val selectBundle = Bundle()
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selectionStart)
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selectionStart)
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            }
        }
    }
}
