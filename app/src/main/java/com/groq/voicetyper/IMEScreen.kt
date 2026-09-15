package com.groq.voicetyper

import android.view.inputmethod.EditorInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import com.groq.voicetyper.ime.KeyboardPanelMode
import com.groq.voicetyper.ime.ui.MainKeyboardView
import com.groq.voicetyper.ime.ui.NumbersKeyboardView
import com.groq.voicetyper.ime.ui.PunctuationKeyboardView
import com.groq.voicetyper.theme.AgentTeal
import com.groq.voicetyper.theme.AgentTealSoft
import com.groq.voicetyper.theme.BrandAmethyst
import com.groq.voicetyper.theme.Error
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.ImeStatusBg
import com.groq.voicetyper.theme.TextDisabled
import com.groq.voicetyper.theme.TextPrimary

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
    isOfflineMode: Boolean = false,
    isTargetExcluded: Boolean = false,
    isVoiceGated: Boolean = false,
    editorInfo: EditorInfo? = null,
    panelMode: KeyboardPanelMode = KeyboardPanelMode.MAIN,
    onPanelModeChange: (KeyboardPanelMode) -> Unit = {},
    onCommitPunctuation: (String) -> Unit = {},
    onCommitText: (String) -> Unit = {},
    onPerformAction: () -> Unit = {},
    onHideKeyboard: () -> Unit = {},
    onMoveCursor: (Int) -> Unit = {},
    onBoundsChanged: (android.graphics.Rect) -> Unit = {}
) {
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

    val isMicDisabled = isVoiceGated ||
        (!isOfflineReady && apiKey.isNullOrBlank()) ||
        isTargetExcluded

    val statusTextColor = when {
        isVoiceGated -> TextDisabled
        recordingState == RecordingState.RECORDING -> if (isAgentMode) AgentTeal else BrandAmethyst
        recordingState == RecordingState.TRANSCRIBING -> if (isAgentMode) AgentTealSoft else BrandAmethyst.copy(alpha = 0.7f)
        recordingState == RecordingState.ERROR -> Error
        else -> TextPrimary
    }

    val statusText = when {
        isVoiceGated -> "Voice input paused (sensitive field)"
        recordingState == RecordingState.IDLE -> if (isOfflineReady && isOfflineMode) "Ready (offline)" else if (apiKey.isNullOrBlank()) "API key required" else "Ready"
        recordingState == RecordingState.RECORDING -> {
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
        recordingState == RecordingState.TRANSCRIBING -> "Transcribing…"
        recordingState == RecordingState.ERROR -> errorMessage ?: "ERROR"
        else -> "Ready"
    }

    // Centered layout with floating status badge above active panel
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(top = 8.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Floating Status Badge
        AnimatedVisibility(visible = isVoiceGated || recordingState != RecordingState.IDLE || errorMessage != null) {
            Text(
                text = statusText,
                color = statusTextColor,
                style = FluenceTypography.labelMedium,
                modifier = Modifier
                    .background(ImeStatusBg, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Active Keyboard Panel (Instant zero-latency switch for 60/120fps performance without window inset jitter)
        when (panelMode) {
                KeyboardPanelMode.MAIN -> {
                    MainKeyboardView(
                        recordingState = recordingState,
                        isAgentMode = isAgentMode,
                        isVoiceGated = isMicDisabled,
                        editorInfo = editorInfo,
                        onStartRecording = onStartRecording,
                        onStopRecording = onStopRecording,
                        onBackspace = onBackspace,
                        onBackspaceSelect = onBackspaceSelect,
                        onBackspaceDeleteSelected = onBackspaceDeleteSelected,
                        onBackspaceCancelSelect = onBackspaceCancelSelect,
                        onCommitText = onCommitText,
                        onPerformAction = onPerformAction,
                        onSwitchKeyboard = onSwitchKeyboard,
                        onSwitchMode = onPanelModeChange,
                        onHideKeyboard = onHideKeyboard,
                        onMoveCursor = onMoveCursor,
                        onBoundsChanged = onBoundsChanged
                    )
                }
                KeyboardPanelMode.PUNCTUATION -> {
                    PunctuationKeyboardView(
                        recordingState = recordingState,
                        isAgentMode = isAgentMode,
                        isVoiceGated = isMicDisabled,
                        editorInfo = editorInfo,
                        onStartRecording = onStartRecording,
                        onStopRecording = onStopRecording,
                        onBackspace = onBackspace,
                        onBackspaceSelect = onBackspaceSelect,
                        onBackspaceDeleteSelected = onBackspaceDeleteSelected,
                        onBackspaceCancelSelect = onBackspaceCancelSelect,
                        onCommitPunctuation = onCommitPunctuation,
                        onCommitText = onCommitText,
                        onPerformAction = onPerformAction,
                        onSwitchMode = onPanelModeChange,
                        onHideKeyboard = onHideKeyboard,
                        onMoveCursor = onMoveCursor,
                        onBoundsChanged = onBoundsChanged
                    )
                }
                KeyboardPanelMode.NUMBERS -> {
                    NumbersKeyboardView(
                        recordingState = recordingState,
                        isAgentMode = isAgentMode,
                        isVoiceGated = isMicDisabled,
                        editorInfo = editorInfo,
                        onStartRecording = onStartRecording,
                        onStopRecording = onStopRecording,
                        onBackspace = onBackspace,
                        onBackspaceSelect = onBackspaceSelect,
                        onBackspaceDeleteSelected = onBackspaceDeleteSelected,
                        onBackspaceCancelSelect = onBackspaceCancelSelect,
                        onCommitChar = onCommitText,
                        onPerformAction = onPerformAction,
                        onSwitchMode = onPanelModeChange,
                        onHideKeyboard = onHideKeyboard,
                        onMoveCursor = onMoveCursor,
                        onBoundsChanged = onBoundsChanged
                    )
                }
            }
    }
}
