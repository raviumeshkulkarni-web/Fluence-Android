package com.groq.voicetyper.snippets.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.FluenceEmptyState
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.snippets.Snippet
import com.groq.voicetyper.snippets.SnippetPreferences
import com.groq.voicetyper.theme.*

@Composable
fun SnippetsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(SnippetPreferences.isSnippetsEnabled(context)) }
    val snippets = remember { mutableStateListOf<Snippet>() }

    fun reload() {
        snippets.clear()
        snippets.addAll(SnippetPreferences.loadSnippets(context))
    }

    LaunchedEffect(Unit) { reload() }

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
            // Header — Monochrome chrome, matching the Dictionary screen
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
                    text = "Voice Snippets",
                    color = TextPrimary,
                    style = FluenceTypography.headlineLarge,
                    modifier = Modifier.weight(1f)
                )

                Switch(
                    checked = isEnabled,
                    onCheckedChange = { checked ->
                        isEnabled = checked
                        SnippetPreferences.setSnippetsEnabled(context, checked)
                    },
                    modifier = Modifier.semantics { contentDescription = "Voice Snippets" },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Panel,
                        checkedTrackColor = TextPrimary,
                        uncheckedThumbColor = TextPrimary,
                        uncheckedTrackColor = Panel
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
                        text = "Voice Snippets are currently paused. Expansions will not apply during transcription.",
                        color = TextSecondary,
                        style = FluenceTypography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (snippets.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    FluenceEmptyState(
                        icon = Icons.AutoMirrored.Filled.TextSnippet,
                        title = "No snippets yet",
                        description = "Add a spoken trigger and its expansion, and Fluence will replace it while you dictate.",
                        actionLabel = "Add your first snippet",
                        onAction = {
                            snippetToEdit = null
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
                        items = snippets,
                        key = { it.id }
                    ) { snippet ->
                        SnippetCard(
                            snippet = snippet,
                            modifier = Modifier.fillMaxWidth(),
                            onEdit = {
                                snippetToEdit = snippet
                                showDialog = true
                            },
                            onDelete = {
                                SnippetPreferences.deleteSnippet(context, snippet.id)
                                reload()
                                Toast.makeText(context, "Snippet deleted", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = {
                snippetToEdit = null
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
                contentDescription = "Add Snippet",
                tint = TextPrimary
            )
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
                    reload()
                    showDialog = false
                    snippetToEdit = null
                    null
                }
            }
        )
    }
}

@Composable
private fun SnippetCard(
    snippet: Snippet,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 3.dp,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp))
            .clickable(
                onClickLabel = "Edit snippet",
                role = Role.Button,
                onClick = onEdit
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = snippet.trigger,
                    color = TextPrimary,
                    style = FluenceTypography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = " \u2192 ${snippet.expansion}",
                    color = TextSecondary,
                    style = FluenceTypography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Snippet",
                    tint = TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
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
                    label = { Text("Trigger (Spoken Phrase)") },
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
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = expansionText,
                    onValueChange = {
                        expansionText = it
                        errorMessage = null
                    },
                    label = { Text("Expansion (Output Text)") },
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