package com.groq.voicetyper.autolearn.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.FeedbackBus
import com.groq.voicetyper.FluenceEmptyState
import com.groq.voicetyper.autolearn.SuggestionRepository
import com.groq.voicetyper.autolearn.data.SuggestionEntry
import com.groq.voicetyper.theme.*
import com.groq.voicetyper.ui.icons.FluenceIcons
import kotlinx.coroutines.launch

// Windows "Suggested Corrections" parity: tri-state select-all + bulk
// dismiss bar + per-row checkbox, Seen count always visible, Accept/Dismiss
// as ghost text actions in Windows order. Rendered as plain rows inside the
// parent list (no nested scrolling, no card chrome).
@Composable
fun PendingSuggestionsSection(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pendingSuggestions by remember(context) { SuggestionRepository.getPendingSuggestions(context) }
        .collectAsState(initial = emptyList())

    // Prune selections for rows that no longer exist (Windows preserves
    // checks across rebuilds the same way).
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    LaunchedEffect(pendingSuggestions) {
        val alive = pendingSuggestions.map { it.id }.toSet()
        if (selectedIds.any { it !in alive }) {
            selectedIds = selectedIds.intersect(alive)
        }
    }
    val allSelected = pendingSuggestions.isNotEmpty() && selectedIds.size == pendingSuggestions.size
    val triState = when {
        allSelected -> ToggleableState.On
        selectedIds.isEmpty() -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Bulk bar (Windows suggestions-bulk-actions parity).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = FluenceSpacing.Sm, end = FluenceSpacing.Sm)
        ) {
            TriStateCheckbox(
                state = triState,
                onClick = {
                    selectedIds =
                        if (allSelected) emptySet()
                        else pendingSuggestions.map { it.id }.toSet()
                },
                colors = CheckboxDefaults.colors(
                    checkedColor = TextPrimary,
                    checkmarkColor = Canvas,
                    uncheckedColor = TextSecondary
                )
            )
            Text(
                text = "${selectedIds.size} selected",
                color = TextTertiary,
                style = FluenceTypography.labelSmall.copy(fontFamily = GeistMonoFont)
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    val ids = selectedIds.toList()
                    selectedIds = emptySet()
                    scope.launch {
                        ids.mapNotNull { id -> pendingSuggestions.find { it.id == id } }
                            .forEach { SuggestionRepository.dismissSuggestion(context, it) }
                        FeedbackBus.show(
                            if (ids.size == 1) "Dismissed 1 suggestion"
                            else "Dismissed ${ids.size} suggestions"
                        )
                    }
                },
                enabled = selectedIds.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = FluenceSpacing.Sm),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(
                    "Dismiss Selected",
                    color = if (selectedIds.isNotEmpty()) TextSecondary else TextDisabled,
                    style = FluenceTypography.labelMedium
                )
            }
            if (selectedIds.isNotEmpty()) {
                TextButton(
                    onClick = { selectedIds = emptySet() },
                    contentPadding = PaddingValues(horizontal = FluenceSpacing.Sm),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Cancel", color = TextSecondary, style = FluenceTypography.labelMedium)
                }
            }
        }

        if (pendingSuggestions.isEmpty()) {
            FluenceEmptyState(
                icon = FluenceIcons.Lightbulb,
                title = "No suggestions yet",
                description = "Correction suggestions will appear here as you use dictation regularly",
                modifier = Modifier.padding(vertical = FluenceSpacing.Xxl)
            )
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                pendingSuggestions.forEachIndexed { index, suggestion ->
                    SuggestionRow(
                        suggestion = suggestion,
                        selected = suggestion.id in selectedIds,
                        onToggleSelect = { on ->
                            selectedIds =
                                if (on) selectedIds + suggestion.id
                                else selectedIds - suggestion.id
                        },
                        onAccept = {
                            scope.launch {
                                SuggestionRepository.acceptSuggestion(context, suggestion)
                                FeedbackBus.show("Added to Custom Dictionary")
                            }
                        },
                        onDismiss = {
                            scope.launch {
                                SuggestionRepository.dismissSuggestion(context, suggestion)
                            }
                        }
                    )
                    if (index < pendingSuggestions.lastIndex) {
                        HorizontalDivider(
                            color = OutlineSubtle,
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    suggestion: SuggestionEntry,
    selected: Boolean,
    onToggleSelect: (Boolean) -> Unit,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = selected,
            onCheckedChange = onToggleSelect,
            colors = CheckboxDefaults.colors(
                checkedColor = TextPrimary,
                checkmarkColor = Canvas,
                uncheckedColor = TextSecondary
            )
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${suggestion.spokenText} → ${suggestion.correctedText}",
                color = TextPrimary,
                style = FluenceTypography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = "Seen ${suggestion.frequency}x",
                color = TextTertiary,
                style = FluenceTypography.labelSmall
            )
        }
        TextButton(
            onClick = onAccept,
            contentPadding = PaddingValues(horizontal = FluenceSpacing.Sm),
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text("Accept", color = TextSecondary, style = FluenceTypography.labelMedium)
        }
        TextButton(
            onClick = onDismiss,
            contentPadding = PaddingValues(horizontal = FluenceSpacing.Sm),
            modifier = Modifier.heightIn(min = 48.dp)
        ) {
            Text("Dismiss", color = TextSecondary, style = FluenceTypography.labelMedium)
        }
    }
}
