package com.groq.voicetyper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.groq.voicetyper.autolearn.domain.AutoLearnFieldSnapshot
import com.groq.voicetyper.autolearn.domain.AutoLearnSessionManager
import com.groq.voicetyper.autolearn.domain.AutoLearnSurface

/**
 * Anchor field identity (review must-fix 13): window+package alone admits
 * any sibling editable of the same app, so the observation feed also
 * requires identical on-screen bounds whenever the platform exposes both
 * sides.
 */
data class AutoLearnAnchorBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

class FluenceAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FluenceA11y"

        @Volatile
        private var activeInstance: FluenceAccessibilityService? = null

        fun addBubbleVisualOverlay(view: View, layoutParams: WindowManager.LayoutParams) {
            val service = activeInstance
                ?: throw IllegalStateException("Accessibility service is not connected")
            service.accessibilityWindowManager?.addView(view, layoutParams)
                ?: throw IllegalStateException("Accessibility window manager is unavailable")
        }

        fun updateBubbleVisualOverlay(view: View, layoutParams: WindowManager.LayoutParams) {
            activeInstance?.accessibilityWindowManager?.updateViewLayout(view, layoutParams)
        }

        fun removeBubbleVisualOverlay(view: View) {
            activeInstance?.accessibilityWindowManager?.removeView(view)
        }

        /**
         * Pure visibility-gate decisions for the opt-in "show only when
         * keyboard is visible" mode. Unit-tested; the service applies them
         * in handleFocusChange/evaluateAllWindows.
         *
         * shouldShowBubble: the existing editable-focus requirement ANDed
         * with IME visibility when the mode is on. When off, reduces to the
         * focus requirement exactly (existing behavior, unchanged).
         *
         * shouldApplyImeHide: the IME gate may only hide while IDLE — never
         * while RECORDING, TRANSCRIBING (or surfacing an ERROR), so a
         * keyboard dismissal can never cancel an in-flight session.
         */
        fun shouldShowBubble(editableFocused: Boolean, imeOnly: Boolean, imeVisible: Boolean): Boolean =
            editableFocused && (!imeOnly || imeVisible)

        fun shouldApplyImeHide(imeOnly: Boolean, imeVisible: Boolean, recordingState: RecordingState): Boolean =
            imeOnly && !imeVisible && recordingState == RecordingState.IDLE

        /**
         * A no-privacy, no-lifecycle-significance hide (the no-focused-editable
         * branch of evaluateAllWindows) must never tear down an in-flight
         * session: the pill, the focused node and the foreground service form
         * one coupled unit until the transcript is delivered. Pure; unit-tested
         * alongside the other bubble gates.
         */
        fun shouldSuppressGenericHide(recordingState: RecordingState): Boolean =
            recordingState == RecordingState.RECORDING || recordingState == RecordingState.TRANSCRIBING

        /**
         * Pure anchor-match decision for the auto-learn observation feed
         * (unit-tested): an accessibility text event may feed the bubble
         * surface's session only when its source IS the field the bubble is
         * anchored to (same window AND same package). Everything else keeps
         * the legacy event handling - foreign-window text churn must never
         * revoke a session it cannot see (Windows closes the session whose
         * field it observes, never another one).
         */
        fun isAutoLearnAnchorFeed(
            sourceWindowId: Int?,
            sourcePackage: String?,
            anchorWindowId: Int?,
            anchorPackage: String?,
            sourceBounds: AutoLearnAnchorBounds? = null,
            anchorBounds: AutoLearnAnchorBounds? = null
        ): Boolean {
            if (!(sourceWindowId != null && anchorWindowId != null &&
                    sourceWindowId == anchorWindowId &&
                    sourcePackage != null && sourcePackage == anchorPackage)
            ) {
                return false
            }
            // Node identity (review must-fix 13): when both sides expose
            // bounds, the field itself must be the same - a sibling editable
            // shares window/package but never bounds. An unavailable bound
            // keeps the legacy window+package identity.
            if (sourceBounds != null && anchorBounds != null) {
                return sourceBounds == anchorBounds
            }
            return true
        }

        /**
         * True when a node must never receive dictation output. Mirrors the
         * secure-field detection used for bubble suppression so the bubble
         * can never be visible over a field it would be forbidden to write
         * into. Pure; unit-tested alongside the other bubble gates.
         */
        fun isSecureInjectionTarget(node: AccessibilityNodeInfo): Boolean {
            if (node.isPassword) return true
            val className = node.className?.toString()?.lowercase() ?: ""
            return className.contains("password") ||
                className.contains("pin") ||
                className.contains("lock")
        }

        /**
         * Honors the long-dead verifyActiveWindow contract at delivery time:
         * injection may only target a package whose application window is
         * actually on screen - a node from a closed window is stale and must
         * not be written into. When the service is disconnected or the window
         * list is unavailable (or genuinely contains no application window,
         * as during transient system transitions) the legacy permissive
         * behavior applies rather than failing delivery.
         */
        fun isInjectionTargetStillVisible(targetPackage: String?): Boolean {
            if (targetPackage == null) return true
            val service = activeInstance ?: return true
            return try {
                val windows = service.windows ?: return true
                val appWindows = windows
                    .filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
                val roots = appWindows.mapNotNull { it.root }
                if (roots.isEmpty()) return true
                roots.any { it.packageName?.toString() == targetPackage }
            } catch (_: Exception) {
                true
            }
        }

        /**
         * Bubble-only foreground check. The IME deliberately does not use this
         * source; it uses its current EditorInfo package instead.
         */
        fun isCurrentApplicationAllowed(targetPackage: String? = null): Boolean {
            return activeInstance?.isCurrentApplicationAllowedInternal(targetPackage) ?: false
        }

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
    // Opt-in "show only when keyboard is visible" mode. Cached like the
    // master switch; when false every gate below is a no-op and behavior is
    // exactly today's focus-based visibility.
    private var isImeOnlyEnabled = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingEvaluation: Runnable? = null
    private var accessibilityWindowManager: WindowManager? = null
    private val evalScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
    )

    private val prefListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == FloatingBubblePreferences.KEY_BUBBLE_ENABLED) {
                isFloatingBubbleEnabled = prefs.getBoolean(key, false)
                if (!isFloatingBubbleEnabled) {
                    cancelPendingEvaluation()
                    BubbleController.stopService(this)
                }
            } else if (key == FloatingBubblePreferences.KEY_BUBBLE_IME_ONLY) {
                isImeOnlyEnabled = prefs.getBoolean(key, false)
                // Converge immediately on toggle: a visible bubble with no
                // keyboard must hide (if idle), a hidden one with an open
                // keyboard may show. Debounced like every other evaluation.
                if (isFloatingBubbleEnabled) {
                    scheduleFullEvaluation()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        accessibilityWindowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        activeInstance = this
        val sharedPrefs = getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
        isFloatingBubbleEnabled = sharedPrefs.getBoolean(FloatingBubblePreferences.KEY_BUBBLE_ENABLED, false)
        isImeOnlyEnabled = sharedPrefs.getBoolean(FloatingBubblePreferences.KEY_BUBBLE_IME_ONLY, false)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefListener)
        // After a session ends, the keyboard/focus picture may have changed
        // underneath it (e.g. keyboard dismissed mid-recording, which must
        // never interrupt the session). Re-evaluate once back at IDLE so the
        // pill converges in BOTH modes: hide when the field the session
        // targeted is gone, show when a fresh editable has focus. drop(1)
        // skips the initial IDLE emission.
        evalScope.launch {
            BubbleController.recordingState
                .drop(1)
                .collect { state ->
                    if (state == RecordingState.IDLE && isFloatingBubbleEnabled) {
                        scheduleFullEvaluation()
                    }
                }
        }
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

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // Window added/removed/bounds changed — this is how IME
                // (keyboard) show/hide reaches us. Only the opt-in IME mode
                // needs it; when off, ignore to keep behavior identical.
                // Debounced: keyboard switches produce a remove+add pair, and
                // this event fires for every system window transition.
                if (isImeOnlyEnabled) {
                    scheduleFullEvaluation()
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Text changed in a view — the user is typing or voice text
                // was injected. While a bubble observation is live, this IS
                // the per-update feed: full-field text plus the CURRENT
                // snapshot flow to the session manager (per-update preamble
                // revokes first on positive security evidence). Other events
                // keep the legacy show-refresh, now through the SAME gates
                // as focus events - the old direct showBubble here skipped
                // the secure-field and excluded-package checks entirely
                // (review gap).
                val source = event.source ?: return
                if (feedBubbleAutoLearnObservation(source)) return
                if (PrivacyPreferences.isPackageExcluded(this, source.packageName?.toString())) {
                    // Review fix 14: background churn in an excluded app is
                    // foreign to any live session (the feed's per-update
                    // preamble already revokes the session's OWN target when
                    // that target turns excluded). Suppression is a field-
                    // switch decision - it belongs to focus events and show
                    // gates, not to text churn.
                    return
                }
                if (isEditableTextField(source)) {
                    gatedShowBubble(source)
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    //  Focus Evaluation
    // ────────────────────────────────────────────────────────────────────

    /**
     * Auto-learn observation feed for a live bubble session (Windows
     * monitor per-read parity): the full field text plus the CURRENT field
     * snapshot flow into the session manager, whose per-update preamble
     * revokes first on positive security evidence (password / excluded /
     * foreign package) before any diff can flush. Returns false when the
     * event's source is not the field the bubble is anchored to.
     */
    private fun feedBubbleAutoLearnObservation(source: AccessibilityNodeInfo): Boolean {
        if (!BubbleController.isAutoLearnAnchor(source)) return false
        if (isSecureInjectionTarget(source)) {
            // Belt-and-braces beyond node.isPassword (review fix 16): the
            // class-hint read the per-update preamble cannot see exits the
            // session the same way the show path already does.
            AutoLearnSessionManager.revokeAndEndForSecureField()
            return true
        }
        val snapshot = AutoLearnFieldSnapshot.fromAccessibilityNode(source)
        AutoLearnSessionManager.onTextUpdated(
            source.text?.toString().orEmpty(),
            this,
            snapshot,
            origin = AutoLearnSurface.BUBBLE,
            windowId = source.windowId.toLong()
        )
        return true
    }

    /**
     * Single choke point for "focus says show". Applies the opt-in IME gate:
     * editable focus AND keyboard visible when the mode is on. When the
     * keyboard is absent the bubble never appears, and an already-visible
     * bubble converges to hidden — but only while IDLE, so an in-flight
     * recording/transcription is never interrupted. When the mode is off,
     * behaves exactly like the code it replaces. Callers retain node
     * ownership (showBubble copies via obtain), as before.
     */
    private fun gatedShowBubble(node: AccessibilityNodeInfo) {
        // Short-circuit: skip the window scan entirely when the mode is off.
        val imeOnly = isImeOnlyEnabled
        val imeVisible = imeOnly && isImeVisible()
        if (!shouldShowBubble(true, imeOnly, imeVisible)) {
            if (shouldApplyImeHide(imeOnly, imeVisible, BubbleController.recordingState.value)) {
                BubbleController.hideBubble()
            }
            return
        }
        if (isSecureField(node)) {
            BubbleController.hideBubble(CancelReason.PRIVACY_GUARD)
        } else {
            BubbleController.showBubble(this, node)
        }
    }

    /**
     * True while a soft-keyboard window exists. Scans the already-available
     * window list (no polling, no new permissions) and recycles every entry.
     */
    private fun isImeVisible(): Boolean {
        val infos = try {
            windows
        } catch (_: Exception) {
            null
        } ?: return false
        var found = false
        for (info in infos) {
            try {
                if (info.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    found = true
                }
            } catch (_: Exception) {
                // Treat an unreadable entry as absent; keep scanning.
            } finally {
                try {
                    info.recycle()
                } catch (_: Exception) {
                }
            }
        }
        return found
    }

    /**
     * Called when we have a direct [node] reference from an event source.
     * We first check the node itself; if it's not an editable field, we fall
     * back to scanning all application windows.
     */
    private fun handleFocusChange(node: AccessibilityNodeInfo) {
        if (PrivacyPreferences.isPackageExcluded(this, node.packageName?.toString())) {
            BubbleController.suppressForPrivacy()
            return
        }

        if (isEditableTextField(node)) {
            gatedShowBubble(node)
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
        if (suppressIfRequired(null)) return

        try {
            val appWindows = windows
                ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?: emptyList()

            // Attempt 1: Use the platform focus API on each application window.
            for (window in appWindows) {
                val root = window.root ?: continue
                val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (focused != null && isEditableTextField(focused)) {
                    gatedShowBubble(focused)
                    focused.recycle()
                    root.recycle()
                    return
                }
                focused?.recycle()
                root.recycle()
            }

            // Attempt 2: Recursive tree walk. Some WebView implementations
            // don't set FOCUS_INPUT but still report `isFocused()` on the node.
            for (window in appWindows) {
                val root = window.root ?: continue
                // A fresh budget per window so a large first window cannot starve
                // the remaining windows (split-screen / multi-window) out of search.
                val budget = intArrayOf(MAX_TRAVERSAL_NODES)
                val found = findFocusedEditableNode(root, 0, budget)
                if (found != null) {
                    if (found !== root) {
                        root.recycle()
                    }
                    gatedShowBubble(found)
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
                    gatedShowBubble(focused)
                    focused.recycle()
                    legacyRoot.recycle()
                    return
                }
                focused?.recycle()
                legacyRoot.recycle()
            }

            // No focused editable field found anywhere. While a session is in
            // flight, the pill + focused node + foreground service form one
            // coupled unit; generic focus churn (Home swipe, app switch,
            // keyboard dismissal) must never tear it down mid-session - the
            // IDLE collector re-evaluates after the session ends.
            if (shouldSuppressGenericHide(BubbleController.recordingState.value)) {
                return
            }
            BubbleController.hideBubble()

        } catch (e: Exception) {
            Log.w(TAG, "evaluateAllWindows failed", e)
            // Don't hide on transient errors — keep current state.
        }
    }

    private fun suppressIfRequired(event: AccessibilityEvent?): Boolean {
        val eventPackage = event?.packageName?.toString()
        if (PrivacyPreferences.isPackageExcluded(this, eventPackage)) {
            cancelPendingEvaluation()
            BubbleController.suppressForPrivacy()
            return true
        }
        return false
    }

    private fun isCurrentApplicationAllowedInternal(targetPackage: String?): Boolean {
        if (PrivacyPreferences.isPackageExcluded(this, targetPackage)) return false
        val activePackage = resolveActiveApplicationPackage() ?: return true
        return !PrivacyPreferences.isPackageExcluded(this, activePackage)
    }

    /**
     * Resolve the active application window package only when the platform gives
     * us a positive, unambiguous application-window identity. Unknown window
     * state is returned as null so privacy-sensitive callers can fail closed.
     */
    private fun resolveActiveApplicationPackage(): String? {
        return try {
            val appWindows = windows
                ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .orEmpty()

            val focusedWindows = appWindows.filter { it.isFocused }
            val candidateWindows = if (focusedWindows.isNotEmpty()) {
                focusedWindows
            } else {
                appWindows.filter { it.isActive }
            }
            if (candidateWindows.isEmpty()) return null

            val packageNames = candidateWindows.map { window ->
                val root = window.root
                try {
                    root?.packageName?.toString()?.takeIf { it.isNotBlank() }
                } finally {
                    root?.recycle()
                }
            }

            if (packageNames.any { it.isNullOrBlank() }) return null
            packageNames.filterNotNull().distinct().singleOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to resolve active application package", e)
            null
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
        return isSecureInjectionTarget(node)
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
        evalScope.cancel()
        if (activeInstance === this) {
            activeInstance = null
        }
        accessibilityWindowManager = null
        val sharedPrefs = getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        BubbleController.stopService(this)
        super.onDestroy()
    }
}
