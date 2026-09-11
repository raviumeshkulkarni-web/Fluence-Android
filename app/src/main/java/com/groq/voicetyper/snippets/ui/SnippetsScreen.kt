package com.groq.voicetyper.snippets.ui

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
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(SnippetPreferences.isSnippetsEnabled(context)) }
    val snippets by remember(context) { SnippetPreferences.observeSnippets(context) }
        .collectAsState(initial = emptyList())

    var showDialog by remember { mutableStateOf(false) }
    var snippetToEdit by remember { mutableStateOf<Snippet?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header (History rhythm): back + title + subtitle. The enable
            // toggle lives in the section below, not in the chrome.
            SettingsTopBar(
                title = "Text Expansion",
                onBack = onNavigateBack,
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )

            Text(
                text = "Replace spoken trigger phrases with expansion text in every transcription",
                color = TextSecondary,
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
                    color = PanelElevated,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .border(1.dp, OutlineSubtle, RoundedCornerShape(12.dp))
                ) {
                    Text(
                        text = "Voice Snippets are currently paused. Expansions will not apply during transcription.",
                        color = TextSecondary,
                        style = FluenceTypography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Card fills the remaining viewport (Windows parity): the enable
            // row, then the My Snippets table rows — no per-row cards.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = FluenceSpacing.Base)
                    .background(CardSurface, FluenceShapes.Medium)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FluenceSpacing.Base)
                        .padding(top = FluenceSpacing.Sm, bottom = FluenceSpacing.Lg)
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Text Expansion",
                            color = TextPrimary,
                            style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Dictate a short trigger and Fluence pastes your expansion text instead",
                            color = TextSecondary,
                            style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.Normal)
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { checked ->
                            isEnabled = checked
                            SnippetPreferences.setSnippetsEnabled(context, checked)
                        },
                        modifier = Modifier.semantics { contentDescription = "Enable text expansion" },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Panel,
                            checkedTrackColor = TextPrimary,
                            uncheckedThumbColor = TextPrimary,
                            uncheckedTrackColor = Panel
                        )
                    )
                }
                HorizontalDivider(
                    color = OutlineSubtle,
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
                                onEdit = {
                                    snippetToEdit = snippet
                                    showDialog = true
                                },
                                onDelete = {
                                    SnippetPreferences.deleteSnippet(context, snippet.id)
                                    FeedbackBus.show("Snippet deleted")
                                }
                            )
                            if (index < snippets.lastIndex) {
                                HorizontalDivider(
                                    color = OutlineSubtle,
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

@Composable
private fun SnippetRow(
    snippet: Snippet,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = "Edit snippet",
                role = Role.Button,
                onClick = onEdit
            )
            .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = snippet.trigger,
                color = TextPrimary,
                style = FluenceTypography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "→ ${snippet.expansion}",
                color = TextSecondary,
                style = FluenceTypography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        TextButton(
            onClick = onDelete,
            contentPadding = PaddingValues(horizontal = FluenceSpacing.Sm),
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text("Delete", color = Error, style = FluenceTypography.labelMedium)
        }
    }
}

@Composable
private fun AddEditSnippetDialog(
    snippetToEdit: Snippet?,
    onDismiss: () -> Unit,
    onSave: (trigger: String, expansion: String) -> String?
) {
    var triggerText by remember { mutableStateOf(snippetToEdit?.trigger ?: "") }
    var expansionText by remember { mutableStateOf(snippetToEdit?.expansion ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogSurface,
        title = {
            Text(
                text = if (snippetToEdit == null) "Add Snippet" else "Edit Snippet",
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
                    value = triggerText,
                    onValueChange = {
                        triggerText = it
                        errorMessage = null
                    },
                    label = { Text("Spoken Trigger") },
                    placeholder = { Text("e.g. my linkedin") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = InputBg,
                        unfocusedContainerColor = InputBg,
                        focusedBorderColor = TextSecondary,
                        unfocusedBorderColor = OutlineSubtle,
                        focusedLabelColor = TextPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = TextPrimary
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
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = InputBg,
                        unfocusedContainerColor = InputBg,
                        focusedBorderColor = TextSecondary,
                        unfocusedBorderColor = OutlineSubtle,
                        focusedLabelColor = TextPrimary,
                        unfocusedLabelColor = TextSecondary,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = Error,
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
                Text("Save", color = TextPrimary, style = FluenceTypography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary, style = FluenceTypography.labelLarge)
            }
        }
    )
}
