package com.groq.voicetyper.ui

import com.groq.voicetyper.FeedbackBus
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.groq.voicetyper.ui.icons.FluenceIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.history.HistoryRepository
import com.groq.voicetyper.history.TranscriptionEntry
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = colors.panelElevated,
        contentColor = colors.textPrimary,
        tonalElevation = 4.dp,
        shape = FluenceShapes.Large,
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = FluenceSpacing.Md)
                    .width(32.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.textPrimary.copy(alpha = 0.18f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = FluenceSpacing.Sm)
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
                            Text(text = "Delete", color = colors.error, style = FluenceTypography.labelLarge)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(FluenceSpacing.Base))

                if (showDeleteConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirmation = false },
                        containerColor = colors.dialog,
                        titleContentColor = colors.textPrimary,
                        textContentColor = colors.textSecondary,
                        title = { Text("Delete transcription") },
                        text = { Text("This action cannot be undone. Delete this transcription?") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    coroutineScope.launch {
                                        repository.delete(item)
                                        showDeleteConfirmation = false
                                        onDismiss()
                                    }
                                }
                            ) {
                                Text("Delete", color = colors.errorText, style = FluenceTypography.labelLarge)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showDeleteConfirmation = false }
                            ) {
                                Text("Cancel", color = colors.textSecondary, style = FluenceTypography.labelLarge)
                            }
                        }
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
}
