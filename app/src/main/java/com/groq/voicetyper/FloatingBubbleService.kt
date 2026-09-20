package com.groq.voicetyper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.Choreographer
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import com.groq.voicetyper.agent.AgentPreferences
import kotlin.math.roundToInt

class FloatingBubbleService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    private val store by lazy { ViewModelStore() }
    private val savedStateRegistryController by lazy { SavedStateRegistryController.create(this) }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var isViewAdded = false
    private var isAnchoredRight = true

    // Interaction (touch) window: transparent overlay sized by its own Compose
    // content, positioned identically to the visual window. The visual window is
    // FLAG_NOT_TOUCHABLE, so every touch routes through this window.
    private var interactionView: ComposeView? = null
    private var interactionLayoutParams: WindowManager.LayoutParams? = null

    // Drag clamp constants shared by the visual and interaction windows.
    private var paddingPx = 0
    private var collapsedSizePx = 0

    // One-turn agent dropdown: its own overlay window, fully independent of
    // the pill. The pill layout, animations, and gestures are never touched.
    private var agentDropdownView: ComposeView? = null
    private var dropdownHeightJob: kotlinx.coroutines.Job? = null
    private val dropdownCollapseSignal = androidx.compose.runtime.mutableStateOf(0)

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Observe bubble visibility from BubbleController
        scope.launch {
            BubbleController.isBubbleVisible.collect { visible ->
                if (visible) {
                    addOverlayView()
                } else {
                    removeOverlayView()
                }
            }
        }

        // Observe recording state to dynamically manage FLAG_KEEP_SCREEN_ON
        scope.launch {
            BubbleController.recordingState.collect { state ->
                updateScreenOnFlag(state == RecordingState.RECORDING)
            }
        }

        // One-turn agent picker: visible only while the agent pill is expanded
        // and recording with at least one custom agent present. Untouched by
        // default, the settings default applies.
        scope.launch {
            combine(
                BubbleController.isBubbleExpanded,
                TranscriptionSessionManager.isAgentMode,
                BubbleController.recordingState
            ) { expanded, agentMode, state -> Triple(expanded, agentMode, state) }
                .collect { (expanded, agentMode, state) ->
                    updateAgentDropdown(
                        expanded && agentMode && state == RecordingState.RECORDING
                    )
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY restarts the service with a null intent after the process was
        // killed. The bubble's in-memory state is gone, so there is nothing to show;
        // running a headless foreground service with a misleading MICROPHONE-type
        // notification and no overlay would be wrong — stop immediately instead.
        if (intent == null && !BubbleController.isBubbleVisible.value) {
            stopSelf()
            return START_NOT_STICKY
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        startForegroundServiceNotification()

        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "fluence_bubble_service"
        val channelName = "Fluence Bubble Service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("Fluence On-Screen Dictation")
            .setContentText("Pill overlay is active. Tap fields to record.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            BubbleController.hideBubble()
            stopSelf()
        }
    }

    private fun addOverlayView() {
        if (isViewAdded) {
            if (!Settings.canDrawOverlays(this)) {
                Log.w(TAG, "Overlay permission revoked mid-session — stopping bubble")
                BubbleController.hideBubble()
                stopSelf()
            }
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing — cannot show bubble")
            BubbleController.hideBubble()
            return
        }

        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val collapsedSize = (56 * density).toInt()
        paddingPx = padding
        collapsedSizePx = collapsedSize

        layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            format = PixelFormat.TRANSLUCENT
            alpha = 1f
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            // The visual window never receives input; the interaction window owns it.
            this.flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.TOP or if (lastIsAnchoredRight) Gravity.END else Gravity.START
            x = if (lastIsAnchoredRight) -padding else (lastX ?: -padding)
            y = lastY ?: (resources.displayMetrics.heightPixels / 3 - padding)

        }

        isAnchoredRight = lastIsAnchoredRight
        BubbleController.updateAnchoredRight(lastIsAnchoredRight)

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            // Use hardware acceleration for 60fps fluidity
            setViewTreeLifecycleOwner(this@FloatingBubbleService)
            setViewTreeViewModelStoreOwner(this@FloatingBubbleService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBubbleService)

            setContent {
                FloatingBubbleUI(
                    isAnchoredRight = isAnchoredRight,
                    onDrag = { dx, dy -> handleDrag(dx, dy) },
                    onDragReleased = { handleDragReleased() },
                    onWidthUpdated = { _ ->
                        // WindowManager native gravity (Gravity.START or Gravity.END) keeps the anchored
                        // edge fixed automatically while Compose resizes. No per-frame updateViewLayout required!
                    }
                )
            }
        }

        composeView = view
        try {
            FluenceAccessibilityService.addBubbleVisualOverlay(view, layoutParams)
            isViewAdded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            composeView = null
            BubbleController.hideBubble()
            return


        }

        // Interaction window: transparent overlay hosting the touch replica,
        // added after the visual window so it sits on top and receives every
        // touch (verified in InputDispatcher: topmost-first dispatch). Its size
        // is driven by its own Compose content (88x88dp collapsed, 272x96dp
        // expanded — instant on state transitions, never per-frame). Its position
        // mirrors the visual window's x/y/gravity; both windows share the same
        // frame in each state, so the visual window below never receives touches.
        val interactionLp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = layoutParams.gravity
            x = layoutParams.x
            y = layoutParams.y
        }
        interactionLayoutParams = interactionLp
        val touchView = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setViewTreeLifecycleOwner(this@FloatingBubbleService)
            setViewTreeViewModelStoreOwner(this@FloatingBubbleService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBubbleService)
            // The touch layer is semantically empty so the visual window remains
            // the single accessibility surface (TalkBack actions still reach the
            // visual window's nodes even though it is not touchable).
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO)
            setContent {
                BubbleTouchLayer(
                    onDrag = { dx, dy -> handleDrag(dx, dy) },
                    onDragReleased = { handleDragReleased() }
                )
            }
        }
        interactionView = touchView
        try {
            windowManager.addView(touchView, interactionLp)
        } catch (e: Exception) {
            // Without the interaction window the bubble would be untouchable.
            Log.e(TAG, "Failed to add interaction overlay view", e)
            interactionView = null
            interactionLayoutParams = null
            if (view.isAttachedToWindow) {
                FluenceAccessibilityService.removeBubbleVisualOverlay(view)
            }
            composeView = null
            isViewAdded = false
            BubbleController.hideBubble()
        }
    }

    /**
     * Shared drag handler for the visual and interaction windows. The visual
     * window's position is the single source of truth; the interaction window
     * mirrors it.
     */
    private fun handleDrag(dx: Float, dy: Float) {
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val lp = layoutParams
        // Keep the resting gravity for the entire drag; the flip happens only at
        // snap completion. Under Gravity.END the x inset is from the right edge,
        // so a left-based finger delta is subtracted; under Gravity.START it is added.
        if (isAnchoredRight) {
            lp.x = (lp.x - dx.toInt()).coerceIn(-paddingPx, screenWidth - collapsedSizePx - paddingPx)
        } else {
            lp.x = (lp.x + dx.toInt()).coerceIn(-paddingPx, screenWidth - collapsedSizePx - paddingPx)
        }
        lp.y = (lp.y + dy.toInt()).coerceIn(-paddingPx, screenHeight - collapsedSizePx - paddingPx)
        lastX = lp.x
        lastY = lp.y
        if (isViewAdded && composeView != null && composeView!!.isAttachedToWindow) {
            FluenceAccessibilityService.updateBubbleVisualOverlay(composeView!!, lp)
        }
        mirrorPositionToInteraction()
    }

    private fun handleDragReleased() {
        val screenWidth = resources.displayMetrics.widthPixels
        val lp = layoutParams
        // No mid-drag flip anymore: the current gravity is END iff isAnchoredRight.
        val currentlyEnd = isAnchoredRight
        val bubbleLeft = if (currentlyEnd) {
            // END gravity: x is inset from the right; bubble sits `padding` from window-right.
            screenWidth - lp.x - paddingPx - collapsedSizePx
        } else {
            lp.x + paddingPx
        }
        val isLeft = bubbleLeft + collapsedSizePx / 2 < screenWidth / 2
        val finalAnchorRight = !isLeft
        // Snap target expressed in the CURRENT gravity's coordinate space.
        val targetX = if (currentlyEnd) {
            if (finalAnchorRight) -paddingPx else screenWidth - collapsedSizePx - paddingPx
        } else {
            if (finalAnchorRight) screenWidth - collapsedSizePx - paddingPx else -paddingPx
        }
        animateSnap(targetX, finalAnchorRight, currentlyEnd)
    }

    /**
     * Copies the visual window's position to the interaction window. Called on
     * every drag frame, every snap frame, the snap-end gravity flip, and mount.
     */
    private fun mirrorPositionToInteraction() {
        val view = interactionView ?: return
        if (!view.isAttachedToWindow) return
        val lp2 = interactionLayoutParams ?: return
        lp2.x = layoutParams.x
        lp2.y = layoutParams.y
        lp2.gravity = layoutParams.gravity
        // Match the visual window: the snap-end flip must teleport, not glide.
        if (Build.VERSION.SDK_INT >= 34) {
            lp2.setCanPlayMoveAnimation(false)
        }
        windowManager.updateViewLayout(view, lp2)
    }

    /**
     * Shows or hides the one-turn agent dropdown. The card is positioned from
     * the pill frame: below the pill when the pill sits in the top half,
     * above it otherwise, x clamped to the screen. Corners resolve by the
     * same rule, no per-corner code. Pill views are never touched.
     */
    private fun updateAgentDropdown(shouldShow: Boolean) {
        if (!shouldShow) {
            hideAgentDropdown()
            return
        }
        val customs = try {
            AgentPreferences.loadCustomAgents(this)
        } catch (_: Exception) {
            emptyList()
        }
        if (customs.isEmpty()) {
            hideAgentDropdown()
            return
        }
        if (agentDropdownView?.isAttachedToWindow == true) return
        if (!Settings.canDrawOverlays(this)) return

        val metrics = resources.displayMetrics
        val density = metrics.density
        val screenW = metrics.widthPixels.toFloat()
        val screenH = metrics.heightPixels.toFloat()
        // True expanded pill frame: 240x64dp content inside the 16dp
        // padded visual window, matching FloatingBubbleUI exactly.
        val pillW = 240f * density
        val pillH = 64f * density
        val pillLeft = if (isAnchoredRight) {
            screenW - layoutParams.x.toFloat() - paddingPx.toFloat() - pillW
        } else {
            layoutParams.x.toFloat() + paddingPx.toFloat()
        }
        val pillTop = layoutParams.y.toFloat() + paddingPx.toFloat()
        val below = pillTop + pillH / 2f < screenH / 2f

        // Strip matches the expanded pill width so it reads as part of it,
        // parked flush to the pill edge with a small gap. The card hangs off
        // the strip on the far side, never overlapping the pill. Pill in the
        // top half opens downward, lower half upward.
        val stripW = 240f * density
        val gap = 6f * density
        val cardAnimated = try {
            android.provider.Settings.Global.getFloat(
                contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        } catch (_: Exception) {
            true
        }
        // Clamp flush to the screen edges, not to the padding inset: the
        // pill itself rests at the raw edge (0 or screenW - stripW) under
        // FLAG_LAYOUT_NO_LIMITS, so reserving padding here shifted the strip
        // 16dp past the pill on the outer side. Live window frames confirmed
        // the 45px offset on a 450dpi screen.
        val minX = 0f
        val maxX = (screenW - stripW).coerceAtLeast(minX)
        val rawX = if (isAnchoredRight) pillLeft + pillW - stripW else pillLeft
        val winX = rawX.coerceIn(minX, maxX).roundToInt()
        // Gravity pins the window edge flush to the pill edge: top edge below
        // the pill when opening downward, bottom edge above the pill when
        // opening upward. Height changes then grow away from the pill.
        val winGravity: Int
        val winY: Int
        if (below) {
            winGravity = Gravity.TOP or Gravity.START
            winY = (pillTop + pillH + gap).roundToInt()
        } else {
            winGravity = Gravity.BOTTOM or Gravity.START
            winY = (screenH - (pillTop - gap)).roundToInt()
        }

        val defaultId = try {
            AgentPreferences.getDefaultAgentId(this)
        } catch (_: Exception) {
            AgentPreferences.ID_BUILT_IN
        }
        val activeId = TranscriptionSessionManager.activeAgentId ?: defaultId
        val agents = mutableListOf(
            DropdownAgent(
                id = AgentPreferences.ID_BUILT_IN,
                name = AgentPreferences.NAME_BUILT_IN,
                subtitle = "Multipurpose"
            )
        )
        for (c in customs) {
            agents.add(DropdownAgent(id = c.id, name = c.name, subtitle = c.hint))
        }

        // Wear the expanded pill's own theme, including day/night
        // auto-switch, so the card always matches the pill beside it.
        val pillName = try {
            if (FloatingBubblePreferences.isFollowSystem(this)) {
                val night = (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                FloatingBubblePreferences.getEffectivePillTheme(this, night)
            } else {
                FloatingBubblePreferences.getPillTheme(this)
            }
        } catch (_: Exception) {
            FloatingBubblePreferences.PILL_THEME_OBSIDIAN
        }
        val pillTheme = PillTheme.forName(pillName)

        val stripHeightPx = (44f * density).roundToInt()
        val expandedHeightPx = (350f * density).roundToInt()

        // Dynamic frame: initialized to strip height (44dp) so underlying screen
        // elements remain 100% touchable. Expands to 350dp only while the card is open.
        // Window move animations stay off, exactly like the pill windows: the
        // card content carries the only motion, so the system never plays a
        // competing move animation over the resize.
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            width = (240f * density).roundToInt()
            height = stripHeightPx
            gravity = winGravity
            x = winX
            y = winY
            if (Build.VERSION.SDK_INT >= 34) {
                setCanPlayMoveAnimation(false)
            }
        }

        dropdownCollapseSignal.value = 0
        val view = ComposeView(this).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setViewTreeLifecycleOwner(this@FloatingBubbleService)
            setViewTreeViewModelStoreOwner(this@FloatingBubbleService)
            setViewTreeSavedStateRegistryOwner(this@FloatingBubbleService)
            // Outside taps fold the card back to the strip without taking
            // the window down, so the strip stays available all turn.
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                    dropdownCollapseSignal.value = dropdownCollapseSignal.value + 1
                    true
                } else {
                    false
                }
            }
            setContent {
                AgentDropdownOverlay(
                    openBelow = below,
                    agents = agents,
                    initialActiveId = activeId,
                    defaultId = defaultId,
                    pillTheme = pillTheme,
                    collapse = dropdownCollapseSignal,
                    animated = cardAnimated,
                    onExpandedChange = { isExpanded ->
                        dropdownHeightJob?.cancel()
                        if (Build.VERSION.SDK_INT >= 34) {
                            lp.setCanPlayMoveAnimation(false)
                        }
                        if (isExpanded) {
                            lp.height = expandedHeightPx
                            try {
                                if (agentDropdownView?.isAttachedToWindow == true) {
                                    windowManager.updateViewLayout(agentDropdownView, lp)
                                }
                            } catch (_: Exception) {}
                        } else {
                            dropdownHeightJob = scope.launch {
                                if (cardAnimated) kotlinx.coroutines.delay(220)
                                lp.height = stripHeightPx
                                try {
                                    if (Build.VERSION.SDK_INT >= 34) {
                                        lp.setCanPlayMoveAnimation(false)
                                    }
                                    if (agentDropdownView?.isAttachedToWindow == true) {
                                        windowManager.updateViewLayout(agentDropdownView, lp)
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    },
                    onPick = { id ->
                        // No auto-collapse: the card stays open so the agent
                        // can change any number of times. The check moves at
                        // once. Confirm applies whatever is active; untouched
                        // means the settings default.
                        TranscriptionSessionManager.activeAgentId = id
                    }
                )
            }
        }
        try {
            windowManager.addView(view, lp)
            agentDropdownView = view
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add agent dropdown overlay", e)
            agentDropdownView = null
        }
    }

    private fun hideAgentDropdown() {
        dropdownHeightJob?.cancel()
        dropdownHeightJob = null
        agentDropdownView?.let {
            try {
                if (it.isAttachedToWindow) windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        agentDropdownView = null
    }

    private fun removeOverlayView() {
        hideAgentDropdown()
        interactionView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
            }
        }
        interactionView = null
        interactionLayoutParams = null
        if (!isViewAdded) return
        composeView?.let {
            if (it.isAttachedToWindow) {
                FluenceAccessibilityService.removeBubbleVisualOverlay(it)
            }
        }
        composeView = null
        isViewAdded = false
    }

    private fun updateScreenOnFlag(keepScreenOn: Boolean) {
        if (!isViewAdded || composeView == null || !composeView!!.isAttachedToWindow) return
        val lp = layoutParams
        val oldFlags = lp.flags
        if (keepScreenOn) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        if (lp.flags != oldFlags) {
            if (isViewAdded && composeView != null && composeView!!.isAttachedToWindow) {
                FluenceAccessibilityService.updateBubbleVisualOverlay(composeView!!, lp)
            }
        }
    }

    private var snapAnimator: android.animation.ValueAnimator? = null

    private fun animateSnap(targetX: Int, finalAnchorRight: Boolean, currentlyEnd: Boolean) {
        snapAnimator?.cancel()
        val startX = layoutParams.x
        val animator = android.animation.ValueAnimator.ofInt(startX, targetX)
        animator.duration = 350
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        var wasCancelled = false
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                wasCancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (wasCancelled || !isViewAdded || composeView?.isAttachedToWindow != true) return
                // Gravity/alignment flip happens only at snap completion, and only when the
                // final side differs from the gravity used during the drag. The bubble is
                // already at rest on the target edge, so the simultaneous origin-move and
                // alignment-flip is geometrically continuous.
                if (finalAnchorRight != currentlyEnd) {
                    val padding = (16 * resources.displayMetrics.density).toInt()
                    val view = composeView
                    if (view != null) {
                        // Alpha gate: hide the overlay for exactly one frame while WindowManager
                        // repositions the window origin and Compose applies the alignment flip.
                        // The Choreographer callback restores alpha on Frame N+1 before traversal.
                        view.alpha = 0f
                        BubbleController.updateAnchoredRight(finalAnchorRight)
                        layoutParams.gravity = Gravity.TOP or if (finalAnchorRight) Gravity.END else Gravity.START
                        layoutParams.x = -padding
                        lastX = layoutParams.x
                        if (Build.VERSION.SDK_INT >= 34) {
                            layoutParams.setCanPlayMoveAnimation(false)
                        }
                        if (isViewAdded && view.isAttachedToWindow) {
                            FluenceAccessibilityService.updateBubbleVisualOverlay(view, layoutParams)
                            mirrorPositionToInteraction()
                        }
                        Choreographer.getInstance().postFrameCallback {
                            if (view.alpha == 0f) {
                                view.alpha = 1f
                            }
                        }
                    }
                }
                isAnchoredRight = finalAnchorRight
                lastIsAnchoredRight = finalAnchorRight
            }
        })
        animator.addUpdateListener { animation ->
            val currX = animation.animatedValue as Int
            layoutParams.x = currX
            lastX = currX
            composeView?.let {
                if (isViewAdded && it.isAttachedToWindow) {
                    FluenceAccessibilityService.updateBubbleVisualOverlay(it, layoutParams)
                }
            }
            mirrorPositionToInteraction()
        }
        snapAnimator = animator
        animator.start()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        scope.cancel()
        removeOverlayView()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        BubbleController.onTrimMemory(level)
    }

    companion object {
        private const val TAG = "FloatingBubbleService"
        private const val NOTIFICATION_ID = 2026

        
        // Static variables to persist the bubble's coordinates and side anchoring across show/hide events
        private var lastX: Int? = null
        private var lastY: Int? = null
        private var lastIsAnchoredRight: Boolean = true
    }
}
