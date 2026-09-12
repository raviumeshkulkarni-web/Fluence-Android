package com.groq.voicetyper.dictionary.ui

import com.groq.voicetyper.FeedbackBus
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.autolearn.AutoLearnPreferences
import com.groq.voicetyper.autolearn.ui.PendingSuggestionsSection
import com.groq.voicetyper.dictionary.DictionaryPreferences
import com.groq.voicetyper.dictionary.DictionaryRepository
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.FluenceEmptyState
import com.groq.voicetyper.FluenceSectionHeader
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
import com.groq.voicetyper.ui.icons.FluenceIcons
import kotlinx.coroutines.launch

@Composable
fun DictionaryScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isEnabled by remember { mutableStateOf(DictionaryPreferences.isDictionaryEnabled(context)) }
    var isAutoLearnEnabled by remember { mutableStateOf(AutoLearnPreferences.isAutoLearnEnabled(context)) }
    val entries by remember(context) { DictionaryRepository.getAll(context) }.collectAsState(initial = emptyList())
    // Quarantined rows are sync bookkeeping (latched collisions) with no in-app
    // resolution flow; they are never applied, so hide them from the user list.
    val visibleEntries = entries.filter { it.quarantineReason == null }

    var showDialog by remember { mutableStateOf(false) }
    var entryToEdit by remember { mutableStateOf<CustomDictionaryEntry?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header (History rhythm): back + title + subtitle. The enable
            // toggle lives in Correction Learning below, not in the chrome.
            SettingsTopBar(
                title = "Custom Dictionary",
                onBack = onNavigateBack,
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )

            Text(
                text = "Correct specific words or phrases automatically after transcription",
                color = colors.textSecondary,
                style = FluenceTypography.bodySmall,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = FluenceSpacing.Base,
                        end = FluenceSpacing.Base,
                        bottom = FluenceSpacing.Sm
                    )
            )

            if (!isEnabled) {
                Surface(
                    color = colors.panelElevated,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .border(1.dp, colors.outlineSubtle, RoundedCornerShape(12.dp))
                ) {
                    Text(
                        text = "Custom Dictionary is currently paused. Replacements will not apply during transcription.",
                        color = colors.textSecondary,
                        style = FluenceTypography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Card fills the remaining viewport (Windows parity): Correction
            // Learning rows, Word Corrections table rows, then Suggested
            // Corrections — no per-row cards, dividers do the separating.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = FluenceSpacing.Base)
                    .background(colors.cardSurface, FluenceShapes.Medium)
            ) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    item {
                        FluenceSectionHeader(label = "CORRECTION LEARNING")
                    }
                    item {
                        LearningRow(
                            title = "Dictionary Enabled",
                            description = "Replacements apply during transcription",
                            checked = isEnabled,
                            toggleLabel = "Dictionary enabled",
                            onCheckedChange = { checked ->
                                isEnabled = checked
                                DictionaryPreferences.setDictionaryEnabled(context, checked)
                            }
                        )
                    }
                    item {
                        HorizontalDivider(
                            color = colors.outlineSubtle,
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
                        )
                    }
                    item {
                        LearningRow(
                            title = "Auto-Learn Corrections",
                            description = "Suggest transcription corrections based on detected patterns",
                            checked = isAutoLearnEnabled,
                            toggleLabel = "Auto Learn Corrections",
                            onCheckedChange = { checked ->
                                isAutoLearnEnabled = checked
                                AutoLearnPreferences.setAutoLearnEnabled(context, checked)
                            }
                        )
                    }
                    item {
                        FluenceSectionHeader(
                            label = "WORD CORRECTIONS",
                            actionLabel = "+ Add",
                            onAction = {
                                entryToEdit = null
                                showDialog = true
                            }
                        )
                    }
                    if (visibleEntries.isEmpty()) {
                        item {
                            FluenceEmptyState(
                                icon = FluenceIcons.BookOpen,
                                title = "No dictionary entries yet",
                                description = "Add corrections for words that are often misheard during transcription",
                                actionLabel = "Add your first word",
                                onAction = {
                                    entryToEdit = null
                                    showDialog = true
                                },
                                modifier = Modifier.padding(vertical = FluenceSpacing.Xxl)
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = visibleEntries,
                            key = { _, entry -> entry.id }
                        ) { index, entry ->
                            DictionaryEntryRow(
                                entry = entry,
                                onEdit = {
                                    entryToEdit = entry
                                    showDialog = true
                                },
                                onDelete = {
                                    scope.launch {
                                        DictionaryRepository.deleteEntry(context, entry)
                                        FeedbackBus.show("Entry deleted")
                                    }
                                }
                            )
                            if (index < visibleEntries.lastIndex) {
                                HorizontalDivider(
                                    color = colors.outlineSubtle,
                                    thickness = 1.dp,
                                    modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
                                )
                            }
                        }
                    }
                    item {
                        FluenceSectionHeader(label = "SUGGESTED CORRECTIONS")
                    }
                    item {
                        PendingSuggestionsSection()
                    }
                    item {
                        Spacer(modifier = Modifier.height(FluenceSpacing.Md))
                    }
                }
            }
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
                    val result = DictionaryRepository.saveEntry(
                        context = context,
                        spokenText = spoken,
                        replacementText = replacement,
                        isEnabled = true,
                        id = entryToEdit?.id ?: 0L
                    )
                    if (result == DictionaryRepository.SaveResult.PRESERVED) {
                        // Phrase already exists under another entry: keep the dialog
                        // open so the user's edit is not silently lost.
                        FeedbackBus.show("An entry with this phrase already exists")
                    } else {
                        showDialog = false
                        entryToEdit = null
                    }
                }
            }
        )
    }
}

@Composable
private fun LearningRow(
    title: String,
    description: String,
    checked: Boolean,
    toggleLabel: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = colors.textSecondary,
                style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.Normal)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = toggleLabel },
            colors = SwitchDefaults.colors(
                checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                checkedTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                uncheckedThumbColor = colors.textPrimary,
                uncheckedTrackColor = colors.panel
            )
        )
    }
}

@Composable
private fun DictionaryEntryRow(
    entry: CustomDictionaryEntry,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Edit entry", onClick = onEdit)
            .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.spokenText,
                color = colors.textPrimary,
                style = FluenceTypography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = " → ",
                color = colors.textTertiary,
                style = FluenceTypography.bodyMedium
            )
            Text(
                text = entry.replacementText,
                color = colors.textPrimary,
                style = FluenceTypography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }

        TextButton(
            onClick = onDelete,
            contentPadding = PaddingValues(horizontal = FluenceSpacing.Sm),
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text("Delete", color = colors.error, style = FluenceTypography.labelMedium)
        }
    }
}

@Composable
private fun AddEditDictionaryDialog(
    entryToEdit: CustomDictionaryEntry?,
    onDismiss: () -> Unit,
    onSave: (spoken: String, replacement: String) -> Unit
) {
    val colors = PrecisionTheme.colors
    var spokenText by remember { mutableStateOf(entryToEdit?.spokenText ?: "") }
    var replacementText by remember { mutableStateOf(entryToEdit?.replacementText ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.dialog,
        title = {
            Text(
                text = if (entryToEdit == null) "Add Dictionary Entry" else "Edit Dictionary Entry",
                color = colors.textPrimary,
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
                    label = { Text("Spoken Word/Phrase") },
                    placeholder = { Text("e.g. fluence") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.inputBg,
                        unfocusedContainerColor = colors.inputBg,
                        focusedBorderColor = if (colors.isLight) colors.brandCyan.copy(alpha = 0.55f) else colors.textSecondary,
                        unfocusedBorderColor = colors.outlineSubtle,
                        focusedLabelColor = colors.textPrimary,
                        unfocusedLabelColor = colors.textSecondary,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = colors.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = replacementText,
                    onValueChange = {
                        replacementText = it
                        errorMessage = null
                    },
                    label = { Text("Corrected Form") },
                    placeholder = { Text("e.g. Fluence") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.inputBg,
                        unfocusedContainerColor = colors.inputBg,
                        focusedBorderColor = if (colors.isLight) colors.brandCyan.copy(alpha = 0.55f) else colors.textSecondary,
                        unfocusedBorderColor = colors.outlineSubtle,
                        focusedLabelColor = colors.textPrimary,
                        unfocusedLabelColor = colors.textSecondary,
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        cursorColor = colors.textPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = colors.error,
                        style = FluenceTypography.labelMedium
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
                Text("Save", color = colors.textPrimary, style = FluenceTypography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.textSecondary, style = FluenceTypography.labelLarge)
            }
        }
    )
}
