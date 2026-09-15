package com.groq.voicetyper.ime.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.RecordingState
import com.groq.voicetyper.ime.KeyboardPanelMode

/**
 * Primary Voice-First Keyboard Layout:
 * Full-width, spacious, and perfectly symmetric:
 *
 * Row 1 (Hero Voice Row):
 *   [Switch Keyboard] [123] | (( [ MIC ] )) | [.,?!] [Delete]
 *
 * Row 2 (Utility Row):
 *   [Hide] | [         SPACEBAR         ] | [Enter / Action]
 */
@Composable
fun MainKeyboardView(
    recordingState: RecordingState,
    isAgentMode: Boolean,
    isVoiceGated: Boolean,
    editorInfo: EditorInfo?,
    onStartRecording: (Boolean) -> Unit,
    onStopRecording: () -> Unit,
    onBackspace: () -> Unit,
    onBackspaceSelect: (Int) -> Unit = {},
    onBackspaceDeleteSelected: () -> Unit = {},
    onBackspaceCancelSelect: () -> Unit = {},
    onCommitText: (String) -> Unit,
    onPerformAction: () -> Unit,
    onSwitchKeyboard: () -> Unit,
    onSwitchMode: (KeyboardPanelMode) -> Unit,
    onHideKeyboard: () -> Unit = {},
    onMoveCursor: (Int) -> Unit = {},
    onBoundsChanged: (android.graphics.Rect) -> Unit = {},
    modifier: Modifier = Modifier
) {
    ImeCardContainer(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .onGloballyPositioned { coordinates ->
                val pos = coordinates.positionInRoot()
                val size = coordinates.size
                onBoundsChanged(
                    android.graphics.Rect(
                        pos.x.toInt(),
                        pos.y.toInt(),
                        (pos.x + size.width).toInt(),
                        (pos.y + size.height).toInt()
                    )
                )
            },
        isAgentMode = isAgentMode,
        isListening = recordingState == RecordingState.RECORDING
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Row 1: Iconic 3-control hero dictation row (Symmetrical with matching buttons & comfortable spacing)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Spacer(modifier = Modifier.weight(1f))

                // Left: Keyboard Switcher (Matching size: 60dp x 50dp)
                ImeKeyboardSwitchButton(
                    onClick = onSwitchKeyboard,
                    modifier = Modifier
                        .size(width = 60.dp, height = 50.dp)
                )

                Spacer(modifier = Modifier.width(32.dp))

                // Dead Center: Prominent Hero Mic Button (58dp circle)
                ImeMicButton(
                    recordingState = recordingState,
                    isAgentMode = isAgentMode,
                    isVoiceGated = isVoiceGated,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording,
                    size = 58.dp
                )

                Spacer(modifier = Modifier.width(32.dp))

                // Right: Backspace Button (Matching size: 60dp x 50dp)
                ImeBackspaceButton(
                    onBackspace = onBackspace,
                    onBackspaceSelect = onBackspaceSelect,
                    onBackspaceDeleteSelected = onBackspaceDeleteSelected,
                    onBackspaceCancelSelect = onBackspaceCancelSelect,
                    modifier = Modifier
                        .size(width = 60.dp, height = 50.dp)
                )

                Spacer(modifier = Modifier.weight(1f))
            }

            // Row 2: Utility row (123 on left, expanded Spacebar at 50% center, compact .,?! and Enter on right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Left Wing: Numbers & Symbols switch (123)
                Box(
                    modifier = Modifier.weight(1.0f)
                ) {
                    ImeKeyButton(
                        text = "123",
                        onClick = { onSwitchMode(KeyboardPanelMode.NUMBERS) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        fontSize = 15.dp,
                        contentDescriptionText = "Numbers and symbols"
                    )
                }

                // Dead Center: Expanded Spacebar with swipe cursor navigation (Weight 2.0f)
                ImeSpacebar(
                    onSpace = { onCommitText(" ") },
                    onMoveCursor = onMoveCursor,
                    modifier = Modifier
                        .weight(2.0f)
                        .height(48.dp)
                )

                // Right Wing: Compact .,?! and Enter buttons (Weight 1.0f - exactly matches Left Wing)
                Box(
                    modifier = Modifier.weight(1.0f)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ImeKeyButton(
                            text = ".,?!",
                            onClick = { onSwitchMode(KeyboardPanelMode.PUNCTUATION) },
                            modifier = Modifier
                                .weight(0.9f)
                                .height(48.dp),
                            fontSize = 14.dp,
                            contentDescriptionText = "Punctuation"
                        )

                        ImeActionKey(
                            editorInfo = editorInfo,
                            onPerformAction = onPerformAction,
                            modifier = Modifier
                                .weight(1.1f)
                                .height(48.dp)
                        )
                    }
                }
            }
        }
    }
}
