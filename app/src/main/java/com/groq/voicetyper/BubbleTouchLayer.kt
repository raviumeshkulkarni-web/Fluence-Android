package com.groq.voicetyper

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Invisible touch replica of the FloatingBubbleUI interaction envelope.
 *
 * Lives in its own transparent overlay window (see FloatingBubbleService) and
 * receives every touch that V1 routed to the visual window. No visuals are
 * drawn here; the window is sized by this composable so the touch envelope is
 * exactly:
 *  - collapsed: 88x88dp (56x56dp pill + 16dp padding ring)
 *  - expanded:  272x96dp (the exact visual window frame; buttons at the same
 *    positions as the visual pill's, so there is no dead zone while expanded)
 * The size switches INSTANTLY on isBubbleExpanded transitions — never per
 * frame — so the WRAP_CONTENT window never resizes during the morph.
 *
 * The whole subtree is semantically empty (clearAndSetSemantics) so the visual
 * window stays the app's single accessibility surface.
 */
@Composable
fun BubbleTouchLayer(
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragReleased: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    val isExpanded by BubbleController.isBubbleExpanded.collectAsState()
    val recordingState by BubbleController.recordingState.collectAsState()
    val isAnchoredRight by BubbleController.isAnchoredRight.collectAsState()

    // Root sized to the window envelope, padded like the visual root, content
    // aligned like the visual pill (TopEnd / TopStart), so the touch regions
    // land on exactly the same screen coordinates as V1's.
    Box(
        modifier = Modifier
            .size(
                width = if (isExpanded) 272.dp else 88.dp,
                height = if (isExpanded) 96.dp else 88.dp
            )
            .padding(16.dp)
            .clearAndSetSemantics {},
        contentAlignment = if (isAnchoredRight) Alignment.TopEnd else Alignment.TopStart
    ) {
        if (isExpanded) {
            // Replica of the expanded pill's hit layout (FloatingBubbleUI Row).
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Cancel (left) — 44dp like the visual IconButton.
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable { BubbleController.cancelRecording() }
                )
                // Waveform pill (center) — 48dp tall, clickable only while RECORDING.
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .padding(horizontal = 8.dp)
                        .clickable {
                            if (recordingState == RecordingState.RECORDING) {
                                BubbleController.stopRecording(context)
                            }
                        }
                )
                // Confirm (right) — 44dp like the visual IconButton.
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clickable { BubbleController.stopRecording(context) }
                )
            }
        } else {
            // Replica of the collapsed pill's gesture block (FloatingBubbleUI):
            // 500ms long-press → agent mode, 8dp drag threshold → drag, tap → record.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isExpanded) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown()
                                val startPos = down.position
                                var isDragging = false
                                var isLongPressTriggered = false

                                val longPressJob = coroutineScope.launch {
                                    delay(500)
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
            )
        }
    }
}
