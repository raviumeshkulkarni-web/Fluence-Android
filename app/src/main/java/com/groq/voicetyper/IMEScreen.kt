package com.groq.voicetyper

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun IMEScreen(
    audioRecorder: AudioRecorder,
    apiKey: String?,
    onBackspace: () -> Unit,
    onBackspaceSelect: (Int) -> Unit = {},
    onBackspaceDeleteSelected: () -> Unit = {},
    onBackspaceCancelSelect: () -> Unit = {},
    recordingState: RecordingState,
    isAgentMode: Boolean = false,
    errorMessage: String?,
    onCancelRecording: () -> Unit,
    onStartRecording: (Boolean) -> Unit,
    onStopRecording: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    isOfflineReady: Boolean = false,
    isOfflineMode: Boolean = false
) {
    val coroutineScope = rememberCoroutineScope()
    val view = androidx.compose.ui.platform.LocalView.current
    val offlineEngineState by TranscriptionSessionManager.offlineEngineState.collectAsState()

    // Recording duration timer
    var recordTimeSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(recordingState) {
        if (recordingState == RecordingState.RECORDING) {
            recordTimeSeconds = 0
            while (isActive) {
                delay(1000)
                recordTimeSeconds++
            }
        }
    }

    val minutes = recordTimeSeconds / 60
    val seconds = recordTimeSeconds % 60
    val timeText = String.format(Locale.US, "%02d:%02d", minutes, seconds)

    val isEnabled = (isOfflineReady || !apiKey.isNullOrBlank()) && recordingState != RecordingState.TRANSCRIBING
    val currentRecordingState by rememberUpdatedState(recordingState)
    val currentOnStartRecording by rememberUpdatedState(onStartRecording)
    val currentOnStopRecording by rememberUpdatedState(onStopRecording)

    // Infinite transitions for smooth animations
    val infiniteTransition = rememberInfiniteTransition(label = "aura")

    // Radar ping animation (when listening)
    val pingScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 2.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pingScale"
    )

    val pingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pingAlpha"
    )

    // Color definitions from Stitch designs
    val micBgColor = when (recordingState) {
        RecordingState.RECORDING -> if (isAgentMode) Color(0xFF00F5D4) else Color(0xFFA855F7) // Teal vs Amethyst Violet
        RecordingState.TRANSCRIBING -> Color(0xFF1E1F23) // Dark Grey
        RecordingState.ERROR -> Color(0xFFFF5252) // Error Red
        RecordingState.IDLE -> if (isEnabled) Color(0xFFE3E2E7) else Color(0xFF474649)
    }

    val micIconColor = when (recordingState) {
        RecordingState.RECORDING -> if (isAgentMode) Color(0xFF0D0E12) else Color(0xFF1B1B21) // Dark contrast
        RecordingState.TRANSCRIBING -> Color.White
        RecordingState.ERROR -> Color.White
        RecordingState.IDLE -> Color(0xFF121317)
    }

    val statusTextColor = when (recordingState) {
        RecordingState.RECORDING -> if (isAgentMode) Color(0xFF00F5D4) else Color(0xFFA855F7)
        RecordingState.TRANSCRIBING -> if (isAgentMode) Color(0xFF80FFE8) else Color(0xFFD18CFF)
        RecordingState.ERROR -> Color(0xFFFF5252)
        RecordingState.IDLE -> Color(0xFFE3E2E7)
    }

    val statusText = when (recordingState) {
        RecordingState.IDLE -> if (isOfflineReady && isOfflineMode) "Ready (offline)" else if (apiKey.isNullOrBlank()) "API KEY REQUIRED" else "Ready"
        RecordingState.RECORDING -> {
            if (isAgentMode) {
                "AI Command Mode... ($timeText)"
            } else if (isOfflineMode && offlineEngineState == OfflineEngineState.LOADING) {
                "Preparing model... ($timeText)"
            } else if (isOfflineMode) {
                "Listening (offline)... ($timeText)"
            } else {
                "Listening... ($timeText)"
            }
        }
        RecordingState.TRANSCRIBING -> "Transcribing..."
        RecordingState.ERROR -> errorMessage ?: "ERROR"
    }

    // Glass Pill Dynamic Styling Colors
    val pillBgColor = if (recordingState == RecordingState.RECORDING) Color(0xB2131319) else Color(0x80131319)

    val localOnBackspace by rememberUpdatedState(onBackspace)
    val localOnBackspaceSelect by rememberUpdatedState(onBackspaceSelect)
    val localOnBackspaceDeleteSelected by rememberUpdatedState(onBackspaceDeleteSelected)
    val localOnBackspaceCancelSelect by rememberUpdatedState(onBackspaceCancelSelect)

    // Layout: Center floating pill bar with floating state text above it
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Floating Status Text
        AnimatedVisibility(visible = recordingState != RecordingState.IDLE || errorMessage != null) {
            Text(
                text = statusText,
                color = statusTextColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color(0xCC0D0E12), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Centered Glass Pill Bar
        Row(
            modifier = Modifier
                .size(width = 240.dp, height = 64.dp)
                .drawBehind {
                    val isListening = recordingState == RecordingState.RECORDING
                    val baseGlowColor = if (isAgentMode) Color(0xFF00F5D4) else Color(0xFFA855F7)
                    val glowColor = baseGlowColor.copy(alpha = if (isListening) 0.65f else 0.45f)
                    val shapeRadiusPx = 32.dp.toPx()
                    val maxOffset = 8.dp.toPx()

                    // Draw concentric layers for a soft gradient glow (hardware-accelerated)
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
                    color = pillBgColor,
                    shape = RoundedCornerShape(32.dp)
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
                                Color(0xFFA855F7),
                                Color(0xFF6366F1).copy(alpha = 0.5f)
                            )
                        }
                    ),
                    shape = RoundedCornerShape(32.dp)
                )
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Keyboard Switcher Icon
            IconButton(
                onClick = onSwitchKeyboard,
                modifier = Modifier.size(44.dp)
            ) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    val w = size.width
                    val h = size.height
                    val path = Path().apply {
                        moveTo(w * 0.083f, h * 0.208f)
                        lineTo(w * 0.917f, h * 0.208f)
                        lineTo(w * 0.917f, h * 0.708f)
                        lineTo(w * 0.083f, h * 0.708f)
                        close()
                        // Space bar
                        moveTo(w * 0.292f, h * 0.583f)
                        lineTo(w * 0.708f, h * 0.583f)
                        // Keys
                        moveTo(w * 0.208f, h * 0.333f); lineTo(w * 0.292f, h * 0.333f)
                        moveTo(w * 0.375f, h * 0.333f); lineTo(w * 0.458f, h * 0.333f)
                        moveTo(w * 0.542f, h * 0.333f); lineTo(w * 0.625f, h * 0.333f)
                        moveTo(w * 0.708f, h * 0.333f); lineTo(w * 0.792f, h * 0.333f)
                        
                        moveTo(w * 0.25f, h * 0.458f); lineTo(w * 0.333f, h * 0.458f)
                        moveTo(w * 0.417f, h * 0.458f); lineTo(w * 0.5f, h * 0.458f)
                        moveTo(w * 0.583f, h * 0.458f); lineTo(w * 0.667f, h * 0.458f)
                        moveTo(w * 0.75f, h * 0.458f); lineTo(w * 0.833f, h * 0.458f)
                    }
                    drawPath(
                        path = path,
                        color = Color.White.copy(alpha = 0.8f),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }

            // 2. Microphone Toggle Button
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center
            ) {
                // Pulsing ripple ring if recording
                if (recordingState == RecordingState.RECORDING) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = pingScale
                                scaleY = pingScale
                            }
                    ) {
                        drawCircle(
                            color = (if (isAgentMode) Color(0xFF00F5D4) else Color(0xFFA855F7)).copy(alpha = pingAlpha),
                            radius = size.minDimension / 2,
                            style = Stroke(width = 1.5.dp.toPx())
                        )
                    }
                }

                // Rotating circle if transcribing
                if (recordingState == RecordingState.TRANSCRIBING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(micBgColor)
                            .pointerInput(isEnabled) {
                                if (!isEnabled) return@pointerInput
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitFirstDown()
                                        down.consume()

                                        var isLongPressTriggered = false
                                        val state = currentRecordingState

                                        // Check for long press to enter Agent Mode
                                        val longPressJob = coroutineScope.launch {
                                            kotlinx.coroutines.delay(500)
                                            if (state == RecordingState.IDLE || state == RecordingState.ERROR) {
                                                isLongPressTriggered = true
                                                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                                currentOnStartRecording(true)
                                            }
                                        }

                                        // Wait for release
                                        do {
                                            val event = awaitPointerEvent()
                                            event.changes.forEach { it.consume() }
                                        } while (event.changes.any { it.pressed })

                                        longPressJob.cancel()

                                        if (state == RecordingState.RECORDING || state == RecordingState.TRANSCRIBING) {
                                            // STOP
                                            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                            if (state == RecordingState.RECORDING) {
                                                currentOnStopRecording()
                                            }
                                        } else if (state == RecordingState.IDLE || state == RecordingState.ERROR) {
                                            // START standard dictation if long press wasn't triggered
                                            if (!isLongPressTriggered) {
                                                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                                currentOnStartRecording(false)
                                            }
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(22.dp)) {
                            val w = size.width
                            val h = size.height
                            
                            // Mic main cylinder
                            val micPath = Path().apply {
                                moveTo(w * 0.35f, h * 0.2f)
                                lineTo(w * 0.65f, h * 0.2f)
                                arcTo(
                                    rect = Rect(w * 0.35f, h * 0.1f, w * 0.65f, h * 0.4f),
                                    startAngleDegrees = 180f,
                                    sweepAngleDegrees = 180f,
                                    forceMoveTo = false
                                )
                                lineTo(w * 0.65f, h * 0.55f)
                                arcTo(
                                    rect = Rect(w * 0.35f, h * 0.45f, w * 0.65f, h * 0.65f),
                                    startAngleDegrees = 0f,
                                    sweepAngleDegrees = 180f,
                                    forceMoveTo = false
                                )
                                close()
                            }
                            drawPath(
                                path = micPath,
                                color = micIconColor
                            )
                            
                            // Mic stand
                            val standPath = Path().apply {
                                moveTo(w * 0.25f, h * 0.45f)
                                arcTo(
                                    rect = Rect(w * 0.25f, h * 0.35f, w * 0.75f, h * 0.75f),
                                    startAngleDegrees = 180f,
                                    sweepAngleDegrees = -180f,
                                    forceMoveTo = true
                                )
                                moveTo(w * 0.5f, h * 0.75f)
                                lineTo(w * 0.5f, h * 0.85f)
                                moveTo(w * 0.35f, h * 0.85f)
                                lineTo(w * 0.65f, h * 0.85f)
                            }
                            drawPath(
                                path = standPath,
                                color = micIconColor,
                                style = Stroke(width = 1.5.dp.toPx())
                            )
                        }
                    }
                }
            }

            // 3. Backspace Icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .pointerInput(Unit) {
                        coroutineScope {
                            var autoRepeatJob: Job? = null
                            var startX = 0f
                            var isDragging = false
                            var currentWordsSelected = 0

                            awaitPointerEventScope {
                                while (true) {
                                    val downEvent = awaitFirstDown()
                                    startX = downEvent.position.x
                                    isDragging = false
                                    currentWordsSelected = 0

                                    // Start auto-repeat timer for holding (400ms delay, then deletes every 60ms)
                                    autoRepeatJob = launch {
                                        delay(400)
                                        while (isActive) {
                                            localOnBackspace()
                                            delay(60)
                                        }
                                    }

                                    // Track drag movement
                                    var dragEvent: PointerInputChange? = null
                                    do {
                                        val event = awaitPointerEvent()
                                        dragEvent = event.changes.firstOrNull()
                                        if (dragEvent != null && dragEvent.pressed) {
                                            val currentX = dragEvent.position.x
                                            val deltaX = currentX - startX

                                            if (deltaX < -24.dp.toPx()) {
                                                autoRepeatJob?.cancel() // Cancel holding repeat
                                                isDragging = true
                                                val words = ((-deltaX - 24.dp.toPx()) / 32.dp.toPx()).toInt() + 1
                                                if (words != currentWordsSelected) {
                                                    currentWordsSelected = words
                                                    localOnBackspaceSelect(words)
                                                }
                                            } else if (isDragging && deltaX >= -12.dp.toPx()) {
                                                currentWordsSelected = 0
                                                localOnBackspaceCancelSelect()
                                            }
                                            dragEvent.consume()
                                        }
                                    } while (dragEvent != null && dragEvent.pressed)

                                    // Released / Touch up!
                                    autoRepeatJob?.cancel()
                                    if (isDragging) {
                                        if (currentWordsSelected > 0) {
                                            localOnBackspaceDeleteSelected()
                                        } else {
                                            localOnBackspaceCancelSelect()
                                        }
                                    } else {
                                        localOnBackspace()
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(24.dp)) {
                    val w = size.width
                    val h = size.height
                    val path = Path().apply {
                        moveTo(w * 0.35f, h * 0.2f)
                        lineTo(w * 0.9f, h * 0.2f)
                        lineTo(w * 0.9f, h * 0.8f)
                        lineTo(w * 0.35f, h * 0.8f)
                        lineTo(w * 0.1f, h * 0.5f)
                        close()
                        // X mark
                        moveTo(w * 0.5f, h * 0.38f)
                        lineTo(w * 0.75f, h * 0.62f)
                        moveTo(w * 0.75f, h * 0.38f)
                        lineTo(w * 0.5f, h * 0.62f)
                    }
                    drawPath(
                        path = path,
                        color = Color.White.copy(alpha = 0.8f),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }
    }
}
