package com.groq.voicetyper.ime.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.RecordingState
import com.groq.voicetyper.ime.KeyboardPanelMode

/**
 * Full-width Secondary Punctuation Panel:
 * - Row 0: Header with dismiss control
 * - Row 1: Primary sentence punctuation (10 keys)
 * - Row 2: Secondary symbols & operations (10 keys)
 * - Row 3: Brackets & technical symbols (10 keys)
 * - Row 4: Symmetrically centered bottom bar:
 *          [ABC] [Space] | (( [ MIC ] )) | [Delete] [Enter]
 */
@Composable
fun PunctuationKeyboardView(
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
    onCommitPunctuation: (String) -> Unit,
    onCommitText: (String) -> Unit = {},
    onPerformAction: () -> Unit,
    onSwitchMode: (KeyboardPanelMode) -> Unit,
    onHideKeyboard: () -> Unit = {},
    onMoveCursor: (Int) -> Unit = {},
    onBoundsChanged: (android.graphics.Rect) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val row1 = listOf(".", ",", "?", "!", ":", ";", "\"", "'", "(", ")")
    val row2 = listOf("—", "–", "…", "@", "#", "&", "_", "/", "\\", "|")
    val row3 = listOf("[", "]", "{", "}", "<", ">", "«", "»", "“", "”")

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
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Row 1: Primary sentence punctuation (48dp height touch target)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row1.forEach { sym ->
                    ImeKeyButton(
                        text = sym,
                        onClick = { onCommitPunctuation(sym) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        fontSize = 18.dp
                    )
                }
            }

            // Row 2: Secondary symbols & operations (48dp height touch target)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row2.forEach { sym ->
                    ImeKeyButton(
                        text = sym,
                        onClick = { onCommitPunctuation(sym) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        fontSize = 17.dp
                    )
                }
            }

            // Row 3: Brackets & technical symbols (48dp height touch target)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                row3.forEach { sym ->
                    ImeKeyButton(
                        text = sym,
                        onClick = { onCommitPunctuation(sym) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        fontSize = 16.dp
                    )
                }
            }

            // Row 4: Symmetrically centered bottom bar (Left: 1.1 + 2.2 = 3.3, Right: 1.1 + 2.2 = 3.3)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ImeKeyButton(
                    text = "ABC",
                    onClick = { onSwitchMode(KeyboardPanelMode.MAIN) },
                    modifier = Modifier
                        .weight(1.1f)
                        .height(48.dp),
                    fontSize = 14.dp,
                    contentDescriptionText = "Return to dictation"
                )

                ImeSpacebar(
                    onSpace = { onCommitText(" ") },
                    onMoveCursor = onMoveCursor,
                    modifier = Modifier
                        .weight(2.2f)
                        .height(48.dp)
                )

                // Dead Center Mic Button (50dp size)
                ImeMicButton(
                    recordingState = recordingState,
                    isAgentMode = isAgentMode,
                    isVoiceGated = isVoiceGated,
                    onStartRecording = onStartRecording,
                    onStopRecording = onStopRecording,
                    size = 50.dp
                )

                ImeBackspaceButton(
                    onBackspace = onBackspace,
                    onBackspaceSelect = onBackspaceSelect,
                    onBackspaceDeleteSelected = onBackspaceDeleteSelected,
                    onBackspaceCancelSelect = onBackspaceCancelSelect,
                    modifier = Modifier
                        .weight(1.1f)
                        .height(48.dp)
                )

                ImeActionKey(
                    editorInfo = editorInfo,
                    onPerformAction = onPerformAction,
                    modifier = Modifier
                        .weight(2.2f)
                        .height(48.dp)
                )
            }
        }
    }
}
