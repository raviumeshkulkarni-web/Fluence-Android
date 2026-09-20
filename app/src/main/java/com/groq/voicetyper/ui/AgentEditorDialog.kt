package com.groq.voicetyper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.agent.AgentPreferences
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.PrecisionTheme

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
    val colors = PrecisionTheme.colors
    var nameText by remember { mutableStateOf(agentToEdit?.name ?: "") }
    var hintText by remember { mutableStateOf(agentToEdit?.hint ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.dialog,
        modifier = Modifier.imePadding(),
        title = {
            Text(
                text = if (agentToEdit == null) "New agent" else "Edit agent",
                color = colors.textPrimary,
                style = FluenceTypography.headlineSmall
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Tell Agent Mode how to write for you. It shapes your words only — it can never take actions.",
                    color = colors.textSecondary,
                    style = FluenceTypography.bodySmall
                )
                OutlinedTextField(
                    value = nameText,
                    onValueChange = {
                        val sanitized = it.replace("\r", "").replace("\n", " ")
                        if (sanitized.length <= AgentPreferences.MAX_AGENT_NAME_LENGTH) nameText = sanitized
                        errorMessage = null
                    },
                    label = { Text("Agent name") },
                    placeholder = { Text("e.g. Email writer") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = "${nameText.trim().length} / ${AgentPreferences.MAX_AGENT_NAME_LENGTH}",
                            color = colors.textTertiary,
                            style = FluenceTypography.labelSmall
                        )
                    },
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
                    value = hintText,
                    onValueChange = {
                        if (it.length <= AgentPreferences.MAX_AGENT_HINT_LENGTH) hintText = it
                        errorMessage = null
                    },
                    label = { Text("What should it do") },
                    placeholder = { Text("e.g. Always reply professionally and keep it short") },
                    minLines = 3,
                    maxLines = 6,
                    supportingText = {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Only shapes your wording. It can't press buttons or take actions, just like the built-in agent.",
                                color = colors.textTertiary,
                                style = FluenceTypography.labelSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${hintText.trim().length} / ${AgentPreferences.MAX_AGENT_HINT_LENGTH}",
                                color = colors.textTertiary,
                                style = FluenceTypography.labelSmall
                            )
                        }
                    },
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
                    val name = AgentPreferences.sanitizeName(nameText)
                    val hint = AgentPreferences.sanitizeHint(hintText)
                    when {
                        name.isEmpty() -> errorMessage = "Give your agent a name"
                        hint.isEmpty() -> errorMessage = "Describe how it should behave"
                        hint.length > AgentPreferences.MAX_AGENT_HINT_LENGTH ->
                            errorMessage = "Hint is too long (max ${AgentPreferences.MAX_AGENT_HINT_LENGTH} characters)"
                        else -> {
                            val error = onSave(name, hint)
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
