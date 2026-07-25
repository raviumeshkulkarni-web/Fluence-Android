package com.groq.voicetyper

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.composed
import kotlin.math.sin

@Composable
fun FloatingBubbleUI(
    isAnchoredRight: Boolean,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragReleased: () -> Unit,
    onWidthUpdated: (Float) -> Unit
) {
    val context = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val isExpanded by BubbleController.isBubbleExpanded.collectAsState()
    val recordingState by BubbleController.recordingState.collectAsState()
    val errorMessage by BubbleController.errorMessage.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Size animations for morphing transition.
    // tween(250ms) provides ~15 frames of smooth, perceivable animation.
    // FastOutSlowInEasing is Material Design's standard "elements moving into place" curve.
    val animSpec = tween<Dp>(durationMillis = 250, easing = FastOutSlowInEasing)
    val width by animateDpAsState(
        targetValue = if (isExpanded) 240.dp else 56.dp,
        animationSpec = animSpec,
        label = "width"
    )

    // Only notify the Service when width actually changes.
    // SideEffect fires on EVERY recomposition (including 60fps waveform frames)
    // which hammered updateViewLayout unnecessarily, causing right-side stutter.
    LaunchedEffect(Unit) {
        snapshotFlow { width.value }
            .distinctUntilChanged()
            .collect { widthDp -> onWidthUpdated(widthDp) }
    }
    val height by animateDpAsState(
        targetValue = if (isExpanded) 64.dp else 56.dp,
        animationSpec = animSpec,
        label = "height"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (isExpanded) 32.dp else 28.dp,
        label = "cornerRadius"
    )

    val shape = RoundedCornerShape(cornerRadius)

    Box(
        modifier = Modifier.padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = width, height = height)
                .amethystObsidianGlow(isExpanded = isExpanded, shape = shape)
                .clip(shape)
                // Gesture handling for Collapsed state (drag, instant tap, hold for agent mode)
                .run {
                    if (!isExpanded) {
                        this.pointerInput(isExpanded) {
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown()
                                    val startPos = down.position
                                    var isDragging = false
                                    var isLongPressTriggered = false

                                    val longPressJob = coroutineScope.launch {
                                        kotlinx.coroutines.delay(500)
                                        if (!isDragging && (recordingState == RecordingState.IDLE || recordingState == RecordingState.ERROR)) {
                                            isLongPressTriggered = true
                                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                            BubbleController.startRecording(context, agentMode = true)
                                        }
                                    }

                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: continue

                                        if (change.pressed) {
                                            val currentPos = change.position
                                            val dragDistance = (currentPos - startPos).getDistance()

                                            if (dragDistance > 8.dp.toPx()) {
                                                isDragging = true
                                                longPressJob.cancel()
                                            }

                                            if (isDragging) {
                                                val dx = change.position.x - change.previousPosition.x
                                                val dy = change.position.y - change.previousPosition.y
                                                onDrag(dx, dy)
                                            }
                                            change.consume()
                                        } else {
                                            break
                                        }
                                    } while (true)

                                    longPressJob.cancel()

                                    if (isDragging) {
                                        onDragReleased()
                                    } else {
                                        if (!isLongPressTriggered) {
                                            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                            if (recordingState == RecordingState.RECORDING) {
                                                BubbleController.stopRecording(context)
                                            } else if (recordingState == RecordingState.IDLE || recordingState == RecordingState.ERROR) {
                                                BubbleController.startRecording(context, agentMode = false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else this
                },
            contentAlignment = Alignment.Center
        ) {
            // Crossfade provides a smooth alpha-blended transition between the
            // collapsed logo and the expanded pill content. Without this, the
            // content swaps in a single frame while the width is still mid-
            // animation, creating a visual "jump" on both left and right sides.
            Crossfade(
                targetState = isExpanded,
                animationSpec = tween(durationMillis = 200),
                label = "bubbleContent"
            ) { targetExpanded ->
                if (!targetExpanded) {
                    FluenceLogoIcon()
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 1. Cancel Button (Left)
                        IconButton(
                            onClick = { BubbleController.cancelRecording() },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color(0x1AFFFFFF), CircleShape)
                        ) {
                            Canvas(modifier = Modifier.size(14.dp)) {
                                val w = size.width
                                val h = size.height
                                drawLine(
                                    color = Color.White,
                                    start = Offset(0f, 0f),
                                    end = Offset(w, h),
                                    strokeWidth = 2.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = Color.White,
                                    start = Offset(w, 0f),
                                    end = Offset(0f, h),
                                    strokeWidth = 2.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }
                        }

                        // 2. Siri Waveform Pill (Center)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .padding(horizontal = 8.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color(0x0CFFFFFF))
                                .border(1.dp, Color(0x0DFFFFFF), RoundedCornerShape(24.dp))
                                .clickable {
                                    if (recordingState == RecordingState.RECORDING) {
                                        BubbleController.stopRecording(context)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (recordingState == RecordingState.TRANSCRIBING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else if (recordingState == RecordingState.ERROR) {
                                Text(
                                    text = errorMessage ?: "Error",
                                    color = Color(0xFFFF5252),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            } else {
                                SiriWaveform()
                            }
                        }

                        // 3. Confirm Button (Right)
                        val isAgentMode by BubbleController.isAgentMode.collectAsState()
                        val confirmBgColor = if (isAgentMode) Color(0xFF00F5D4) else Color(0xFFA855F7)
                        val confirmIconColor = if (isAgentMode) Color(0xFF0D0E12) else Color.White
                        IconButton(
                            onClick = { BubbleController.stopRecording(context) },
                            modifier = Modifier
                                .size(44.dp)
                                .background(confirmBgColor, CircleShape)
                        ) {
                            Canvas(modifier = Modifier.size(16.dp)) {
                                val w = size.width
                                val h = size.height
                                val path = Path().apply {
                                    moveTo(w * 0.2f, h * 0.5f)
                                    lineTo(w * 0.45f, h * 0.75f)
                                    lineTo(w * 0.85f, h * 0.25f)
                                }
                                drawPath(
                                    path = path,
                                    color = confirmIconColor,
                                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Custom Modifier that draws a high-end glowing background with amethyst obsidian aesthetic.
 */
fun Modifier.amethystObsidianGlow(
    isExpanded: Boolean,
    glowRadius: Dp = 8.dp,
    shape: RoundedCornerShape
): Modifier = this.composed {
    val isAgentMode by BubbleController.isAgentMode.collectAsState()
    val baseGlowColor = if (isAgentMode) Color(0xFF00F5D4) else Color(0xFFA855F7)
    val glowColor = baseGlowColor.copy(alpha = if (isExpanded) 0.65f else 0.45f)

    this.drawBehind {
        val shapeRadiusPx = shape.topStart.toPx(size, this)
        val maxOffset = glowRadius.toPx()

        // Draw concentric rounded rectangles to build a smooth hardware-accelerated glow
        val steps = 5
        for (i in 1..steps) {
            val offset = maxOffset * (i.toFloat() / steps)
            val alpha = glowColor.alpha * (1.0f - (i.toFloat() / (steps + 1)))
            val strokeWidth = maxOffset / steps * 1.5f

            drawRoundRect(
                color = glowColor.copy(alpha = alpha),
                topLeft = Offset(-offset, -offset),
                size = Size(size.width + offset * 2, size.height + offset * 2),
                cornerRadius = CornerRadius(shapeRadiusPx + offset, shapeRadiusPx + offset),
                style = Stroke(width = strokeWidth)
            )
        }
    }
    .background(
        color = Color(0xEA0D0E12), // Deep Obsidian base
        shape = shape
    )
    .border(
        width = 1.2.dp,
        brush = Brush.linearGradient(
            colors = if (isAgentMode) {
                listOf(
                    Color(0xFF00F5D4),
                    Color(0xFF00BBF9).copy(alpha = 0.5f)
                )
            } else {
                listOf(
                    Color(0xFFA855F7), // Amethyst Glow
                    Color(0xFF6366F1).copy(alpha = 0.5f) // Deep Indigo accent
                )
            }
        ),
        shape = shape
    )
}

/**
 * Fluence brand logo icon — replaces the old animated orb for a clean, premium look.
 */
@Composable
fun FluenceLogoIcon() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_fluence_logo),
            contentDescription = "Fluence",
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Siri-Style multi-layered animated sine wave visualizer.
 */
@Composable
fun SiriWaveform() {
    val rawAmplitude by BubbleController.amplitude.collectAsState()
    val isAgentMode by BubbleController.isAgentMode.collectAsState()

    val primaryColor = if (isAgentMode) Color(0xFF00F5D4) else Color(0xFFA855F7)
    val forefrontColor = if (isAgentMode) Color(0xFFE6FFFA) else Color(0xFFF3E8FF)

    // Smooth and boost the amplitude to prevent jerky jumps from 50ms polling
    val smoothedAmplitude by animateFloatAsState(
        targetValue = (rawAmplitude * 6f).coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "amplitude"
    )

    // Dynamically integrate phase for speed changes without jumps
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastTime = withFrameNanos { it }
        while (isActive) {
            val currentTime = withFrameNanos { it }
            val dt = (currentTime - lastTime) / 1e9f
            lastTime = currentTime
            
            // Speed increases when voice detects (smoothedAmplitude is higher)
            val speed = 1f + smoothedAmplitude * 4f
            phase = (phase + speed * dt * 2f * Math.PI.toFloat()) % (1000f * Math.PI.toFloat())
        }
    }

    val phase1 = phase
    val phase2 = -phase * 0.7f

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        // Determine actual height amplitude (minimum idle height of 0.1f)
        val activeAmplitude = (smoothedAmplitude * 0.8f + 0.1f) * (height * 0.45f)

        // Gradient brushes to fade out the waves near the left and right edges
        val gradientBrush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, forefrontColor.copy(alpha = 0.9f), Color.Transparent),
            startX = 0f,
            endX = width
        )
        val bgGradientBrush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, primaryColor.copy(alpha = 0.4f), Color.Transparent),
            startX = 0f,
            endX = width
        )

        // Wave 1: Background Wave
        val path1 = Path()
        path1.moveTo(0f, centerY)
        for (x in 0..width.toInt() step 6) {
            val xVal = x.toFloat()
            // Parabolic envelope to taper wave heights to 0 at edges
            val envelope = sin((xVal / width) * Math.PI.toFloat())
            val angle = (xVal / width) * 2f * Math.PI.toFloat() * 1.5f + phase1
            // Adding high frequency vibration based on amplitude
            val vibration = sin(xVal * 0.1f + phase1 * 3f) * smoothedAmplitude * 4f
            val yVal = centerY + (sin(angle) * activeAmplitude * 0.5f + vibration) * envelope
            path1.lineTo(xVal, yVal)
        }
        drawPath(
            path = path1,
            brush = bgGradientBrush,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Wave 2: Middle Wave
        val path2 = Path()
        path2.moveTo(0f, centerY)
        for (x in 0..width.toInt() step 6) {
            val xVal = x.toFloat()
            val envelope = sin((xVal / width) * Math.PI.toFloat())
            val angle = (xVal / width) * 2f * Math.PI.toFloat() * 2.5f + phase2
            val vibration = sin(xVal * 0.15f - phase2 * 4f) * smoothedAmplitude * 3f
            val yVal = centerY + (sin(angle) * activeAmplitude * 0.7f + vibration) * envelope
            path2.lineTo(xVal, yVal)
        }
        drawPath(
            path = path2,
            brush = bgGradientBrush,
            style = Stroke(width = 1.8.dp.toPx())
        )

        // Wave 3: Forefront Delicate Wave
        val path3 = Path()
        path3.moveTo(0f, centerY)
        for (x in 0..width.toInt() step 6) {
            val xVal = x.toFloat()
            val envelope = sin((xVal / width) * Math.PI.toFloat())
            val angle = (xVal / width) * 2f * Math.PI.toFloat() * 1.2f + (phase1 - phase2) * 0.5f
            val vibration = sin(xVal * 0.08f + phase1 * 5f) * smoothedAmplitude * 5f
            val yVal = centerY + (sin(angle) * activeAmplitude * 0.9f + vibration) * envelope
            path3.lineTo(xVal, yVal)
        }
        drawPath(
            path = path3,
            brush = gradientBrush,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
