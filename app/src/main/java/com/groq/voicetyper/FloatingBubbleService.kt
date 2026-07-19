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
import android.view.Gravity
import android.view.WindowManager
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun addOverlayView() {
        if (isViewAdded) return

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
            gravity = Gravity.TOP or Gravity.START
            x = lastX ?: (resources.displayMetrics.widthPixels - collapsedSize - padding)
            y = lastY ?: (resources.displayMetrics.heightPixels / 3 - padding)
        }
        isAnchoredRight = lastIsAnchoredRight

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
                    onDrag = { dx, dy ->
                        val screenWidth = resources.displayMetrics.widthPixels
                        val screenHeight = resources.displayMetrics.heightPixels
                        val lp = this@FloatingBubbleService.layoutParams
                        lp.x = (lp.x + dx.toInt()).coerceIn(-padding, screenWidth - collapsedSize - padding)
                        lp.y = (lp.y + dy.toInt()).coerceIn(-padding, screenHeight - collapsedSize - padding)
                        lastX = lp.x
                        lastY = lp.y
                        if (isViewAdded && composeView != null && composeView!!.isAttachedToWindow) {
                            windowManager.updateViewLayout(composeView, lp)
                        }
                    },
                    onDragReleased = {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val lp = this@FloatingBubbleService.layoutParams
                        val isLeft = (lp.x + padding) + collapsedSize / 2 < screenWidth / 2
                        isAnchoredRight = !isLeft
                        lastIsAnchoredRight = isAnchoredRight
                        val targetX = if (isLeft) -padding else screenWidth - collapsedSize - padding
                        animateSnap(targetX)
                    },
                    onWidthUpdated = { widthDp ->
                        if (isAnchoredRight) {
                            val screenWidth = resources.displayMetrics.widthPixels
                            val currentWidthPx = (widthDp * density).toInt()
                            val lp = this@FloatingBubbleService.layoutParams
                            lp.x = screenWidth - currentWidthPx - padding
                            if (isViewAdded && composeView != null && composeView!!.isAttachedToWindow) {
                                windowManager.updateViewLayout(composeView, lp)
                            }
                        }
                    }
                )
            }
        }

        composeView = view
        windowManager.addView(view, layoutParams)
        isViewAdded = true
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

    private var snapAnimator: android.animation.ValueAnimator? = null

    private fun animateSnap(targetX: Int) {
        snapAnimator?.cancel()
        val startX = layoutParams.x
        val animator = android.animation.ValueAnimator.ofInt(startX, targetX)
        animator.duration = 350
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            val currX = animation.animatedValue as Int
            layoutParams.x = currX
            lastX = currX
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
        private const val NOTIFICATION_ID = 2026
        
        // Static variables to persist the bubble's coordinates and side anchoring across show/hide events
        private var lastX: Int? = null
        private var lastY: Int? = null
        private var lastIsAnchoredRight: Boolean = true
    }
}
