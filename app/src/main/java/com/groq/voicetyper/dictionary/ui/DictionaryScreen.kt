package com.groq.voicetyper.dictionary.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.autolearn.AutoLearnPreferences
import com.groq.voicetyper.autolearn.ui.PendingSuggestionsSection
import com.groq.voicetyper.dictionary.DictionaryPreferences
import com.groq.voicetyper.dictionary.DictionaryRepository
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.FluenceEmptyState
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DictionaryScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isEnabled by remember { mutableStateOf(DictionaryPreferences.isDictionaryEnabled(context)) }
    var isAutoLearnEnabled by remember { mutableStateOf(AutoLearnPreferences.isAutoLearnEnabled(context)) }
    val entries by DictionaryRepository.getAll(context).collectAsState(initial = emptyList())

    var showDialog by remember { mutableStateOf(false) }
    var entryToEdit by remember { mutableStateOf<CustomDictionaryEntry?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header — Monochrome chrome
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateBack,
                    modifier = Modifier
                        .size(48.dp)
                        .pressScale(remember { MutableInteractionSource() })
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = "Custom Dictionary",
                    color = TextPrimary,
                    style = FluenceTypography.headlineLarge,
                    modifier = Modifier.weight(1f)
                )

                // Switch matching Online/Offline transcription toggle style
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        isEnabled = checked
                        DictionaryPreferences.setDictionaryEnabled(context, checked)
                    },
                    modifier = Modifier.semantics { contentDescription = "Custom Dictionary" },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Panel,
                        checkedTrackColor = TextPrimary,
                        uncheckedThumbColor = TextPrimary,
                        uncheckedTrackColor = PanelElevated
                    )
                )
            }

            if (!isEnabled) {
                Surface(
                    color = PanelElevated,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
                ) {
                    Text(
                        text = "Custom Dictionary is currently paused. Replacements will not apply during transcription.",
                        color = TextSecondary,
                        style = FluenceTypography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Explicit Auto Learn Toggle & Status Banner
            Surface(
                color = PanelElevated,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto Learn Corrections",
                            color = TextPrimary,
                            style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isAutoLearnEnabled) "Observes edits after voice typing" else "Auto Learn paused",
                            color = TextSecondary,
                            style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.Normal)
                        )
                    }
                    Switch(
                        checked = isAutoLearnEnabled,
                        onCheckedChange = { checked ->
                            isAutoLearnEnabled = checked
                            AutoLearnPreferences.setAutoLearnEnabled(context, checked)
                        },
                        modifier = Modifier.semantics { contentDescription = "Auto Learn Corrections" },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Panel,
                            checkedTrackColor = TextPrimary,
                            uncheckedThumbColor = TextPrimary,
                            uncheckedTrackColor = Canvas
                        )
                    )
                }
            }

            // Render Auto Learn Pending Suggestions Section if suggestions exist
            PendingSuggestionsSection()

            Spacer(modifier = Modifier.height(4.dp))

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    FluenceEmptyState(
                        icon = Icons.Default.Book,
                        title = "No dictionary entries yet",
                        description = "Add custom words or phrases and Fluence will automatically replace them while you dictate.",
                        actionLabel = "Add your first word",
                        onAction = {
                            entryToEdit = null
                            showDialog = true
                        }
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = entries,
                        key = { it.id }
                    ) { entry ->
                        DictionaryEntryCard(
                            entry = entry,
                            modifier = Modifier.animateItemPlacement(),
                            onToggleEnabled = { enabled ->
                                scope.launch {
                                    DictionaryRepository.toggleEntryEnabled(context, entry, enabled)
                                }
                            },
                            onEdit = {
                                entryToEdit = entry
                                showDialog = true
                            },
                            onDelete = {
                                scope.launch {
                                    DictionaryRepository.deleteEntry(context, entry)
                                    Toast.makeText(context, "Entry deleted", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }

        // FAB: Monochrome surface
        FloatingActionButton(
            onClick = {
                entryToEdit = null
                showDialog = true
            },
            containerColor = PanelElevated,
            contentColor = TextPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .border(1.dp, OutlineSubtle, RoundedCornerShape(16.dp))
                .pressScale(remember { MutableInteractionSource() })
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add Entry",
                tint = TextPrimary
            )
        }
    }

    if (showDialog) {
        AddEditDictionaryDialog(
            entryToEdit = entryToEdit,
            onDismiss = {
                showDialog = false
                entryToEdit = null
            },
            onSave = { spoken, replacement ->
                scope.launch {
                    DictionaryRepository.saveEntry(
                        context = context,
                        spokenText = spoken,
                        replacementText = replacement,
                        isEnabled = entryToEdit?.isEnabled ?: true,
                        id = entryToEdit?.id ?: 0L
                    )
                    showDialog = false
                    entryToEdit = null
                }
            }
        )
    }
}

@Composable
private fun DictionaryEntryCard(
    entry: CustomDictionaryEntry,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 3.dp,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp))
            .clickable { onEdit() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.spokenText,
                        color = if (entry.isEnabled) TextPrimary else TextDisabled,
                        style = FluenceTypography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = " \u2192 ",
                        color = TextTertiary,
                        style = FluenceTypography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = entry.replacementText,
                        color = if (entry.isEnabled) TextPrimary else TextDisabled,
                        style = FluenceTypography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Card item Switch matching Online/Offline toggle colors
            Switch(
                checked = entry.isEnabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.semantics { contentDescription = "Enable ${entry.spokenText}" },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Panel,
                    checkedTrackColor = TextPrimary,
                    uncheckedThumbColor = TextPrimary,
                    uncheckedTrackColor = PanelElevated
                )
            )

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Entry",
                    tint = TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun AddEditDictionaryDialog(
    entryToEdit: CustomDictionaryEntry?,
    onDismiss: () -> Unit,
    onSave: (spoken: String, replacement: String) -> Unit
) {
    var spokenText by remember { mutableStateOf(entryToEdit?.spokenText ?: "") }
    var replacementText by remember { mutableStateOf(entryToEdit?.replacementText ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogSurface,
        title = {
            Text(
                text = if (entryToEdit == null) "Add Dictionary Entry" else "Edit Dictionary Entry",
                color = TextPrimary,
                style = FluenceTypography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = spokenText,
                    onValueChange = {
                        spokenText = it
                        errorMessage = null
                    },
                    label = { Text("Spoken Phrase (Input)") },
                    placeholder = { Text("e.g. fluence") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = InputBg,
                        unfocusedContainerColor = InputBg,
                        focusedBorderColor = TextPrimary,
                        unfocusedBorderColor = OutlineSubtle,
                        focusedLabelColor = TextPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = replacementText,
                    onValueChange = {
                        replacementText = it
                        errorMessage = null
                    },
                    label = { Text("Replace With (Output)") },
                    placeholder = { Text("e.g. Fluence") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = InputBg,
                        unfocusedContainerColor = InputBg,
                        focusedBorderColor = TextPrimary,
                        unfocusedBorderColor = OutlineSubtle,
                        focusedLabelColor = TextPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = Error,
                        style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.Normal)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (spokenText.isBlank()) {
                        errorMessage = "Spoken phrase cannot be empty"
                    } else if (replacementText.isBlank()) {
                        errorMessage = "Replacement text cannot be empty"
                    } else {
                        onSave(spokenText.trim(), replacementText.trim())
                    }
                }
            ) {
                Text("Save", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
