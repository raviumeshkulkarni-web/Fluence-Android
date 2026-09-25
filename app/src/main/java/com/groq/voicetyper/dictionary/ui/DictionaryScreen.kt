package com.groq.voicetyper.dictionary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.autolearn.AutoLearnPreferences
import com.groq.voicetyper.autolearn.ui.PendingSuggestionsSection
import com.groq.voicetyper.dictionary.DictionaryPreferences
import com.groq.voicetyper.dictionary.DictionaryRepository
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.FeedbackBus
import com.groq.voicetyper.FluenceEmptyState
import com.groq.voicetyper.FluenceSectionHeader
import com.groq.voicetyper.SettingsJointCard
import com.groq.voicetyper.SettingsSectionHeader
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

    val longSetSaver = Saver<MutableState<Set<Long>>, ArrayList<Long>>(
        save = { arrayListOf<Long>().apply { addAll(it.value) } },
        restore = { mutableStateOf(it.toSet()) }
    )
    val longListSaver = Saver<MutableState<List<Long>>, ArrayList<Long>>(
        save = { ArrayList(it.value) },
        restore = { mutableStateOf(it.toList()) }
    )

    var selectedIds by rememberSaveable(saver = longSetSaver) { mutableStateOf(setOf<Long>()) }
    val isMultiSelect = selectedIds.isNotEmpty()
    var pendingDeleteIds by rememberSaveable(saver = longListSaver) { mutableStateOf<List<Long>>(emptyList()) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = isMultiSelect) {
        selectedIds = emptySet()
    }

    // Prune selections of deleted or quarantined entries so multiselect
    // can't stick on with a phantom count.
    androidx.compose.runtime.LaunchedEffect(visibleEntries) {
        val valid = visibleEntries.map { it.id }.toSet()
        if (!valid.containsAll(selectedIds)) {
            selectedIds = selectedIds.intersect(valid)
        }
    }

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
            if (isMultiSelect) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = FluenceSpacing.Base),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { selectedIds = emptySet() }, modifier = Modifier.size(48.dp)) {
                        Icon(FluenceIcons.X, "Exit selection", tint = colors.textPrimary, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(FluenceSpacing.Base))
                    Text(
                        text = "${selectedIds.size} selected",
                        color = colors.textPrimary,
                        style = FluenceTypography.headlineMedium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            val all = visibleEntries.map { it.id }.toSet()
                            selectedIds = if (selectedIds == all) emptySet() else all
                        },
                        contentPadding = PaddingValues(horizontal = FluenceSpacing.Sm),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(
                            if (selectedIds == visibleEntries.map { it.id }.toSet()) "Deselect all" else "Select all",
                            color = colors.textSecondary,
                            style = FluenceTypography.labelMedium
                        )
                    }
                    IconButton(
                        onClick = {
                            pendingDeleteIds = selectedIds.toList()
                            showDeleteDialog = true
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(FluenceIcons.Trash2, "Delete selected", tint = colors.error, modifier = Modifier.size(20.dp))
                    }
                }
            } else {
                SettingsTopBar(
                    title = "Custom Dictionary",
                    onBack = onNavigateBack,
                    modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
                )
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = FluenceSpacing.Base)
            ) {
                item {
                    Text(
                        text = "Teach Fluence the words it gets wrong: names, terms, spellings. Each one is fixed automatically right after you dictate.",
                        color = colors.textSecondary,
                        style = FluenceTypography.bodySmall,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = FluenceSpacing.Sm)
                    )
                }

                if (!isEnabled) {
                    item {
                        Surface(
                            color = colors.panelElevated,
                            shape = FluenceShapes.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = FluenceSpacing.Sm)
                                .border(1.dp, colors.outlineSubtle, FluenceShapes.Medium)
                        ) {
                            Text(
                                text = "Custom Dictionary is paused. Your replacements won't apply until you turn it back on.",
                                color = colors.textSecondary,
                                style = FluenceTypography.bodySmall,
                                modifier = Modifier.padding(FluenceSpacing.Md)
                            )
                        }
                    }
                }

                // Section 1: Correction Learning
                item {
                    SettingsSectionHeader(
                        title = "Correction Learning",
                        description = "Configure automatic learning and dictionary suggestions"
                    )
                }
                item {
                    SettingsJointCard {
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
                        HorizontalDivider(
                            color = colors.divider,
                            thickness = 1.dp
                        )
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
                }

                // Section 2: Word Corrections
                item {
                    SettingsSectionHeader(
                        title = "Word Corrections",
                        description = "Custom word replacements applied during transcription",
                        actionLabel = "+ Add",
                        onAction = {
                            entryToEdit = null
                            showDialog = true
                        }
                    )
                }
                item {
                    SettingsJointCard {
                        if (visibleEntries.isEmpty()) {
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
                        } else {
                            visibleEntries.forEachIndexed { index, entry ->
                                DictionaryEntryRow(
                                    entry = entry,
                                    isSelected = entry.id in selectedIds,
                                    isMultiSelect = isMultiSelect,
                                    onToggleSelect = {
                                        selectedIds = if (entry.id in selectedIds) {
                                            selectedIds - entry.id
                                        } else {
                                            selectedIds + entry.id
                                        }
                                    },
                                    onEdit = {
                                        entryToEdit = entry
                                        showDialog = true
                                    },
                                    onDelete = {
                                        pendingDeleteIds = listOf(entry.id)
                                        showDeleteDialog = true
                                    }
                                )
                                if (index < visibleEntries.lastIndex) {
                                    HorizontalDivider(
                                        color = colors.divider,
                                        thickness = 1.dp
                                    )
                                }
                            }
                        }
                    }
                }

                // Section 3: Suggested Corrections
                item {
                    SettingsSectionHeader(
                        title = "Suggested Corrections",
                        description = "Review and accept phrases detected from recent edits"
                    )
                }
                item {
                    SettingsJointCard {
                        PendingSuggestionsSection()
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(FluenceSpacing.Xl))
                }
            }
        }
    }

    if (showDeleteDialog) {
        val count = pendingDeleteIds.size
        val spoken = if (count == 1) {
            visibleEntries.find { it.id == pendingDeleteIds.first() }?.spokenText
        } else null
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = colors.dialog,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            title = { Text("Delete dictionary entr${if (count > 1) "ies" else "y"}") },
            text = {
                Text(
                    if (count == 1 && spoken != null) "This action cannot be undone. Delete \"$spoken\"?"
                    else "This action cannot be undone. Delete ${if (count > 1) "$count entries" else "this entry"}?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            pendingDeleteIds.forEach { id ->
                                DictionaryRepository.deleteById(context, id)
                            }
                            selectedIds = selectedIds - pendingDeleteIds.toSet()
                            showDeleteDialog = false
                            FeedbackBus.show(if (count > 1) "Deleted $count entries" else "Entry deleted")
                        }
                    }
                ) {
                    Text("Delete", color = colors.errorText, style = FluenceTypography.labelLarge)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = colors.textSecondary, style = FluenceTypography.labelLarge)
                }
            }
        )
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
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(interactionSource)
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
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
            onCheckedChange = null,
            modifier = Modifier.semantics {
                contentDescription = toggleLabel
                stateDescription = if (checked) "On" else "Off"
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                checkedTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                uncheckedThumbColor = colors.textPrimary,
                uncheckedTrackColor = colors.panel,
                uncheckedBorderColor = colors.outlineSubtle
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DictionaryEntryRow(
    entry: CustomDictionaryEntry,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val bgColor = if (isSelected) colors.textPrimary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .padding(start = FluenceSpacing.Base)
                    .size(18.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(colors.textPrimary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FluenceIcons.Check,
                    contentDescription = null,
                    tint = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.canvas,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onClickLabel = if (isMultiSelect) "Toggle selection" else "Edit entry",
                    onLongClickLabel = "Select entry",
                    onClick = {
                        if (isMultiSelect) {
                            onToggleSelect()
                        } else {
                            onEdit()
                        }
                    },
                    onLongClick = {
                        onToggleSelect()
                    }
                )
                .semantics {
                    if (isMultiSelect) {
                        role = Role.Checkbox
                        selected = isSelected
                        stateDescription = if (isSelected) "Selected" else "Not selected"
                    } else {
                        role = Role.Button
                    }
                }
                .padding(
                    start = if (isSelected) FluenceSpacing.Sm else FluenceSpacing.Base,
                    end = FluenceSpacing.Sm,
                    top = FluenceSpacing.Sm,
                    bottom = FluenceSpacing.Sm
                ),
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

        if (!isMultiSelect) {
            TextButton(
                onClick = onDelete,
                contentPadding = PaddingValues(horizontal = FluenceSpacing.Sm),
                modifier = Modifier
                    .padding(end = FluenceSpacing.Sm)
                    .heightIn(min = 48.dp)
            ) {
                Text("Delete", color = colors.error, style = FluenceTypography.labelMedium)
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
    val colors = PrecisionTheme.colors
    var spokenText by remember { mutableStateOf(entryToEdit?.spokenText ?: "") }
    var replacementText by remember { mutableStateOf(entryToEdit?.replacementText ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.dialog,
        modifier = Modifier.imePadding(),
        title = {
            Text(
                text = if (entryToEdit == null) "Add Dictionary Entry" else "Edit Dictionary Entry",
                color = colors.textPrimary,
                style = FluenceTypography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FluenceSpacing.Md)
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
                        focusedBorderColor = if (colors.isLight) colors.brandCyan else colors.textPrimary,
                        unfocusedBorderColor = colors.inputBorder,
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
                        focusedBorderColor = if (colors.isLight) colors.brandCyan else colors.textPrimary,
                        unfocusedBorderColor = colors.inputBorder,
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
