package com.groq.voicetyper

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
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.composed
import kotlin.math.sin

@Composable
fun FloatingBubbleUI(
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragReleased: () -> Unit,
    onExpandChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val isExpanded by BubbleController.isBubbleExpanded.collectAsState()
    val recordingState by BubbleController.recordingState.collectAsState()
    val errorMessage by BubbleController.errorMessage.collectAsState()
    var lastTapTime by remember { mutableStateOf(0L) }
    val coroutineScope = rememberCoroutineScope()
    var pendingTapJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    // Notify parent window manager of size changes so position can be adjusted if on right side
    var previousExpanded by remember { mutableStateOf(isExpanded) }
    LaunchedEffect(isExpanded) {
        if (isExpanded != previousExpanded) {
            previousExpanded = isExpanded
            onExpandChanged(isExpanded)
        }
    }

    // Size animations for morphing transition
    val width by animateDpAsState(
        targetValue = if (isExpanded) 240.dp else 56.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "width"
    )
    val height by animateDpAsState(
        targetValue = if (isExpanded) 64.dp else 56.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
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
                // Gesture handling for Collapsed state (drag, tap, hold)
                .run {
                    if (!isExpanded) {
                        this.pointerInput(isExpanded) {
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown()
                                    val startPos = down.position
                                    var isDragging = false

                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: continue

                                        if (change.pressed) {
                                            val currentPos = change.position
                                            val dragDistance = (currentPos - startPos).getDistance()

                                            if (dragDistance > 8.dp.toPx()) {
                                                isDragging = true
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

                                    if (isDragging) {
                                        pendingTapJob?.cancel()
                                        onDragReleased()
                                    } else {
                                        val currentState = BubbleController.recordingState.value
                                        if (currentState == RecordingState.RECORDING) {
                                            BubbleController.stopRecording(context)
                                        } else if (currentState == RecordingState.IDLE || currentState == RecordingState.ERROR) {
                                            val currentTime = System.currentTimeMillis()
                                            if (currentTime - lastTapTime < 300) {
                                                pendingTapJob?.cancel()
                                                pendingTapJob = null
                                                BubbleController.startRecording(context, agentMode = true)
                                            } else {
                                                pendingTapJob?.cancel()
                                                pendingTapJob = coroutineScope.launch {
                                                    kotlinx.coroutines.delay(250)
                                                    BubbleController.startRecording(context, agentMode = false)
                                                }
                                            }
                                            lastTapTime = currentTime
                                        }
                                    }
                                }
                            }
                        }
                    } else this
                },
            contentAlignment = Alignment.Center
        ) {
            if (!isExpanded) {
                MiniFluenceOrb()
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
    
    val density = androidx.compose.ui.platform.LocalDensity.current
    val glowRadiusPx = with(density) { glowRadius.toPx() }
    
    // Cache framework Paint and BlurMaskFilter objects to prevent allocation at 60fps
    val paint = remember {
        android.graphics.Paint().apply {
            this.style = android.graphics.Paint.Style.STROKE
        }
    }
    val blurMaskFilter = remember(glowRadiusPx) {
        android.graphics.BlurMaskFilter(glowRadiusPx, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    this.drawBehind {
        val shapeRadiusPx = shape.topStart.toPx(size, this)

        drawIntoCanvas { canvas ->
            paint.color = glowColor.toArgb()
            paint.strokeWidth = 2.dp.toPx() + glowRadiusPx * 0.4f
            paint.maskFilter = blurMaskFilter

            val rect = android.graphics.RectF(0f, 0f, size.width, size.height)
            canvas.nativeCanvas.drawRoundRect(rect, shapeRadiusPx, shapeRadiusPx, paint)
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
 * A beautiful, miniaturized Fluence logo orb that pulses.
 */
@Composable
fun MiniFluenceOrb() {
    val infiniteTransition = rememberInfiniteTransition(label = "mini_orb")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Glowing Outer Radial Aura
        Canvas(modifier = Modifier.size(56.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFA855F7).copy(alpha = 0.4f * pulseAlpha),
                        Color(0xFFA855F7).copy(alpha = 0.02f * pulseAlpha),
                        Color.Transparent
                    )
                ),
                radius = size.width / 2 * pulseScale
            )
        }

        // Inner frosted amethyst glass circle
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF7C3AED).copy(alpha = 0.5f),
                            Color(0xFFC084FC).copy(alpha = 0.2f)
                        )
                    ),
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.3f),
                            Color(0xFFA855F7).copy(alpha = 0.1f)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // Mini 3-line static/equalizer visualizer
            Canvas(modifier = Modifier.size(14.dp)) {
                val lineStroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
                
                // Static wave lines heights
                val h1 = size.height * 0.4f
                val h2 = size.height * 0.8f
                val h3 = size.height * 0.5f

                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.25f, size.height * 0.5f - h1 / 2),
                    end = Offset(size.width * 0.25f, size.height * 0.5f + h1 / 2),
                    strokeWidth = lineStroke.width,
                    cap = lineStroke.cap
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.5f, size.height * 0.5f - h2 / 2),
                    end = Offset(size.width * 0.5f, size.height * 0.5f + h2 / 2),
                    strokeWidth = lineStroke.width,
                    cap = lineStroke.cap
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.75f, size.height * 0.5f - h3 / 2),
                    end = Offset(size.width * 0.75f, size.height * 0.5f + h3 / 2),
                    strokeWidth = lineStroke.width,
                    cap = lineStroke.cap
                )
            }
        }
    }
}

/**
 * Siri-Style multi-layered animated sine wave visualizer.
 */
@Composable
fun SiriWaveform() {
    val amplitude by BubbleController.amplitude.collectAsState()
    val isAgentMode by BubbleController.isAgentMode.collectAsState()
    val wave1Color = if (isAgentMode) Color(0xFF00BBF9).copy(alpha = 0.45f) else Color(0xFF6366F1).copy(alpha = 0.45f)
    val wave2Color = if (isAgentMode) Color(0xFF00F5D4).copy(alpha = 0.65f) else Color(0xFFA855F7).copy(alpha = 0.65f)
    val wave3Color = Color.White.copy(alpha = 0.95f)

    val infiniteTransition = rememberInfiniteTransition(label = "siri_waves")
    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase1"
    )
    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase2"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        // Determine actual height amplitude (minimum idle height of 0.1f so waves always move slightly)
        val activeAmplitude = (amplitude.coerceIn(0f, 1f) * 0.85f + 0.15f) * (height * 0.35f)

        // Wave 1: Deep Indigo / Cyan-Blue (Background)
        val path1 = Path()
        path1.moveTo(0f, centerY)
        for (x in 0..width.toInt() step 4) {
            val xVal = x.toFloat()
            val angle = (xVal / width) * 2f * Math.PI.toFloat() * 1.4f + phase1
            val yVal = centerY + sin(angle) * activeAmplitude * 0.6f
            path1.lineTo(xVal, yVal)
        }
        drawPath(
            path = path1,
            color = wave1Color,
            style = Stroke(width = 2.dp.toPx())
        )

        // Wave 2: Amethyst / Teal (Middle)
        val path2 = Path()
        path2.moveTo(0f, centerY)
        for (x in 0..width.toInt() step 4) {
            val xVal = x.toFloat()
            val angle = (xVal / width) * 2f * Math.PI.toFloat() * 2.0f + phase2
            val yVal = centerY + sin(angle) * activeAmplitude * 0.8f
            path2.lineTo(xVal, yVal)
        }
        drawPath(
            path = path2,
            color = wave2Color,
            style = Stroke(width = 2.5.dp.toPx())
        )

        // Wave 3: White/Lavender (Forefront)
        val path3 = Path()
        path3.moveTo(0f, centerY)
        for (x in 0..width.toInt() step 4) {
            val xVal = x.toFloat()
            val angle = (xVal / width) * 2f * Math.PI.toFloat() * 1.0f + (phase1 - phase2)
            val yVal = centerY + sin(angle) * activeAmplitude * 1.0f
            path3.lineTo(xVal, yVal)
        }
        drawPath(
            path = path3,
            color = wave3Color,
            style = Stroke(width = 3.dp.toPx())
        )
    }
}
