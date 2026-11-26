package com.groq.voicetyper.ui

import com.groq.voicetyper.FeedbackBus
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.groq.voicetyper.ui.icons.FluenceIcons
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.history.HistoryRepository
import com.groq.voicetyper.history.TranscriptionEntry
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.GeistMonoFont
import com.groq.voicetyper.theme.PrecisionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionDetailSheet(
    entryId: Long,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { HistoryRepository.init(context); HistoryRepository }
    var entry by remember { mutableStateOf<TranscriptionEntry?>(null) }

    LaunchedEffect(entryId) {
        withContext(Dispatchers.IO) {
            repository.getById(entryId).collect { item ->
                entry = item
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var showDeleteConfirmation by remember { mutableStateOf(false) }

    FluenceBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        sheetState = sheetState
    ) {
            // Title
            Text(
                text = "Transcription",
                color = colors.textPrimary,
                style = FluenceTypography.headlineMedium,
                modifier = Modifier.padding(start = FluenceSpacing.Lg, end = FluenceSpacing.Lg, top = FluenceSpacing.Xs, bottom = FluenceSpacing.Md)
            )

            entry?.let { item ->
                // Full text
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = FluenceSpacing.Lg)
                ) {
                    Text(
                        text = item.text,
                        color = colors.textPrimary,
                        style = FluenceTypography.bodyLarge
                    )
                }

                Spacer(modifier = Modifier.height(FluenceSpacing.Base))

                // Timestamp
                val sdf = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
                Text(
                    text = sdf.format(Date(item.timestamp)),
                    color = colors.textSecondary,
                    style = FluenceTypography.labelMedium.copy(fontFamily = GeistMonoFont),
                    modifier = Modifier.padding(horizontal = FluenceSpacing.Lg)
                )

                Spacer(modifier = Modifier.height(FluenceSpacing.Base))

                // Action buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FluenceSpacing.Lg),
                    horizontalArrangement = Arrangement.spacedBy(FluenceSpacing.Md)
                ) {
                    val copyInteractionSource = remember { MutableInteractionSource() }
                    val shareInteractionSource = remember { MutableInteractionSource() }
                    val deleteInteractionSource = remember { MutableInteractionSource() }
                    // §29 #3b: rows synced by another account are read-only.
                    val foreign = com.groq.voicetyper.sync.SyncAccounts.isForeign(item.syncAccount)

                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Transcription", item.text)
                            clipboard.setPrimaryClip(clip)
                            FeedbackBus.show("Copied to clipboard")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.buttonSecondary),
                        shape = FluenceShapes.Medium,
                        modifier = Modifier
                            .weight(1f)
                            .pressScale(copyInteractionSource)
                    ) {
                        Icon(
                            imageVector = FluenceIcons.Copy,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                        Text(text = "Copy", color = colors.textPrimary, style = FluenceTypography.labelLarge)
                    }

                    Button(
                        onClick = { shareTranscript(context, item.text) },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.buttonSecondary),
                        shape = FluenceShapes.Medium,
                        modifier = Modifier
                            .weight(1f)
                            .pressScale(shareInteractionSource)
                    ) {
                        Icon(
                            imageVector = FluenceIcons.Share2,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                        Text(text = "Share", color = colors.textPrimary, style = FluenceTypography.labelLarge)
                    }

                    if (!foreign) {
                        Button(
                            onClick = {
                                showDeleteConfirmation = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.buttonSubtle),
                            shape = FluenceShapes.Medium,
                            modifier = Modifier
                                .weight(1f)
                                .pressScale(deleteInteractionSource)
                        ) {
                            Icon(
                                imageVector = FluenceIcons.Trash2,
                                contentDescription = null,
                                tint = colors.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                            Text(text = "Delete", color = colors.errorText, style = FluenceTypography.labelLarge)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(FluenceSpacing.Base))

                if (showDeleteConfirmation) {
                    FluenceConfirmDialog(
                        title = "Delete transcription",
                        message = "This action cannot be undone. Delete this transcription?",
                        confirmLabel = "Delete",
                        destructive = true,
                        onConfirm = {
                            coroutineScope.launch {
                                repository.delete(item)
                                showDeleteConfirmation = false
                                onDismiss()
                            }
                        },
                        onDismiss = { showDeleteConfirmation = false }
                    )
                }
            } ?: run {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = colors.textSecondary, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
    }
}
