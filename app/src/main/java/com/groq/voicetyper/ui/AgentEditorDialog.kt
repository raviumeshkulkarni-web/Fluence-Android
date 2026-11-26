package com.groq.voicetyper.ui

import androidx.compose.runtime.Composable
import com.groq.voicetyper.agent.AgentPreferences

/**
 * Add or edit a custom agent. Same dialog language as the snippet and
 * custom-style editors: AlertDialog on colors.dialog, OutlinedTextField on
 * colors.inputBg, live counters, inline errors, Save plus Cancel. The fixed
 * command contract is never shown or editable here.
 */
@Composable
fun AgentEditorDialog(
    agentToEdit: AgentPreferences.CustomAgent?,
    onDismiss: () -> Unit,
    onSave: (name: String, hint: String) -> String?
) {
    FluencePromptEditorDialog(
        title = if (agentToEdit == null) "New agent" else "Edit agent",
        introText = "Tell Agent Mode how to write for you. It shapes your words only and can never take actions.",
        initialName = agentToEdit?.name ?: "",
        initialHint = agentToEdit?.hint ?: "",
        nameLabel = "Agent name",
        namePlaceholder = "e.g. Email writer",
        nameMaxLength = AgentPreferences.MAX_AGENT_NAME_LENGTH,
        hintLabel = "What should it do",
        hintPlaceholder = "e.g. Always reply professionally and keep it short",
        hintHelperText = "Only shapes your wording. It can't press buttons or take actions, just like the built-in agent.",
        hintMaxLength = AgentPreferences.MAX_AGENT_HINT_LENGTH,
        emptyNameError = "Give your agent a name",
        emptyHintError = "Describe how it should behave",
        onDismiss = onDismiss,
        onSave = onSave,
        sanitizeNameInput = { it.replace("\r", "").replace("\n", " ") },
        normalizeNameForSave = { AgentPreferences.sanitizeName(it) },
        normalizeHintForSave = { AgentPreferences.sanitizeHint(it) }
    )
}
