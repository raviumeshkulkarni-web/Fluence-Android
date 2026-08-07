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
import kotlinx.coroutines.launch
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
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
                    onDrag = { dx, dy ->
                        BubbleTrace.log("DRAG", "dx=$dx dy=$dy")
                        val screenWidth = resources.displayMetrics.widthPixels
                        val screenHeight = resources.displayMetrics.heightPixels
                        val lp = this@FloatingBubbleService.layoutParams
                        // Direction C prototype: do NOT flip gravity/alignment mid-drag.
                        // Keep the resting gravity for the entire drag so there is no
                        // simultaneous window-move + alignment-flip under the finger (the flash).
                        // Under Gravity.END, x is inset from the right edge, so a left-based
                        // finger delta must be subtracted; under Gravity.START it is added.
                        if (isAnchoredRight) {
                            lp.x = (lp.x - dx.toInt()).coerceIn(-padding, screenWidth - collapsedSize - padding)
                        } else {
                            lp.x = (lp.x + dx.toInt()).coerceIn(-padding, screenWidth - collapsedSize - padding)
                        }
                        lp.y = (lp.y + dy.toInt()).coerceIn(-padding, screenHeight - collapsedSize - padding)
                        lastX = lp.x
                        lastY = lp.y
                        if (isViewAdded && composeView != null && composeView!!.isAttachedToWindow) {
                            windowManager.updateViewLayout(composeView, lp)
                            BubbleTrace.log("UVL_DRAG", "g=${lp.gravity} x=${lp.x} y=${lp.y} aR=$isAnchoredRight")
                        }
                    },
                    onDragReleased = {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val lp = this@FloatingBubbleService.layoutParams
                        // No mid-drag flip anymore: the current gravity is END iff isAnchoredRight.
                        val currentlyEnd = isAnchoredRight
                        val bubbleLeft = if (currentlyEnd) {
                            // END gravity: x is inset from the right; bubble sits `padding` from window-right.
                            screenWidth - lp.x - padding - collapsedSize
                        } else {
                            lp.x + padding
                        }
                        val isLeft = bubbleLeft + collapsedSize / 2 < screenWidth / 2
                        val finalAnchorRight = !isLeft
                        // Snap target expressed in the CURRENT gravity's coordinate space.
                        val targetX = if (currentlyEnd) {
                            if (finalAnchorRight) -padding else screenWidth - collapsedSize - padding
                        } else {
                            if (finalAnchorRight) screenWidth - collapsedSize - padding else -padding
                        }
                        BubbleTrace.log("RELEASE", "curEnd=$currentlyEnd isLeft=$isLeft finalR=$finalAnchorRight targetX=$targetX")
                        animateSnap(targetX, finalAnchorRight, currentlyEnd)
                    },
                    onWidthUpdated = { _ ->
                        // WindowManager native gravity (Gravity.START or Gravity.END) keeps the anchored
                        // edge fixed automatically while Compose resizes. No per-frame updateViewLayout required!
                    }
                )
            }
        }

        composeView = view
        try {
            windowManager.addView(view, layoutParams)
            isViewAdded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            composeView = null
            BubbleController.hideBubble()
        }
    }

    private fun removeOverlayView() {
        if (!isViewAdded) return
        composeView?.let {
            if (it.isAttachedToWindow) {
                windowManager.removeView(it)
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
                windowManager.updateViewLayout(composeView, lp)
                BubbleTrace.log("UVL_FLAGS", "keepScreenOn=$keepScreenOn")
            }
        }
    }

    private var snapAnimator: android.animation.ValueAnimator? = null
    private var windowTraceFrames = 0

    private fun startWindowTrace(frames: Int = 140) {
        windowTraceFrames = frames
        if (frames > 0) {
            Choreographer.getInstance().postFrameCallback { frameNanos ->
                windowTraceFrame(frameNanos)
            }
        }
    }

    private fun windowTraceFrame(frameNanos: Long) {
        if (windowTraceFrames <= 0) return
        windowTraceFrames--
        composeView?.let { v ->
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            BubbleTrace.log("WINDOW_POS", "${loc[0]},${loc[1]}|frame=$frameNanos")
        }
        if (windowTraceFrames > 0) {
            Choreographer.getInstance().postFrameCallback { fn -> windowTraceFrame(fn) }
        }
    }

    private fun animateSnap(targetX: Int, finalAnchorRight: Boolean, currentlyEnd: Boolean) {
        snapAnimator?.cancel()
        val startX = layoutParams.x
        BubbleTrace.log("SNAP_START", "start=$startX target=$targetX finalR=$finalAnchorRight curEnd=$currentlyEnd")
        startWindowTrace()
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
                // Direction C prototype: the gravity/alignment transition happens ONLY here,
                // at snap completion, and only when the final side differs from the gravity we
                // dragged in. The bubble is already at rest on the target edge, so the
                // simultaneous window-move + alignment-flip is geometrically continuous
                // (proven: both representations place the bubble at the same screen edge).
                if (finalAnchorRight != currentlyEnd) {
                    val padding = (16 * resources.displayMetrics.density).toInt()
                    val view = composeView
                    if (view != null) {
                        // S4 atomicity prototype. The flash is a race between two subsystems:
                        // Compose applies the alignment flip (async recomposition) and WindowManager
                        // applies the ~184dp origin move (separate transaction). If the window moves
                        // first, the not-yet-realigned content shows the full offset ON-screen for one
                        // frame (the flash); if the content re-aligns first, the transient offset lands
                        // OFF-screen past the resting edge and is invisible.
                        //
                        // (1) Flip the Compose alignment now (schedules recomposition) and stage the
                        //     new window geometry.
                        BubbleTrace.log("FLIP_BEGIN", "finalR=$finalAnchorRight curEnd=$currentlyEnd")
                        BubbleController.updateAnchoredRight(finalAnchorRight)
                        layoutParams.gravity = Gravity.TOP or if (finalAnchorRight) Gravity.END else Gravity.START
                        layoutParams.x = -padding
                        lastX = layoutParams.x
                        BubbleTrace.log("FLIP_STAGED", "g=${layoutParams.gravity} x=${layoutParams.x}")
                        // (2) Apply the origin move as one instantaneous transaction
                        if (Build.VERSION.SDK_INT >= 34) {
                            layoutParams.setCanPlayMoveAnimation(false)
                            BubbleTrace.log("FLIP_NOMOVEANIM", "sdk=${Build.VERSION.SDK_INT}")
                        }
                        // (3) Defer the window move to the pre-draw of the RE-ALIGNED content so the
                        //     ordering is deterministically content-first and the seam stays off-screen.
                        BubbleTrace.log("FLIP_PREDRAW_ADD")
                        view.viewTreeObserver.addOnPreDrawListener(
                            object : android.view.ViewTreeObserver.OnPreDrawListener {
                                override fun onPreDraw(): Boolean {
                                    view.viewTreeObserver.removeOnPreDrawListener(this)
                                    if (isViewAdded && view.isAttachedToWindow) {
                                        BubbleTrace.log("FLIP_PREDRAW_EXEC", "attached=${view.isAttachedToWindow}")
                                        windowManager.updateViewLayout(view, layoutParams)
                                        BubbleTrace.log("UVL_POST", "g=${layoutParams.gravity} x=${layoutParams.x} y=${layoutParams.y}")
                                    }
                                    return true
                                }
                            }
                        )
                    }
                }
                isAnchoredRight = finalAnchorRight
                lastIsAnchoredRight = finalAnchorRight
                BubbleTrace.log("FLIP_DONE", "isAnchoredRight=$isAnchoredRight")
            }
        })
        animator.addUpdateListener { animation ->
            val currX = animation.animatedValue as Int
            layoutParams.x = currX
            lastX = currX
            BubbleTrace.log("SNAP_FRAME", "x=$currX")
            composeView?.let {
                if (isViewAdded && it.isAttachedToWindow) {
                    windowManager.updateViewLayout(it, layoutParams)
                }
            }
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
