package com.groq.voicetyper.snippets.ui

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
import androidx.compose.foundation.verticalScroll
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
import com.groq.voicetyper.FeedbackBus
import com.groq.voicetyper.FluenceEmptyState
import com.groq.voicetyper.FluenceSectionHeader
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.snippets.Snippet
import com.groq.voicetyper.snippets.SnippetPreferences
import com.groq.voicetyper.theme.*
import com.groq.voicetyper.ui.icons.FluenceIcons

@Composable
fun SnippetsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(SnippetPreferences.isSnippetsEnabled(context)) }
    val snippets by remember(context) { SnippetPreferences.observeSnippets(context) }
        .collectAsState(initial = emptyList())

    var showDialog by remember { mutableStateOf(false) }
    var snippetToEdit by remember { mutableStateOf<Snippet?>(null) }

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

    // Prune selections of deleted snippets so multiselect can't stick on
    // with a phantom count.
    androidx.compose.runtime.LaunchedEffect(snippets) {
        val valid = snippets.map { it.id }.toSet()
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
        Column(modifier = Modifier.fillMaxSize()) {
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
                            val all = snippets.map { it.id }.toSet()
                            selectedIds = if (selectedIds == all) emptySet() else all
                        },
                        contentPadding = PaddingValues(horizontal = FluenceSpacing.Sm),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(
                            if (selectedIds == snippets.map { it.id }.toSet()) "Deselect all" else "Select all",
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
                    title = "Text Expansion",
                    onBack = onNavigateBack,
                    modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
                )
            }

            Text(
                text = "Say a short trigger like my email and Fluence types your full text instead. Triggers work everywhere you dictate.",
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
                    shape = FluenceShapes.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.N6)
                        .border(1.dp, colors.outlineSubtle, FluenceShapes.Medium)
                ) {
                    Text(
                        text = "Text Expansion is paused. Your triggers won't expand until you turn it back on.",
                        color = colors.textSecondary,
                        style = FluenceTypography.bodySmall,
                        modifier = Modifier.padding(FluenceSpacing.Md)
                    )
                }
            }

            // Card fills the remaining viewport (Windows parity): the enable
            // row, then the My Snippets table rows — no per-row cards.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = FluenceSpacing.Base)
                    .background(colors.cardSurface, FluenceShapes.Medium)
            ) {
                val switchInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(switchInteraction)
                        .toggleable(
                            value = isEnabled,
                            interactionSource = switchInteraction,
                            indication = androidx.compose.foundation.LocalIndication.current,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                isEnabled = checked
                                SnippetPreferences.setSnippetsEnabled(context, checked)
                            }
                        )
                        .padding(horizontal = FluenceSpacing.Base)
                        .padding(top = FluenceSpacing.Sm, bottom = FluenceSpacing.Lg)
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Text Expansion",
                            color = colors.textPrimary,
                            style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Dictate a short trigger and Fluence pastes your expansion text instead",
                            color = colors.textSecondary,
                            style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.Normal)
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = null,
                        modifier = Modifier.semantics {
                            contentDescription = "Enable text expansion"
                            stateDescription = if (isEnabled) "On" else "Off"
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                            checkedTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                            uncheckedThumbColor = colors.textPrimary,
                            uncheckedTrackColor = colors.panel
                        )
                    )
                }
                HorizontalDivider(
                    color = colors.outlineSubtle,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
                )
                FluenceSectionHeader(
                    label = "MY SNIPPETS",
                    actionLabel = "+ Add",
                    onAction = {
                        snippetToEdit = null
                        showDialog = true
                    }
                )
                if (snippets.isEmpty()) {
                    FluenceEmptyState(
                        icon = FluenceIcons.Zap,
                        title = "No snippets yet",
                        description = "Add a trigger phrase and its expansion, e.g. \"my email\" becomes your full email address",
                        actionLabel = "Add your first snippet",
                        onAction = {
                            snippetToEdit = null
                            showDialog = true
                        },
                        modifier = Modifier.padding(vertical = FluenceSpacing.Xxl)
                    )
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        itemsIndexed(
                            items = snippets,
                            key = { _, snippet -> snippet.id }
                        ) { index, snippet ->
                            SnippetRow(
                                snippet = snippet,
                                isSelected = snippet.id in selectedIds,
                                isMultiSelect = isMultiSelect,
                                onToggleSelect = {
                                    selectedIds = if (snippet.id in selectedIds) {
                                        selectedIds - snippet.id
                                    } else {
                                        selectedIds + snippet.id
                                    }
                                },
                                onEdit = {
                                    snippetToEdit = snippet
                                    showDialog = true
                                },
                                onDelete = {
                                    pendingDeleteIds = listOf(snippet.id)
                                    showDeleteDialog = true
                                }
                            )
                            if (index < snippets.lastIndex) {
                                HorizontalDivider(
                                    color = colors.outlineSubtle,
                                    thickness = 1.dp,
                                    modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
                                )
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(FluenceSpacing.Md))
                        }
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        val count = pendingDeleteIds.size
        val snippetName = if (count == 1) {
            snippets.find { it.id == pendingDeleteIds.first() }?.trigger
        } else null
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = colors.dialog,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            title = { Text("Delete snippet${if (count > 1) "s" else ""}") },
            text = {
                Text(
                    if (count == 1 && snippetName != null) "This action cannot be undone. Delete \"$snippetName\"?"
                    else "This action cannot be undone. Delete ${if (count > 1) "$count snippets" else "this snippet"}?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteIds.forEach { id ->
                            SnippetPreferences.deleteSnippet(context, id)
                        }
                        selectedIds = selectedIds - pendingDeleteIds.toSet()
                        showDeleteDialog = false
                        FeedbackBus.show(if (count > 1) "Deleted $count snippets" else "Snippet deleted")
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
        AddEditSnippetDialog(
            snippetToEdit = snippetToEdit,
            onDismiss = {
                showDialog = false
                snippetToEdit = null
            },
            onSave = { trigger, expansion ->
                val result = SnippetPreferences.saveSnippet(
                    context = context,
                    trigger = trigger,
                    expansion = expansion,
                    id = snippetToEdit?.id ?: 0L
                )
                if (result == SnippetPreferences.SaveResult.PRESERVED) {
                    "A snippet with this trigger already exists"
                } else {
                    showDialog = false
                    snippetToEdit = null
                    null
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SnippetRow(
    snippet: Snippet,
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
        Column(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onClickLabel = if (isMultiSelect) "Toggle selection" else "Edit snippet",
                    onLongClickLabel = "Select snippet",
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
                )
        ) {
            Text(
                text = snippet.trigger,
                color = colors.textPrimary,
                style = FluenceTypography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "→ ${snippet.expansion}",
                color = colors.textSecondary,
                style = FluenceTypography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
private fun AddEditSnippetDialog(
    snippetToEdit: Snippet?,
    onDismiss: () -> Unit,
    onSave: (trigger: String, expansion: String) -> String?
) {
    val colors = PrecisionTheme.colors
    var triggerText by remember { mutableStateOf(snippetToEdit?.trigger ?: "") }
    var expansionText by remember { mutableStateOf(snippetToEdit?.expansion ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.dialog,
        modifier = Modifier.imePadding(),
        title = {
            Text(
                text = if (snippetToEdit == null) "Add Snippet" else "Edit Snippet",
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
                    value = triggerText,
                    onValueChange = {
                        triggerText = it
                        errorMessage = null
                    },
                    label = { Text("Spoken Trigger") },
                    placeholder = { Text("e.g. my linkedin") },
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
                    value = expansionText,
                    onValueChange = {
                        expansionText = it
                        errorMessage = null
                    },
                    label = { Text("Expansion Text") },
                    placeholder = { Text("e.g. https://linkedin.com/in/…") },
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
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
                    val trimmedTrigger = triggerText.trim()
                    val trimmedExpansion = expansionText.trim()
                    when {
                        trimmedTrigger.isEmpty() -> errorMessage = "Trigger cannot be empty"
                        trimmedExpansion.isEmpty() -> errorMessage = "Expansion cannot be empty"
                        trimmedTrigger.length > SnippetPreferences.MAX_TRIGGER_LENGTH ->
                            errorMessage = "Trigger is too long (max ${SnippetPreferences.MAX_TRIGGER_LENGTH} characters)"
                        trimmedExpansion.length > SnippetPreferences.MAX_EXPANSION_LENGTH ->
                            errorMessage = "Expansion is too long (max ${SnippetPreferences.MAX_EXPANSION_LENGTH} characters)"
                        else -> {
                            val error = onSave(trimmedTrigger, trimmedExpansion)
                            if (error != null) errorMessage = error
                        }
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
