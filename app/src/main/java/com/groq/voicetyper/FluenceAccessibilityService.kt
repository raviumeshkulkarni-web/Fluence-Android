package com.groq.voicetyper

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
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
import com.groq.voicetyper.quickcontrols.FluenceQuickControls
import com.groq.voicetyper.quickcontrols.PaymentPauseNotifier

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
                val roots = mutableListOf<AccessibilityNodeInfo>()
                try {
                    val appWindows = windows
                        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    for (window in appWindows) {
                        window.root?.let { roots.add(it) }
                    }
                    if (roots.isEmpty()) return true
                    roots.any { it.packageName?.toString() == targetPackage }
                } finally {
                    roots.forEach {
                        try { it.recycle() } catch (_: Exception) {}
                    }
                    windows.forEach {
                        try { it.recycle() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed checking target visibility from windows", e)
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

        /**
         * Programmatically disables this AccessibilityService on Android 7+
         * (API 24+) so payment apps immediately unblock. If a recording is in
         * progress, stops and finalizes it first (zero data loss).
         */
        fun requestPauseForPayments(): Boolean {
            val service = activeInstance ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
            service.cancelPendingEvaluation()
            if (BubbleController.recordingState.value == RecordingState.RECORDING) {
                BubbleController.suppressForPrivacy()
            } else {
                BubbleController.stopService(service)
            }
            return try {
                service.disableSelf()
                true
            } catch (e: Exception) {
                Log.w(TAG, "disableSelf failed", e)
                false
            }
        }

        /**
         * Delivery/start guard that respects JVM unit tests and transient
         * service disconnection (mirrors [isInjectionTargetStillVisible]):
         * returns false whenever the target package is excluded OR any
         * currently visible application window belongs to an excluded package.
         */
        fun isForegroundFreeOfExcludedApps(targetPackage: String? = null): Boolean {
            val service = activeInstance ?: return true
            return service.isCurrentApplicationAllowedInternal(targetPackage)
        }

        /**
         * Pure decision: true if ANY visible application window belongs to an
         * excluded package (covers split-screen and payment bottom-sheets).
         */
        internal fun evaluateWindowExclusion(
            visibleAppPackages: List<String?>,
            isExcluded: (String?) -> Boolean
        ): Boolean = visibleAppPackages.any { !it.isNullOrBlank() && isExcluded(it) }

        /**
         * Resolves a node's effective package name by falling back to its
         * enclosing window/event package when virtual nodes (WebView, Compose,
         * React Native) report a null or blank packageName.
         */
        internal fun resolveEffectiveNodePackage(
            nodePackage: String?,
            fallbackPackage: String?
        ): String? = nodePackage?.trim()?.takeIf { it.isNotEmpty() }
            ?: fallbackPackage?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * Pure gate for TYPE_WINDOWS_CHANGED: avoids OS-wide Binder IPC churn
         * when IME-only mode is off and no bubble/session is active, while
         * still catching payment overlays and split-screen launches whenever
         * our bubble or an active session is live.
         */
        internal fun shouldEvaluateOnWindowsChanged(
            imeOnlyEnabled: Boolean,
            isBubbleVisible: Boolean,
            hasActiveBubbleSession: Boolean,
            recordingState: RecordingState
        ): Boolean = imeOnlyEnabled ||
            isBubbleVisible ||
            hasActiveBubbleSession ||
            recordingState != RecordingState.IDLE

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
                if (isFloatingBubbleEnabled) {
                    scheduleFullEvaluation()
                }
            } else if (key == PrivacyPreferences.KEY_EXCLUDED_PACKAGES) {
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

        val eventPackage = event.packageName?.toString()?.trim()?.takeIf { it.isNotEmpty() }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (PrivacyPreferences.isPackageExcluded(this, eventPackage)) {
                    cancelPendingEvaluation()
                    BubbleController.suppressForPrivacy()
                    return
                }
                val source = event.source
                if (source != null) {
                    try {
                        handleFocusChange(source, eventPackage)
                    } finally {
                        try { source.recycle() } catch (_: Exception) {}
                    }
                } else {
                    scheduleFullEvaluation()
                }
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (PrivacyPreferences.isPackageExcluded(this, eventPackage)) {
                    cancelPendingEvaluation()
                    BubbleController.suppressForPrivacy()
                    return
                }
                scheduleFullEvaluation()
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                scheduleFullEvaluation()
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (shouldEvaluateOnWindowsChanged(
                        imeOnlyEnabled = isImeOnlyEnabled,
                        isBubbleVisible = BubbleController.isBubbleVisible.value,
                        hasActiveBubbleSession = AutoLearnSessionManager.hasActiveBubbleSession(),
                        recordingState = BubbleController.recordingState.value
                    )
                ) {
                    scheduleFullEvaluation()
                }
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val source = event.source ?: return
                try {
                    if (feedBubbleAutoLearnObservation(source)) return
                    val effectivePkg = resolveEffectiveNodePackage(
                        source.packageName?.toString(),
                        eventPackage
                    )
                    if (PrivacyPreferences.isPackageExcluded(this, effectivePkg)) {
                        return
                    }
                    if (effectivePkg != null && isEditableTextField(source)) {
                        gatedShowBubble(source, effectivePkg)
                    }
                } finally {
                    try { source.recycle() } catch (_: Exception) {}
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
            // Stage-6 should-fix: do not leave the pill floating over the
            // field the revoke just proved secure - suppress the surface
            // too (the revoke inside suppression is then a no-op).
            BubbleController.suppressForPrivacy()
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
    private fun gatedShowBubble(node: AccessibilityNodeInfo, resolvedPackage: String? = null) {
        val effectivePkg = resolveEffectiveNodePackage(node.packageName?.toString(), resolvedPackage)
        if (PrivacyPreferences.isPackageExcluded(this, effectivePkg)) {
            BubbleController.suppressForPrivacy()
            return
        }
        if (effectivePkg.isNullOrBlank()) {
            return
        }
        if (isSecureField(node)) {
            BubbleController.hideBubble(CancelReason.PRIVACY_GUARD)
            return
        }
        val imeOnly = isImeOnlyEnabled
        val imeVisible = imeOnly && isImeVisible()
        if (!shouldShowBubble(true, imeOnly, imeVisible)) {
            if (shouldApplyImeHide(imeOnly, imeVisible, BubbleController.recordingState.value)) {
                BubbleController.hideBubble()
            }
            return
        }
        BubbleController.showBubble(this, node, effectivePkg)
    }

    /**
     * True while a soft-keyboard window exists. Scans the already-available
     * window list (no polling, no new permissions) and recycles every entry.
     */
    private fun isImeVisible(): Boolean {
        val infos = try {
            windows
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve accessibility windows", e)
            null
        } ?: return false
        var found = false
        for (info in infos) {
            try {
                if (info.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    found = true
                }
            } catch (e: Exception) {
                // Treat an unreadable entry as absent; keep scanning.
                Log.d(TAG, "Error inspecting window info", e)
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
    private fun handleFocusChange(node: AccessibilityNodeInfo, fallbackPackage: String? = null) {
        val effectivePkg = resolveEffectiveNodePackage(node.packageName?.toString(), fallbackPackage)
        if (PrivacyPreferences.isPackageExcluded(this, effectivePkg)) {
            cancelPendingEvaluation()
            BubbleController.suppressForPrivacy()
            return
        }
        if (hasExcludedVisibleApplicationWindow()) {
            cancelPendingEvaluation()
            BubbleController.suppressForPrivacy()
            return
        }

        if (effectivePkg != null && isEditableTextField(node)) {
            gatedShowBubble(node, effectivePkg)
            return
        }

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
        val windowList = try {
            windows
        } catch (e: Exception) {
            Log.w(TAG, "evaluateAllWindows failed retrieving windows", e)
            return
        }

        if (windowList == null) {
            evaluateLegacyRootFallback()
            return
        }

        try {
            val appWindows = windowList
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .sortedByDescending { (if (it.isFocused) 2 else 0) + (if (it.isActive) 1 else 0) }

            val windowPackages = ArrayList<String?>(appWindows.size)
            for (window in appWindows) {
                val root = try { window.root } catch (_: Exception) { null }
                val pkg = try {
                    root?.packageName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                } finally {
                    try { root?.recycle() } catch (_: Exception) {}
                }
                windowPackages.add(pkg)
            }

            // Exclusion always wins over focus, active recordings, and auto-learn:
            // if ANY visible application window belongs to an excluded package
            // (including split-screen or payment sheets), suppress immediately so
            // our overlay never triggers Android tapjacking protection.
            if (evaluateWindowExclusion(windowPackages) { PrivacyPreferences.isPackageExcluded(this, it) }) {
                BubbleController.suppressForPrivacy()
                return
            }

            // Attempt 1: Platform focus API on each application window (focused/active first).
            for (i in appWindows.indices) {
                val root = try { appWindows[i].root } catch (_: Exception) { null } ?: continue
                var focused: AccessibilityNodeInfo? = null
                try {
                    focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null && isEditableTextField(focused)) {
                        val effectivePkg = resolveEffectiveNodePackage(
                            focused.packageName?.toString(),
                            windowPackages[i]
                        )
                        if (PrivacyPreferences.isPackageExcluded(this, effectivePkg)) {
                            BubbleController.suppressForPrivacy()
                            return
                        }
                        if (effectivePkg != null) {
                            gatedShowBubble(focused, effectivePkg)
                            return
                        }
                    }
                } finally {
                    try { focused?.recycle() } catch (_: Exception) {}
                    try { root.recycle() } catch (_: Exception) {}
                }
            }

            // Attempt 2: Recursive tree walk for WebViews without FOCUS_INPUT.
            for (i in appWindows.indices) {
                val root = try { appWindows[i].root } catch (_: Exception) { null } ?: continue
                var found: AccessibilityNodeInfo? = null
                try {
                    val budget = intArrayOf(MAX_TRAVERSAL_NODES)
                    found = findFocusedEditableNode(root, 0, budget)
                    if (found != null) {
                        val effectivePkg = resolveEffectiveNodePackage(
                            found.packageName?.toString(),
                            windowPackages[i]
                        )
                        if (PrivacyPreferences.isPackageExcluded(this, effectivePkg)) {
                            BubbleController.suppressForPrivacy()
                            return
                        }
                        if (effectivePkg != null) {
                            gatedShowBubble(found, effectivePkg)
                            return
                        }
                    }
                } finally {
                    if (found != null && found !== root) {
                        try { found.recycle() } catch (_: Exception) {}
                    }
                    try { root.recycle() } catch (_: Exception) {}
                }
            }

            // Attempt 3: Legacy fallback with rootInActiveWindow.
            if (evaluateLegacyRootFallback()) {
                return
            }

            if (shouldSuppressGenericHide(BubbleController.recordingState.value) ||
                AutoLearnSessionManager.hasActiveBubbleSession()
            ) {
                return
            }
            BubbleController.hideBubble()
        } catch (e: Exception) {
            Log.w(TAG, "evaluateAllWindows failed", e)
        } finally {
            windowList.forEach {
                try { it.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun evaluateLegacyRootFallback(): Boolean {
        val legacyRoot = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
        var focused: AccessibilityNodeInfo? = null
        try {
            val rootPkg = legacyRoot.packageName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            if (PrivacyPreferences.isPackageExcluded(this, rootPkg)) {
                BubbleController.suppressForPrivacy()
                return true
            }
            focused = legacyRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && isEditableTextField(focused)) {
                val effectivePkg = resolveEffectiveNodePackage(
                    focused.packageName?.toString(),
                    rootPkg
                )
                if (PrivacyPreferences.isPackageExcluded(this, effectivePkg)) {
                    BubbleController.suppressForPrivacy()
                    return true
                }
                if (effectivePkg != null) {
                    gatedShowBubble(focused, effectivePkg)
                    return true
                }
            }
            return false
        } finally {
            try { focused?.recycle() } catch (_: Exception) {}
            try { legacyRoot.recycle() } catch (_: Exception) {}
        }
    }

    private fun hasExcludedVisibleApplicationWindow(): Boolean {
        val windowList = try {
            windows
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve accessibility windows", e)
            return false
        } ?: return false

        return try {
            val packages = mutableListOf<String?>()
            for (window in windowList) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = try { window.root } catch (_: Exception) { null }
                val pkg = try {
                    root?.packageName?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                } finally {
                    try { root?.recycle() } catch (_: Exception) {}
                }
                packages.add(pkg)
            }
            evaluateWindowExclusion(packages) { PrivacyPreferences.isPackageExcluded(this, it) }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to inspect visible application windows", e)
            false
        } finally {
            windowList.forEach {
                try { it.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun isCurrentApplicationAllowedInternal(targetPackage: String?): Boolean {
        if (PrivacyPreferences.isPackageExcluded(this, targetPackage)) return false
        if (hasExcludedVisibleApplicationWindow()) return false
        val activePackage = resolveActiveApplicationPackage() ?: return true
        return !PrivacyPreferences.isPackageExcluded(this, activePackage)
    }

    /**
     * Resolve the active application window package only when the platform gives
     * us a positive, unambiguous application-window identity. Unknown window
     * state is returned as null so privacy-sensitive callers can fail closed.
     */
    private fun resolveActiveApplicationPackage(): String? {
        val windowList = try {
            windows
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve accessibility windows", e)
            null
        } ?: return null

        return try {
            val appWindows = windowList
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

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
        } finally {
            windowList.forEach {
                try { it.recycle() } catch (_: Exception) {}
            }
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        PaymentPauseNotifier.dismiss(this)
        FluenceQuickControls.refreshAll(this, accessibilityEnabledOverride = true)
    }

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
        FluenceQuickControls.refreshAll(this, accessibilityEnabledOverride = false)
        super.onDestroy()
    }
}
