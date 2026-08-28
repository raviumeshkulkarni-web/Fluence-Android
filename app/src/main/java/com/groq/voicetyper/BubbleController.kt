package com.groq.voicetyper

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
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

    // Defer stopService so an accessibility show/hide flap can't race an in-flight
    // startForeground() (crashes with ForegroundServiceDidNotStartInTimeException).
    private const val STOP_SERVICE_DELAY_MS = 1500L

    // Depth cap for the WebView ancestor walk used by the SET_TEXT classifier.
    private const val MAX_ANCESTOR_DEPTH = 25

    private val _isBubbleVisible = MutableStateFlow(false)
    val isBubbleVisible: StateFlow<Boolean> = _isBubbleVisible.asStateFlow()

    private val _isBubbleExpanded = MutableStateFlow(false)
    val isBubbleExpanded: StateFlow<Boolean> = _isBubbleExpanded.asStateFlow()

    private val _isAnchoredRight = MutableStateFlow(true)
    val isAnchoredRight: StateFlow<Boolean> = _isAnchoredRight.asStateFlow()



    fun updateAnchoredRight(anchoredRight: Boolean) {
        _isAnchoredRight.value = anchoredRight
    }

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

    private val deferredStopService = Runnable {
        stopFloatingBubbleService()
    }
    private val errorCollapseRunnable = Runnable {
        if (recordingState.value == RecordingState.IDLE) {
            _isBubbleExpanded.value = false
        }
    }

    fun showBubble(context: Context, node: AccessibilityNodeInfo) {
        if (PrivacyPreferences.isPackageExcluded(context, node.packageName?.toString())) {
            suppressForPrivacy()
            return
        }

        applicationContext = context.applicationContext

        // A bubble re-show within the defer window keeps the service alive.
        mainHandler.removeCallbacks(deferredStopService)

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

        if (!wasVisible && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted — cannot start bubble foreground service")
            return
        }

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
                _isBubbleVisible.value = false
            }
        }

        // Pre-initialize/pre-warm offline pipeline
        val appCtx = context.applicationContext
        TranscriptionSessionManager.preWarmOfflinePipeline(appCtx)
    }

    /**
     * Removes bubble interaction without cancelling an active recording or
     * transcription session. The existing hideBubble() intentionally retains
     * its legacy cancellation behavior for non-privacy lifecycle callers.
     */
    fun suppressForPrivacy() {
        TranscriptionSessionManager.cancelPreWarm()
        _isBubbleVisible.value = false
        _isBubbleExpanded.value = false
        if (recordingState.value == RecordingState.IDLE) {
            @Suppress("DEPRECATION")
            synchronized(nodeLock) {
                activeNode?.recycle()
                activeNode = null
            }
        }
        mainHandler.removeCallbacks(deferredStopService)
        mainHandler.postDelayed(deferredStopService, STOP_SERVICE_DELAY_MS)
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
        // Defer stopping the foreground service so a rapid accessibility show/hide
        // flap cannot stop it before startForeground() runs (crash). Re-shows cancel it.
        mainHandler.removeCallbacks(deferredStopService)
        mainHandler.postDelayed(deferredStopService, STOP_SERVICE_DELAY_MS)
    }

    private fun stopFloatingBubbleService() {
        val ctx = applicationContext ?: return
        try {
            ctx.stopService(Intent(ctx, FloatingBubbleService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop FloatingBubbleService", e)
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

    private fun activeNodePackage(): String? {
        return synchronized(nodeLock) {
            activeNode?.packageName?.toString()
        }
    }

    private fun isTargetAllowed(
        context: Context,
        targetPackage: String?,
        verifyActiveWindow: Boolean = false
    ): Boolean {
        if (PrivacyPreferences.isPackageExcluded(context, targetPackage)) {
            return false
        }
        return true
    }

    private fun isCurrentTargetAllowed(): Boolean {
        val context = applicationContext ?: return false
        return isTargetAllowed(context, activeNodePackage())
    }

    private fun isNodeTargetAllowed(context: Context, node: AccessibilityNodeInfo): Boolean {
        return !PrivacyPreferences.isPackageExcluded(context, node.packageName?.toString()) &&
            isTargetAllowed(context, activeNodePackage())
    }

    private fun performAllowedAction(
        context: Context,
        node: AccessibilityNodeInfo,
        action: Int,
        arguments: Bundle? = null
    ): Boolean {
        if (!isNodeTargetAllowed(context, node)) return false
        return if (arguments == null) {
            node.performAction(action)
        } else {
            node.performAction(action, arguments)
        }
    }

    private fun performCurrentTargetAction(
        node: AccessibilityNodeInfo,
        action: Int,
        arguments: Bundle? = null
    ): Boolean {
        val context = applicationContext ?: return false
        return performAllowedAction(context, node, action, arguments)
    }

    fun startRecording(context: Context, agentMode: Boolean = false) {
        val targetPackage = activeNodePackage()
        _isBubbleExpanded.value = true
        applicationContext = context.applicationContext

        val isOffline = OfflinePreferences.isOfflineModeEnabled(context)
        TranscriptionSessionManager.startRecording(
            context = context,
            isOffline = isOffline,
            agentMode = agentMode,
            targetPackage = targetPackage,
            listener = object : SessionListener {
                override fun onTranscription(text: String) {
                    mainHandler.post {
                        if (!PrivacyPreferences.isPackageExcluded(context, targetPackage)) {
                            injectText(context, "$text ")
                        }
                        _isBubbleExpanded.value = false
                    }
                }

                override fun onCommand(command: CommandResult, contextText: String) {
                    mainHandler.post {
                        if (!PrivacyPreferences.isPackageExcluded(context, targetPackage)) {
                            executeCommandAction(context, command, contextText.length)
                        }
                        _isBubbleExpanded.value = false
                    }
                }

                override fun getContextText(): String {
                    if (PrivacyPreferences.isPackageExcluded(context, targetPackage)) return ""
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
                        // Error handling UI auto-collapses bubble on error after timeout.
                        // Only cancel the pending collapse, never the deferred service stop.
                        mainHandler.removeCallbacks(errorCollapseRunnable)
                        mainHandler.postDelayed(errorCollapseRunnable, 4000)
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
        if (!isTargetAllowed(context, activeNodePackage(), verifyActiveWindow = true)) return
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
                            performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_CLICK)
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

        // Capture the original clip up front so every path below can restore it.
        val primaryClip = try {
            clipboard.primaryClip
        } catch (e: Exception) {
            Log.w(TAG, "Could not read current clipboard", e)
            null
        }

        try {
            if (!isNodeTargetAllowed(context, node)) return
            clipboard.setPrimaryClip(ClipData.newPlainText("voice_input", textToPaste))
            if (!isNodeTargetAllowed(context, node)) {
                restoreClipboard(clipboard, primaryClip)
                return
            }
            val success = performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_PASTE)
            if (!success) {
                Log.w(TAG, "ACTION_PASTE returned false, restoring clipboard and running fallback")
                restoreClipboard(clipboard, primaryClip)
                fallback()
                return
            }

            // Only schedule delayed restore when paste succeeded —
            // gives the target app time to process the paste IPC before we swap the clipboard back.
            scope.launch(Dispatchers.Main) {
                try {
                    kotlinx.coroutines.delay(50)
                } finally {
                    restoreClipboard(clipboard, primaryClip)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in pasteTextViaClipboard", e)
            // The clip was swapped to the transcription; always restore it, even on
            // failure, so the transcription never lingers in the system clipboard.
            restoreClipboard(clipboard, primaryClip)
            fallback()
        }
    }

    /**
     * Decides whether to attempt ACTION_SET_TEXT before the existing clipboard path.
     *
     * Only native, plain-text editable fields qualify. Every ambiguous or risky case
     * returns false so the untouched clipboard implementation runs unchanged.
     */
    private fun shouldPreferSetText(node: AccessibilityNodeInfo, currentText: CharSequence): Boolean {
        // Unknown or missing ACTION_SET_TEXT — cannot confirm support.
        val actions = node.actionList ?: return false
        if (actions.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return false

        // contenteditable-style nodes advertise SET_TEXT without isEditable
        // (see FluenceAccessibilityService.isEditableTextField). They historically
        // needed clipboard insertion — keep the reference path for them.
        if (!node.isEditable) return false

        // WebView-hosted editors (Chrome/Brave/System WebView) can dispatch SET_TEXT
        // without persisting it — keep the reference clipboard path for them.
        if (hasWebViewAncestor(node)) return false

        // Whole-field SET_TEXT would flatten formatting spans that paste preserves.
        if (currentText is android.text.Spanned &&
            currentText.getSpans(0, currentText.length, Any::class.java).isNotEmpty()) {
            return false
        }

        return true
    }

    /**
     * Walks the ancestor chain (bounded) looking for a WebView container. Chromium
     * exposes web content as a virtual accessibility subtree hosted under the
     * WebView view node, so a focused web input is always a descendant of a node
     * whose class name contains "WebView". Any traversal failure defaults to the
     * clipboard path (conservative).
     */
    @Suppress("DEPRECATION")
    private fun hasWebViewAncestor(node: AccessibilityNodeInfo): Boolean {
        var current = node.parent
        var depth = 0
        try {
            while (current != null && depth < MAX_ANCESTOR_DEPTH) {
                val cls = current.className?.toString()?.lowercase() ?: ""
                val parent = current.parent
                current.recycle()
                current = parent
                if (cls.contains("webview")) return true
                depth++
            }
        } catch (e: Exception) {
            // Cannot verify ancestry — fall back to the clipboard path.
            return true
        } finally {
            // Recycle the final acquired parent on every exit path (depth cap,
            // exception, or a WebView hit) so no node leaks.
            if (current != null) current.recycle()
        }
        return false
    }

    /**
     * Applies [newText] via ACTION_SET_TEXT and restores the cursor to [newCursorPos].
     * Returns true only when the action was actually performed.
     */
    private fun trySetText(context: Context, node: AccessibilityNodeInfo, newText: String, newCursorPos: Int): Boolean {
        val setBundle = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        }
        if (!performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_SET_TEXT, setBundle)) return false
        val selectBundle = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
        }
        performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
        return true
    }

    /**
     * True when the node is showing its hint rather than real text. Some apps
     * (e.g. WhatsApp) expose the hint as the accessibility text without setting
     * the showing-hint flag, so also treat a field whose text exactly equals its
     * hint as empty — otherwise SET_TEXT would type the hint as real text.
     */
    @Suppress("DEPRECATION")
    private fun isHintEmpty(node: AccessibilityNodeInfo): Boolean {
        if (node.isShowingHintText) return true
        val hint = node.hintText
        if (hint != null && hint.isNotEmpty()) {
            return node.text?.toString() == hint.toString()
        }
        return false
    }

    /**
     * True when a field renders a placeholder as actual field text (WhatsApp
     * exposes its "Message" placeholder as node.text with no hint flags and no
     * selection). Real text in a focused editable field always carries a
     * selection, so non-empty text with no hint and no selection is the
     * placeholder signal. SET_TEXT must not reconstruct from such text, or it
     * types the placeholder as real content.
     */
    private fun isPlaceholderText(node: AccessibilityNodeInfo): Boolean {
        if (node.text.isNullOrEmpty()) return false
        if (node.isShowingHintText) return false
        @Suppress("DEPRECATION")
        val hint = node.hintText
        if (hint != null && hint.isNotEmpty()) return false
        return node.textSelectionStart == -1 && node.textSelectionEnd == -1
    }

    /**
     * Injects text into the active node at the current cursor position.
     */
    fun injectText(context: Context, text: String) {
        if (!isTargetAllowed(context, activeNodePackage(), verifyActiveWindow = true)) return
        val node = synchronized(nodeLock) { activeNode } ?: return
        if (!isNodeTargetAllowed(context, node)) return

        // Attempt to refresh the node to get up-to-date text/cursor state.
        // If refresh fails (common in WebViews or after window changes), we
        // still try to inject — the cached node often remains functional.
        val refreshed = try { node.refresh() } catch (e: Exception) { false }
        if (!refreshed) {
            Log.w(TAG, "Node refresh returned false — attempting injection anyway")
        }
        if (!isNodeTargetAllowed(context, node)) return

        val placeholderText = isPlaceholderText(node)
        val hintEmpty = isHintEmpty(node) || placeholderText
        val currentText = if (hintEmpty) "" else (node.text ?: "")
        val selectionStart = if (hintEmpty) 0 else node.textSelectionStart
        val selectionEnd = if (hintEmpty) 0 else node.textSelectionEnd
        val textToInsert = "${text.trim()} "

        if (selectionStart >= 0 && selectionEnd >= 0) {
            val selectBundle = Bundle()
            selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selectionStart)
            selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selectionEnd)
            performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            
            if (shouldPreferSetText(node, currentText) && !placeholderText) {
                val newText = StringBuilder(currentText)
                    .replace(selectionStart, selectionEnd, textToInsert)
                    .toString()
                val newCursorPos = selectionStart + textToInsert.length
                if (trySetText(context, node, newText, newCursorPos)) return
            }

            pasteTextViaClipboard(context, node, textToInsert) {
                if (!isTargetAllowed(context, activeNodePackage(), verifyActiveWindow = true)) return@pasteTextViaClipboard
                val newText = StringBuilder(currentText)
                    .replace(selectionStart, selectionEnd, textToInsert)
                    .toString()
                val bundle = Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                val success = performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                if (success) {
                    val newCursorPos = selectionStart + textToInsert.length
                    val selectBundle2 = Bundle()
                    selectBundle2.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                    selectBundle2.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                    performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle2)
                }
            }
        } else {
            if (shouldPreferSetText(node, currentText) && !placeholderText) {
                val newText = currentText.toString() + textToInsert
                if (trySetText(context, node, newText, newText.length)) return
            }

            pasteTextViaClipboard(context, node, textToInsert) {
                if (!isTargetAllowed(context, activeNodePackage(), verifyActiveWindow = true)) return@pasteTextViaClipboard
                val newText = currentText.toString() + textToInsert
                val bundle = Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            }
        }
    }

    fun performReplaceText(context: Context, newText: String, contextTextLength: Int) {
        if (!isTargetAllowed(context, activeNodePackage(), verifyActiveWindow = true)) return
        val node = synchronized(nodeLock) { activeNode } ?: return
        if (!isNodeTargetAllowed(context, node)) return
        try { node.refresh() } catch (_: Exception) {}
        if (!isNodeTargetAllowed(context, node)) return
        
        val currentText = node.text ?: ""
        val selectionStart = node.textSelectionStart
        val selectionEnd = node.textSelectionEnd
        
        if (selectionStart >= 0 && selectionStart == selectionEnd) {
            val startPos = maxOf(0, selectionStart - contextTextLength)
            val selectBundle = Bundle()
            selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, startPos)
            selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selectionStart)
            performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            
            if (shouldPreferSetText(node, currentText)) {
                val replacedText = StringBuilder(currentText)
                    .replace(startPos, selectionStart, newText)
                    .toString()
                val newCursorPos = startPos + newText.length
                if (trySetText(context, node, replacedText, newCursorPos)) return
            }

            pasteTextViaClipboard(context, node, newText) {
                val replacedText = StringBuilder(currentText)
                    .replace(startPos, selectionStart, newText)
                    .toString()
                val bundle = Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacedText)
                val success = performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                if (success) {
                    val newCursorPos = startPos + newText.length
                    val selectBundle2 = Bundle()
                    selectBundle2.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                    selectBundle2.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                    performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle2)
                }
            }
        } else {
            // Fallback: replace everything
            val textLength = currentText.length
            val selectBundle = Bundle()
            selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLength)
            performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            
            if (shouldPreferSetText(node, currentText)) {
                if (trySetText(context, node, newText, newText.length)) return
            }

            pasteTextViaClipboard(context, node, newText) {
                val bundle = Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                val success = performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                if (success) {
                    val selectBundle2 = Bundle()
                    selectBundle2.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length)
                    selectBundle2.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length)
                    performAllowedAction(context, node, AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle2)
                }
            }
        }
    }

    fun performSelectAll() {
        if (!isCurrentTargetAllowed()) return
        val node = synchronized(nodeLock) { activeNode } ?: return
        if (!isNodeTargetAllowed(applicationContext ?: return, node)) return
        try { node.refresh() } catch (_: Exception) {}
        if (!isCurrentTargetAllowed()) return
        val textLength = node.text?.length ?: 0
        val selectBundle = Bundle()
        selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
        selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, textLength)
        performCurrentTargetAction(node, AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
    }

    fun performMoveCursor(position: String) {
        if (!isCurrentTargetAllowed()) return
        val node = synchronized(nodeLock) { activeNode } ?: return
        if (!isNodeTargetAllowed(applicationContext ?: return, node)) return
        try { node.refresh() } catch (_: Exception) {}
        if (!isCurrentTargetAllowed()) return
        val textLength = node.text?.length ?: 0
        val targetPos = if (position.equals("START", ignoreCase = true)) 0 else textLength
        val selectBundle = Bundle()
        selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, targetPos)
        selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, targetPos)
        performCurrentTargetAction(node, AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
    }

    fun performDeleteChars(count: Int) {
        if (!isCurrentTargetAllowed()) return
        val node = synchronized(nodeLock) { activeNode } ?: return
        if (!isNodeTargetAllowed(applicationContext ?: return, node)) return
        try { node.refresh() } catch (_: Exception) {}
        if (!isCurrentTargetAllowed()) return
        if (node.isShowingHintText || count <= 0) return

        val currentText = node.text ?: ""
        val selectionStart = node.textSelectionStart
        val selectionEnd = node.textSelectionEnd

        if (selectionStart >= count && selectionStart == selectionEnd) {
            val newText = StringBuilder(currentText).delete(selectionStart - count, selectionStart).toString()
            val bundle = Bundle()
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            val success = performCurrentTargetAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (success) {
                val newCursorPos = selectionStart - count
                val selectBundle = Bundle()
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                performCurrentTargetAction(node, AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            }
        } else {
            val textLength = currentText.length
            if (textLength >= count) {
                val newText = StringBuilder(currentText).delete(textLength - count, textLength).toString()
                val bundle = Bundle()
                bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
                val success = performCurrentTargetAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                if (success) {
                    val newCursorPos = textLength - count
                    val selectBundle = Bundle()
                    selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                    selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                    performCurrentTargetAction(node, AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
                }
            }
        }
    }

    fun performBackspace() {
        if (!isCurrentTargetAllowed()) return
        val node = synchronized(nodeLock) { activeNode } ?: return
        if (!isNodeTargetAllowed(applicationContext ?: return, node)) return
        try { node.refresh() } catch (_: Exception) {}
        if (!isCurrentTargetAllowed()) return

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
            val success = performCurrentTargetAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (success) {
                val newCursorPos = selectionStart - 1
                val selectBundle = Bundle()
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
                performCurrentTargetAction(node, AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            }
        } else if (selectionStart >= 0 && selectionEnd > selectionStart) {
            val newText = StringBuilder(currentText).delete(selectionStart, selectionEnd).toString()
            val bundle = Bundle()
            bundle.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
            val success = performCurrentTargetAction(node, AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            if (success) {
                val selectBundle = Bundle()
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, selectionStart)
                selectBundle.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, selectionStart)
                performCurrentTargetAction(node, AccessibilityNodeInfo.ACTION_SET_SELECTION, selectBundle)
            }
        }
    }
}
