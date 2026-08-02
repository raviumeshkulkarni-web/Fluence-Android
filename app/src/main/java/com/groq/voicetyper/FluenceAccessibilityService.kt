package com.groq.voicetyper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class FluenceAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FluenceA11y"

        /** Max recursion depth when walking the accessibility tree. */
        private const val MAX_TREE_DEPTH = 25

        /** Max nodes examined by the recursive tree walk per evaluation pass. */
        private const val MAX_TRAVERSAL_NODES = 150

        /**
         * Debounce interval (ms). Rapid-fire accessibility events (especially
         * TYPE_WINDOW_CONTENT_CHANGED) can flood the handler — we collapse them
         * into a single evaluation pass.
         */
        private const val DEBOUNCE_MS = 120L
    }

    private var isFloatingBubbleEnabled = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingEvaluation: Runnable? = null

    private val prefListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "floating_bubble_enabled") {
                isFloatingBubbleEnabled = prefs.getBoolean(key, false)
                if (!isFloatingBubbleEnabled) {
                    cancelPendingEvaluation()
                    BubbleController.stopService(this)
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        val sharedPrefs = getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
        isFloatingBubbleEnabled = sharedPrefs.getBoolean("floating_bubble_enabled", false)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    // ────────────────────────────────────────────────────────────────────
    //  Event Dispatch
    // ────────────────────────────────────────────────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isFloatingBubbleEnabled) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                // Immediate: the user just tapped/focused a specific view.
                val source = event.source
                if (source != null) {
                    handleFocusChange(source)
                } else {
                    scheduleFullEvaluation()
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // A new window appeared (app switch, dialog, etc.) — re-evaluate.
                scheduleFullEvaluation()
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Content changed inside a window (e.g. WebView finished loading).
                // This fires *very* frequently, so we debounce it.
                scheduleFullEvaluation()
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Text changed in a view — the user is typing or voice text was
                // injected. Keep the bubble visible if there's a focused editable.
                val source = event.source
                if (source != null && isEditableTextField(source)) {
                    BubbleController.showBubble(this, source)
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Focus Evaluation
    // ────────────────────────────────────────────────────────────────────

    /**
     * Called when we have a direct [node] reference from an event source.
     * We first check the node itself; if it's not an editable field, we fall
     * back to scanning all application windows.
     */
    private fun handleFocusChange(node: AccessibilityNodeInfo) {
        if (isEditableTextField(node)) {
            if (isSecureField(node)) {
                BubbleController.hideBubble()
            } else {
                BubbleController.showBubble(this, node)
            }
            return
        }

        // The event source wasn't editable — maybe focus moved to a label or
        // container. Do a full scan to find the real focused input.
        evaluateAllWindows()
    }

    /**
     * Debounced full evaluation. Collapses rapid events into one pass.
     */
    private fun scheduleFullEvaluation() {
        cancelPendingEvaluation()
        val runnable = Runnable { evaluateAllWindows() }
        pendingEvaluation = runnable
        handler.postDelayed(runnable, DEBOUNCE_MS)
    }

    private fun cancelPendingEvaluation() {
        pendingEvaluation?.let { handler.removeCallbacks(it) }
        pendingEvaluation = null
    }

    /**
     * Scans **all** application windows (not just `rootInActiveWindow`) to find
     * a focused editable text field. This is critical because:
     *
     * 1. When the soft keyboard is open, Android may report the IME window as
     *    the "active" window, making [rootInActiveWindow] return the IME root
     *    instead of the app's root.
     * 2. Multi-window / split-screen scenarios have multiple app windows.
     * 3. WebViews in browsers like Brave host their own accessibility subtree
     *    inside a child window.
     */
    private fun evaluateAllWindows() {
        try {
            val appWindows = windows
                ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?: emptyList()

            // Attempt 1: Use the platform focus API on each application window.
            for (window in appWindows) {
                val root = window.root ?: continue
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && isEditableTextField(focused)) {
                    if (isSecureField(focused)) {
                        BubbleController.hideBubble()
                    } else {
                        BubbleController.showBubble(this, focused)
                    }
                    focused.recycle()
                    root.recycle()
                    return
                }
                focused?.recycle()
                root.recycle()
            }

            // Attempt 2: Recursive tree walk. Some WebView implementations
            // don't set FOCUS_INPUT but still report `isFocused()` on the node.
            val budget = intArrayOf(MAX_TRAVERSAL_NODES)
            for (window in appWindows) {
                val root = window.root ?: continue
                val found = findFocusedEditableNode(root, 0, budget)
                if (found != null) {
                    if (found !== root) {
                        root.recycle()
                    }
                    if (isSecureField(found)) {
                        BubbleController.hideBubble()
                    } else {
                        BubbleController.showBubble(this, found)
                    }
                    found.recycle()
                    return
                }
                root.recycle()
            }

            // Attempt 3: Legacy fallback with rootInActiveWindow.
            val legacyRoot = rootInActiveWindow
            if (legacyRoot != null) {
                val focused = legacyRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && isEditableTextField(focused)) {
                    if (isSecureField(focused)) {
                        BubbleController.hideBubble()
                    } else {
                        BubbleController.showBubble(this, focused)
                    }
                    focused.recycle()
                    legacyRoot.recycle()
                    return
                }
                focused?.recycle()
                legacyRoot.recycle()
            }

            // No focused editable field found anywhere — hide the bubble.
            BubbleController.hideBubble()

        } catch (e: Exception) {
            Log.w(TAG, "evaluateAllWindows failed", e)
            // Don't hide on transient errors — keep current state.
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Recursive Tree Walk
    // ────────────────────────────────────────────────────────────────────

    /**
     * Walks the accessibility node tree depth-first looking for a node that is
     * both focused and editable. Limits depth to [MAX_TREE_DEPTH] to prevent
     * runaway traversal in pathological trees.
     */
    private fun findFocusedEditableNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        budget: IntArray
    ): AccessibilityNodeInfo? {
        if (depth > MAX_TREE_DEPTH || budget[0] <= 0) return null
        budget[0]--

        if (node.isFocused && isEditableTextField(node)) {
            return node
        }

        for (i in 0 until node.childCount) {
            if (budget[0] <= 0) break
            val child = node.getChild(i) ?: continue
            val result = findFocusedEditableNode(child, depth + 1, budget)
            if (result != null) {
                if (result !== child) {
                    child.recycle()
                }
                return result
            }
            child.recycle()
        }

        return null
    }

    // ────────────────────────────────────────────────────────────────────
    //  Node Classification Helpers
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns true if the node represents an editable text field.
     *
     * We check multiple signals because different UI toolkits report
     * editability differently:
     * - Native Android views: [isEditable] is true
     * - WebViews (Brave/Chrome): className may be "android.widget.EditText"
     *   even when [isEditable] is false
     * - Jetpack Compose: className is often "android.view.View" but
     *   [isEditable] is true
     */
    private fun isEditableTextField(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true

        val className = node.className?.toString() ?: ""

        // WebView often wraps inputs as EditText class even without isEditable
        if (className.contains("EditText", ignoreCase = true)) return true

        // Some WebView implementations expose contenteditable divs with
        // ACTION_SET_TEXT available but not isEditable
        val actions = node.actionList
        if (actions != null) {
            for (action in actions) {
                if (action.id == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Returns true if the node is a secure/password field where we should
     * NOT show the voice dictation bubble.
     */
    private fun isSecureField(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true

        val className = node.className?.toString()?.lowercase() ?: ""
        return className.contains("password") ||
                className.contains("pin") ||
                className.contains("lock")
    }

    // ────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ────────────────────────────────────────────────────────────────────

    override fun onInterrupt() {
        cancelPendingEvaluation()
        BubbleController.stopService(this)
    }

    override fun onDestroy() {
        cancelPendingEvaluation()
        val sharedPrefs = getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        BubbleController.stopService(this)
        super.onDestroy()
    }
}
