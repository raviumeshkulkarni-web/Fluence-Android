package com.groq.voicetyper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
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

    LaunchedEffect(Unit) {
        customs = AgentPreferences.loadCustomAgents(context)
        defaultId = AgentPreferences.getDefaultAgentId(context)
    }

    fun refresh() {
        customs = AgentPreferences.loadCustomAgents(context)
        defaultId = AgentPreferences.getDefaultAgentId(context)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        SettingsTopBar(
            title = "Agents",
            onBack = onNavigateBack,
            modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
        )

        Text(
            text = "Custom agents change the wording, never the powers. Tap a row to make it the default for agent mode.",
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
                        description = "Multipurpose. Edits, rewrites, and answers.",
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
                            description = "Create your own agent with your own behavior, e.g. a translator that always replies in Hindi",
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
                                AgentPreferences.deleteCustomAgent(context, agent.id)
                                refresh()
                                FeedbackBus.show("Agent deleted")
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

@Composable
private fun AgentRow(
    title: String,
    description: String,
    isDefault: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    val colors = PrecisionTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
                onClickLabel = "Set $title as default agent",
                role = Role.RadioButton,
                onClick = onClick
            )
            .padding(horizontal = FluenceSpacing.Base, vertical = 12.dp)
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
            )
        )
    }
}
