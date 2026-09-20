package com.groq.voicetyper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.semantics.Role
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
import com.groq.voicetyper.agent.AgentPreferences
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.PrecisionTheme
import com.groq.voicetyper.ui.icons.FluenceIcons

/**
 * Agents board. Same visual language as the AI cleanup styles screen: one
 * cardSurface container, section headers, plain rows, dividers do the
 * separating. The built-in multipurpose agent is fixed. Tapping any row
 * sets that agent as the default for long-press agent mode.
 */
@Composable
fun AgentsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    var customs by remember { mutableStateOf(AgentPreferences.loadCustomAgents(context)) }
    var defaultId by remember { mutableStateOf(AgentPreferences.getDefaultAgentId(context)) }
    var showEditor by remember { mutableStateOf(false) }
    var agentToEdit by remember { mutableStateOf<AgentPreferences.CustomAgent?>(null) }

    val stringSetSaver = Saver<androidx.compose.runtime.MutableState<Set<String>>, ArrayList<String>>(
        save = { arrayListOf<String>().apply { addAll(it.value) } },
        restore = { mutableStateOf(it.toSet()) }
    )
    val stringListSaver = Saver<androidx.compose.runtime.MutableState<List<String>>, ArrayList<String>>(
        save = { ArrayList(it.value) },
        restore = { mutableStateOf(it.toList()) }
    )

    var selectedIds by rememberSaveable(saver = stringSetSaver) { mutableStateOf(setOf<String>()) }
    val isMultiSelect = selectedIds.isNotEmpty()
    var pendingDeleteIds by rememberSaveable(saver = stringListSaver) { mutableStateOf<List<String>>(emptyList()) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = isMultiSelect) {
        selectedIds = emptySet()
    }

    fun refresh() {
        customs = AgentPreferences.loadCustomAgents(context)
        defaultId = AgentPreferences.getDefaultAgentId(context)
        // Prune selections of deleted agents so multiselect can't stick
        // on with a phantom count.
        val valid = customs.map { it.id }.toSet()
        selectedIds = selectedIds.intersect(valid)
    }

    // Refresh on every return, so edits made elsewhere are never stale.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
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
                        val all = customs.map { it.id }.toSet()
                        selectedIds = if (selectedIds == all) emptySet() else all
                    },
                    contentPadding = PaddingValues(horizontal = FluenceSpacing.Sm),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        if (selectedIds == customs.map { it.id }.toSet()) "Deselect all" else "Select all",
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
                title = "Agents",
                onBack = onNavigateBack,
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )
        }

        Text(
            text = "Agents are saved instructions that shape how Agent Mode writes for you, e.g. keep my emails short and professional. They only change wording and can never take actions. Tap one to make it your default.",
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

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = FluenceSpacing.Base)
                .background(colors.cardSurface, FluenceShapes.Medium)
        ) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    FluenceSectionHeader(label = "BUILT IN")
                }
                item {
                    AgentRow(
                        title = "Fluence Agent",
                        description = "The all-rounder. Edits, rewrites, and answers questions.",
                        isDefault = defaultId == AgentPreferences.ID_BUILT_IN,
                        onClick = {
                            AgentPreferences.setDefaultAgentId(context, AgentPreferences.ID_BUILT_IN)
                            refresh()
                            FeedbackBus.show("Fluence Agent set as default")
                        }
                    )
                    HorizontalDivider(
                        color = colors.outlineSubtle,
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
                    )
                }
                item {
                    FluenceSectionHeader(
                        label = "CUSTOM AGENTS",
                        actionLabel = "+ New",
                        onAction = {
                            agentToEdit = null
                            showEditor = true
                        }
                    )
                }
                if (customs.isEmpty()) {
                    item {
                        FluenceEmptyState(
                            icon = FluenceIcons.Zap,
                            title = "No custom agents yet",
                            description = "Create one that writes the way you want, e.g. a translator that always replies in Hindi",
                            actionLabel = "Create your first agent",
                            onAction = {
                                agentToEdit = null
                                showEditor = true
                            },
                            modifier = Modifier.padding(vertical = FluenceSpacing.Xxl)
                        )
                    }
                } else {
                    items(customs, key = { it.id }) { agent ->
                        AgentRow(
                            title = agent.name,
                            description = agent.hint,
                            isDefault = defaultId == agent.id,
                            isCustom = true,
                            isSelected = agent.id in selectedIds,
                            isMultiSelect = isMultiSelect,
                            onToggleSelect = {
                                selectedIds = if (agent.id in selectedIds) {
                                    selectedIds - agent.id
                                } else {
                                    selectedIds + agent.id
                                }
                            },
                            onClick = {
                                AgentPreferences.setDefaultAgentId(context, agent.id)
                                refresh()
                                FeedbackBus.show("${agent.name} set as default")
                            },
                            onEdit = {
                                agentToEdit = agent
                                showEditor = true
                            },
                            onDelete = {
                                pendingDeleteIds = listOf(agent.id)
                                showDeleteDialog = true
                            }
                        )
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

    if (showDeleteDialog) {
        val count = pendingDeleteIds.size
        val agentName = if (count == 1) {
            customs.find { it.id == pendingDeleteIds.first() }?.name
        } else null
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = colors.dialog,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            title = { Text("Delete custom agent${if (count > 1) "s" else ""}") },
            text = {
                Text(
                    if (count == 1 && agentName != null) "This action cannot be undone. Delete \"$agentName\"?"
                    else "This action cannot be undone. Delete ${if (count > 1) "$count custom agents" else "this agent"}?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteIds.forEach { id ->
                            AgentPreferences.deleteCustomAgent(context, id)
                        }
                        selectedIds = selectedIds - pendingDeleteIds.toSet()
                        refresh()
                        showDeleteDialog = false
                        FeedbackBus.show(if (count > 1) "Deleted $count agents" else "Agent deleted")
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

    if (showEditor) {
        AgentEditorDialog(
            agentToEdit = agentToEdit,
            onDismiss = {
                showEditor = false
                agentToEdit = null
            },
            onSave = { name, hint ->
                val nameError = AgentPreferences.validateAgentName(context, name, agentToEdit?.id)
                if (nameError != null) {
                    nameError
                } else {
                    val saved = AgentPreferences.saveCustomAgent(
                        context = context,
                        name = name,
                        hint = hint,
                        id = agentToEdit?.id
                    )
                    if (saved == null) {
                        "Could not save. Try a shorter name and hint."
                    } else {
                        refresh()
                        showEditor = false
                        agentToEdit = null
                        FeedbackBus.show("Agent saved")
                        null
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentRow(
    title: String,
    description: String,
    isDefault: Boolean,
    isCustom: Boolean = false,
    isSelected: Boolean = false,
    isMultiSelect: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val colors = PrecisionTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val bgColor = if (isSelected) colors.textPrimary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
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
                    .clip(CircleShape)
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
                .pressScale(interactionSource)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onClickLabel = if (isMultiSelect) "Toggle selection" else "Set $title as default agent",
                    onLongClickLabel = if (isCustom) "Select $title" else null,
                    onClick = {
                        if (isMultiSelect && onToggleSelect != null) {
                            onToggleSelect()
                        } else {
                            onClick()
                        }
                    },
                    onLongClick = if (isCustom && onToggleSelect != null) {
                        { onToggleSelect() }
                    } else null
                )
                .semantics {
                    if (isMultiSelect) {
                        role = Role.Checkbox
                        selected = isSelected
                        stateDescription = if (isSelected) "Selected" else "Not selected"
                    } else {
                        role = Role.RadioButton
                        selected = isDefault
                    }
                }
                .padding(
                    start = if (isSelected) FluenceSpacing.Sm else FluenceSpacing.Base,
                    end = FluenceSpacing.Sm,
                    top = FluenceSpacing.Md,
                    bottom = FluenceSpacing.Md
                )
        ) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = colors.textSecondary,
                style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.Normal),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (isDefault) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Default",
                    color = colors.textTertiary,
                    style = FluenceTypography.labelSmall
                )
            }
        }
        if (!isMultiSelect) {
            if (onEdit != null) {
                TextButton(onClick = onEdit, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Edit", color = colors.textSecondary, style = FluenceTypography.labelMedium)
                }
            }
            if (onDelete != null) {
                TextButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Delete", color = colors.error, style = FluenceTypography.labelMedium)
                }
            }
            RadioButton(
                selected = isDefault,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = colors.textPrimary,
                    unselectedColor = colors.textSecondary.copy(alpha = 0.5f)
                ),
                modifier = Modifier.padding(end = FluenceSpacing.Sm)
            )
        }
    }
}
