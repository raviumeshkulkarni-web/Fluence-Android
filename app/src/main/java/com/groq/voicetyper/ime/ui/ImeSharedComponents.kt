package com.groq.voicetyper.ime.ui

import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.RecordingState
import com.groq.voicetyper.ime.EditorInfoHelper
import com.groq.voicetyper.theme.AgentBlue
import com.groq.voicetyper.theme.AgentTeal
import com.groq.voicetyper.theme.BrandAmethyst
import com.groq.voicetyper.theme.Error
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.ImeInkDark
import com.groq.voicetyper.theme.ImePillBg
import com.groq.voicetyper.theme.ImePillBgActive
import com.groq.voicetyper.theme.IndigoAccent
import com.groq.voicetyper.theme.Panel
import com.groq.voicetyper.theme.PanelElevated
import com.groq.voicetyper.theme.TextDisabled
import com.groq.voicetyper.theme.TextPrimary
import com.groq.voicetyper.theme.TextSecondary
import com.groq.voicetyper.theme.rememberReducedMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reusable standard key cap with haptic feedback and press feedback.
 */
@Composable
fun ImeKeyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescriptionText: String = text,
    isPrimary: Boolean = false,
    fontSize: Dp = 18.dp
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = when {
            isPressed -> Color.White.copy(alpha = 0.25f)
            isPrimary -> BrandAmethyst
            else -> PanelElevated
        },
        animationSpec = tween(durationMillis = 60),
        label = "keyBg"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isPressed -> BrandAmethyst.copy(alpha = 0.8f)
            isPrimary -> BrandAmethyst
            else -> Color.White.copy(alpha = 0.08f)
        },
        animationSpec = tween(durationMillis = 60),
        label = "keyBorder"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(0.8.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .semantics {
                role = Role.Button
                contentDescription = contentDescriptionText
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isPrimary) Color.White else TextPrimary,
            fontSize = fontSize.value.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Spacebar key with tactile haptics, press feedback, clean styling,
 * and swipe-to-scrub cursor navigation (horizontal drag).
 */
@Composable
fun ImeSpacebar(
    onSpace: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = "space",
    onMoveCursor: (Int) -> Unit = {}
) {
    val view = LocalView.current
    val currentOnSpace by rememberUpdatedState(onSpace)
    val currentOnMoveCursor by rememberUpdatedState(onMoveCursor)
    var isPressed by remember { mutableStateOf(false) }

    val bgColor by animateColorAsState(
        targetValue = if (isPressed) Color.White.copy(alpha = 0.25f) else PanelElevated,
        animationSpec = tween(durationMillis = 60),
        label = "spaceBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isPressed) BrandAmethyst.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f),
        animationSpec = tween(durationMillis = 60),
        label = "spaceBorder"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(0.8.dp, borderColor, RoundedCornerShape(10.dp))
            .pointerInput(Unit) {
                coroutineScope {
                    awaitPointerEventScope {
                        while (true) {
                            val downEvent = awaitFirstDown()
                            isPressed = true
                            val startX = downEvent.position.x
                            var lastTickX = startX
                            var hasDragged = false
                            val stepPx = 14.dp.toPx()

                            do {
                                val event = awaitPointerEvent()
                                val dragEvent = event.changes.firstOrNull()
                                if (dragEvent != null && dragEvent.pressed) {
                                    val currentX = dragEvent.position.x
                                    val deltaX = currentX - lastTickX
                                    val steps = (deltaX / stepPx).toInt()
                                    if (steps != 0) {
                                        hasDragged = true
                                        val direction = if (steps > 0) 1 else -1
                                        repeat(kotlin.math.abs(steps)) {
                                            currentOnMoveCursor(direction)
                                        }
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        lastTickX += steps * stepPx
                                    }
                                }
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })

                            // Released / touch up: if not dragged beyond threshold, insert space
                            isPressed = false
                            if (!hasDragged) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                currentOnSpace()
                            }
                        }
                    }
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = "Space"
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Exact preservation of the existing delete/backspace button:
 * - Tap: single delete
 * - Hold: 400ms delay, 60ms auto-repeat
 * - Swipe left: word selection & delete on release, cancel on return
 */
@Composable
fun ImeBackspaceButton(
    onBackspace: () -> Unit,
    onBackspaceSelect: (Int) -> Unit = {},
    onBackspaceDeleteSelected: () -> Unit = {},
    onBackspaceCancelSelect: () -> Unit = {},
    modifier: Modifier = Modifier,
    iconColor: Color = Color.White.copy(alpha = 0.85f)
) {
    val localOnBackspace by rememberUpdatedState(onBackspace)
    val localOnBackspaceSelect by rememberUpdatedState(onBackspaceSelect)
    val localOnBackspaceDeleteSelected by rememberUpdatedState(onBackspaceDeleteSelected)
    val localOnBackspaceCancelSelect by rememberUpdatedState(onBackspaceCancelSelect)
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }

    val bgColor by animateColorAsState(
        targetValue = if (isPressed) Color.White.copy(alpha = 0.25f) else PanelElevated,
        animationSpec = tween(durationMillis = 60),
        label = "backspaceBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isPressed) BrandAmethyst.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f),
        animationSpec = tween(durationMillis = 60),
        label = "backspaceBorder"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(0.8.dp, borderColor, RoundedCornerShape(12.dp))
            .semantics {
                role = Role.Button
                contentDescription = "Backspace"
                onClick(label = "Delete") {
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    localOnBackspace()
                    true
                }
            }
            .pointerInput(Unit) {
                coroutineScope {
                    var autoRepeatJob: Job? = null
                    var startX = 0f
                    var isDragging = false
                    var currentWordsSelected = 0
                    var autoRepeatFired = false

                    awaitPointerEventScope {
                        while (true) {
                            val downEvent = awaitFirstDown()
                            isPressed = true
                            startX = downEvent.position.x
                            isDragging = false
                            currentWordsSelected = 0
                            autoRepeatFired = false

                            // Start auto-repeat timer for holding (400ms delay, then deletes every 60ms)
                            autoRepeatJob = launch {
                                delay(400)
                                while (isActive) {
                                    autoRepeatFired = true
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                            isPressed = false
                            autoRepeatJob?.cancel()
                            if (isDragging) {
                                if (currentWordsSelected > 0) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    localOnBackspaceDeleteSelected()
                                } else {
                                    localOnBackspaceCancelSelect()
                                }
                            } else if (!autoRepeatFired) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
                color = iconColor,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

/**
 * Reusable prominent microphone button with ripple animations and agent mode long-press.
 */
@Composable
fun ImeMicButton(
    recordingState: RecordingState,
    isAgentMode: Boolean,
    isVoiceGated: Boolean,
    onStartRecording: (Boolean) -> Unit,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp
) {
    val coroutineScope = rememberCoroutineScope()
    val view = LocalView.current
    val reducedMotion = rememberReducedMotion()

    val isEnabled = !isVoiceGated && recordingState != RecordingState.TRANSCRIBING
    val currentRecordingState by rememberUpdatedState(recordingState)
    val currentOnStartRecording by rememberUpdatedState(onStartRecording)
    val currentOnStopRecording by rememberUpdatedState(onStopRecording)

    var isPressed by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "aura")
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

    // Gentle breathing pulse for idle aura
    val idleAuraAlpha by if (reducedMotion) {
        remember { mutableFloatStateOf(0.30f) }
    } else {
        infiniteTransition.animateFloat(
            initialValue = 0.22f,
            targetValue = 0.38f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "idleAuraAlpha"
        )
    }

    val auraColor = if (isAgentMode) AgentTeal else BrandAmethyst

    val micBgColor by animateColorAsState(
        targetValue = when {
            isVoiceGated -> PanelElevated
            recordingState == RecordingState.RECORDING -> auraColor
            recordingState == RecordingState.TRANSCRIBING -> Panel
            recordingState == RecordingState.ERROR -> Error
            isPressed -> Color.White.copy(alpha = 0.22f)
            else -> PanelElevated
        },
        animationSpec = tween(durationMillis = 60),
        label = "micBg"
    )

    val micBorderColor by animateColorAsState(
        targetValue = when {
            isVoiceGated -> Color.Transparent
            recordingState == RecordingState.RECORDING -> auraColor
            recordingState == RecordingState.TRANSCRIBING -> auraColor.copy(alpha = 0.5f)
            recordingState == RecordingState.ERROR -> Error
            isPressed -> auraColor.copy(alpha = 0.85f)
            else -> auraColor.copy(alpha = 0.35f)
        },
        animationSpec = tween(durationMillis = 60),
        label = "micBorder"
    )

    val micIconColor by animateColorAsState(
        targetValue = when {
            isVoiceGated -> TextDisabled
            recordingState == RecordingState.RECORDING -> ImeInkDark
            recordingState == RecordingState.TRANSCRIBING -> Color.White
            recordingState == RecordingState.ERROR -> Color.White
            else -> TextPrimary
        },
        animationSpec = tween(durationMillis = 60),
        label = "micIcon"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Subtle aura of amethyst (or teal in agent mode) when idle
        if (recordingState == RecordingState.IDLE && !isVoiceGated) {
            Canvas(
                modifier = Modifier.size(size + 16.dp)
            ) {
                val auraRadiusPx = this.size.minDimension / 2f
                val buttonRadiusPx = (size - 8.dp).toPx() / 2f
                val stopEdge = (buttonRadiusPx / auraRadiusPx).coerceIn(0.5f, 0.75f)

                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to auraColor.copy(alpha = 0.0f),
                            stopEdge * 0.75f to auraColor.copy(alpha = idleAuraAlpha * 0.4f),
                            stopEdge to auraColor.copy(alpha = idleAuraAlpha),
                            stopEdge + (1f - stopEdge) * 0.45f to auraColor.copy(alpha = idleAuraAlpha * 0.35f),
                            1.0f to Color.Transparent
                        ),
                        center = center,
                        radius = auraRadiusPx
                    ),
                    radius = auraRadiusPx,
                    center = center
                )
            }
        }

        // Pulsing radar ripple ring when listening (active behavior intact)
        if (recordingState == RecordingState.RECORDING) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = if (reducedMotion) 1f else pingScale
                        scaleY = if (reducedMotion) 1f else pingScale
                    }
            ) {
                drawCircle(
                    color = auraColor.copy(
                        alpha = if (reducedMotion) 0.5f else pingAlpha
                    ),
                    radius = this.size.minDimension / 2,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // Rotating circle when transcribing
        if (recordingState == RecordingState.TRANSCRIBING) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = TextPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Box(
                modifier = Modifier
                    .size(size - 8.dp)
                    .clip(CircleShape)
                    .background(micBgColor)
                    .border(1.dp, micBorderColor, CircleShape)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = "Microphone"
                        stateDescription = when {
                            isVoiceGated -> "Unavailable in sensitive field"
                            currentRecordingState == RecordingState.RECORDING -> "Recording"
                            currentRecordingState == RecordingState.TRANSCRIBING -> "Transcribing"
                            currentRecordingState == RecordingState.ERROR -> "Error"
                            else -> "Ready"
                        }
                        if (!isEnabled) {
                            disabled()
                        } else {
                            onClick(label = if (currentRecordingState == RecordingState.RECORDING) "Stop recording" else "Start dictation") {
                                if (currentRecordingState == RecordingState.RECORDING) {
                                    currentOnStopRecording()
                                } else {
                                    currentOnStartRecording(false)
                                }
                                true
                            }
                            onLongClick(label = "Start AI command mode") {
                                currentOnStartRecording(true)
                                true
                            }
                        }
                    }
                    .pointerInput(isEnabled) {
                        if (!isEnabled) return@pointerInput
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown()
                                down.consume()
                                isPressed = true

                                val stateAtDown = currentRecordingState
                                var isLongPressTriggered = false

                                val longPressJob = if (stateAtDown == RecordingState.IDLE || stateAtDown == RecordingState.ERROR) {
                                    coroutineScope.launch {
                                        delay(500)
                                        if (currentRecordingState == RecordingState.IDLE || currentRecordingState == RecordingState.ERROR) {
                                            isLongPressTriggered = true
                                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                            currentOnStartRecording(true)
                                        }
                                    }
                                } else null

                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })

                                isPressed = false
                                longPressJob?.cancel()

                                val stateAtUp = currentRecordingState
                                if (stateAtDown == RecordingState.RECORDING || stateAtUp == RecordingState.RECORDING ||
                                    stateAtDown == RecordingState.TRANSCRIBING || stateAtUp == RecordingState.TRANSCRIBING
                                ) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    currentOnStopRecording()
                                } else if (stateAtDown == RecordingState.IDLE || stateAtDown == RecordingState.ERROR) {
                                    if (!isLongPressTriggered) {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        currentOnStartRecording(false)
                                    }
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.size(22.dp)) {
                    val w = this.size.width
                    val h = this.size.height

                    // Mic cylinder
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
                    drawPath(path = micPath, color = micIconColor)

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
}

/**
 * Glassmorphic container that encapsulates keyboard panels:
 * - 95% opacity background to prevent distracting under-keyboard background depth.
 * - Clean monochrome border when idle (no unnecessary accent colors).
 * - Accent glow and border activate only when actively recording.
 */
@Composable
fun ImeCardContainer(
    modifier: Modifier = Modifier,
    isAgentMode: Boolean = false,
    isListening: Boolean = false,
    content: @Composable () -> Unit
) {
    // 95% opaque dark glass background
    val pillBgColor = Color(0xFF131319).copy(alpha = 0.95f)

    // Subtle monochrome border when idle; accent border only when listening
    val borderColor = if (isListening) {
        if (isAgentMode) AgentTeal.copy(alpha = 0.7f) else BrandAmethyst.copy(alpha = 0.7f)
    } else {
        Color.White.copy(alpha = 0.10f)
    }

    Box(
        modifier = modifier
            .then(
                if (isListening) {
                    Modifier.drawBehind {
                        val glowColor = (if (isAgentMode) AgentTeal else BrandAmethyst).copy(alpha = 0.35f)
                        val shapeRadiusPx = 28.dp.toPx()
                        val maxOffset = 8.dp.toPx()
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
                } else Modifier
            )
            .background(color = pillBgColor, shape = RoundedCornerShape(28.dp))
            .border(
                width = 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(28.dp)
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        content()
    }
}

/**
 * Keyboard switcher icon button.
 */
@Composable
fun ImeKeyboardSwitchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = if (isPressed) Color.White.copy(alpha = 0.25f) else PanelElevated,
        animationSpec = tween(durationMillis = 60),
        label = "switchBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isPressed) BrandAmethyst.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f),
        animationSpec = tween(durationMillis = 60),
        label = "switchBorder"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(0.8.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
            .semantics {
                role = Role.Button
                contentDescription = "Switch keyboard"
            },
        contentAlignment = Alignment.Center
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
}

/**
 * Downward chevron button to collapse/hide the IME.
 */
@Composable
fun ImeHideButton(
    onHideKeyboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PanelElevated)
            .border(0.8.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .clickable(interactionSource = interactionSource, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onHideKeyboard()
            }
            .semantics {
                role = Role.Button
                contentDescription = "Hide keyboard"
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.25f, h * 0.38f)
                lineTo(w * 0.5f, h * 0.62f)
                lineTo(w * 0.75f, h * 0.38f)
            }
            drawPath(
                path = path,
                color = Color.White.copy(alpha = 0.85f),
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

/**
 * Context-aware Enter / Action key that respects editor action, multiline, or newline.
 */
@Composable
fun ImeActionKey(
    editorInfo: EditorInfo?,
    onPerformAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val isMultiline = EditorInfoHelper.isMultiline(editorInfo)
    val actionId = EditorInfoHelper.getActionId(editorInfo)

    val (label, isIcon) = when {
        isMultiline -> Pair("Return", true)
        actionId == EditorInfo.IME_ACTION_SEARCH -> Pair("Search", false)
        actionId == EditorInfo.IME_ACTION_SEND -> Pair("Send", false)
        actionId == EditorInfo.IME_ACTION_GO -> Pair("Go", false)
        actionId == EditorInfo.IME_ACTION_NEXT -> Pair("Next", false)
        actionId == EditorInfo.IME_ACTION_DONE -> Pair("Done", false)
        else -> Pair("Enter", true)
    }

    val bgColor by animateColorAsState(
        targetValue = if (isPressed) Color.White.copy(alpha = 0.25f) else PanelElevated,
        animationSpec = tween(durationMillis = 60),
        label = "actionBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isPressed) BrandAmethyst.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.08f),
        animationSpec = tween(durationMillis = 60),
        label = "actionBorder"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(0.8.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interactionSource, indication = null) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onPerformAction()
            }
            .semantics {
                role = Role.Button
                contentDescription = label
            },
        contentAlignment = Alignment.Center
    ) {
        if (isIcon) {
            Canvas(modifier = Modifier.size(18.dp)) {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.75f, h * 0.28f)
                    lineTo(w * 0.75f, h * 0.58f)
                    lineTo(w * 0.28f, h * 0.58f)
                    moveTo(w * 0.44f, h * 0.42f)
                    lineTo(w * 0.28f, h * 0.58f)
                    lineTo(w * 0.44f, h * 0.74f)
                }
                drawPath(
                    path = path,
                    color = Color.White.copy(alpha = 0.85f),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }
        } else {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

