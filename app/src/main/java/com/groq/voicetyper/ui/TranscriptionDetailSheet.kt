package com.groq.voicetyper.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        containerColor = PanelElevated,
        contentColor = TextPrimary,
        tonalElevation = 4.dp,
        shape = FluenceShapes.Large,
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(32.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(TextPrimary.copy(alpha = 0.18f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            // Title
            Text(
                text = "Transcription",
                color = TextPrimary,
                style = FluenceTypography.headlineMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)
            )

            entry?.let { item ->
                // Full text
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                ) {
                    Text(
                        text = item.text,
                        color = TextPrimary,
                        style = FluenceTypography.bodyLarge
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Timestamp
                val sdf = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
                Text(
                    text = sdf.format(Date(item.timestamp)),
                    color = TextSecondary,
                    style = FluenceTypography.labelMedium.copy(fontFamily = GeistMonoFont),
                    modifier = Modifier.padding(horizontal = 24.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Action buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                        shape = FluenceShapes.Medium,
                        modifier = Modifier
                            .weight(1f)
                            .pressScale(copyInteractionSource)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "Copy", color = TextPrimary, style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.Bold))
                    }

                    if (!foreign) {
                        Button(
                            onClick = {
                                showDeleteConfirmation = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ButtonSubtle),
                            shape = FluenceShapes.Medium,
                            modifier = Modifier
                                .weight(1f)
                                .pressScale(deleteInteractionSource)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = Error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "Delete", color = Error, style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (showDeleteConfirmation) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirmation = false },
                        containerColor = DialogSurface,
                        titleContentColor = TextPrimary,
                        textContentColor = TextSecondary,
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
                                Text("Delete", color = ErrorText)
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showDeleteConfirmation = false }
                            ) {
                                Text("Cancel", color = TextSecondary)
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
                    CircularProgressIndicator(color = TextSecondary, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
