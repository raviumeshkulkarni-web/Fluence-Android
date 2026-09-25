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
import com.groq.voicetyper.cleanup.AiCleanupPreferences
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.PrecisionTheme

/**
 * Add or edit a custom AI cleanup style. Same dialog language as the
 * Snippets add/edit dialog: AlertDialog on colors.dialog, OutlinedTextField
 * on colors.inputBg, inline error text, Save plus Cancel. Friendly copy only,
 * no system prompt shown, no warning style banners.
 */
@Composable
fun CustomStyleEditorDialog(
    styleToEdit: AiCleanupPreferences.CustomStyle?,
    onDismiss: () -> Unit,
    onSave: (name: String, hint: String) -> String?
) {
    val colors = PrecisionTheme.colors
    var nameText by remember { mutableStateOf(styleToEdit?.name ?: "") }
    var hintText by remember { mutableStateOf(styleToEdit?.hint ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.dialog,
        modifier = Modifier.imePadding(),
        title = {
            Text(
                text = if (styleToEdit == null) "New style" else "Edit style",
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
                    text = "Describe how your dictation should be rewritten. Names, numbers, and facts are always kept exactly as spoken.",
                    color = colors.textSecondary,
                    style = FluenceTypography.bodySmall
                )
                OutlinedTextField(
                    value = nameText,
                    onValueChange = {
                        if (it.length <= AiCleanupPreferences.MAX_STYLE_NAME_LENGTH) nameText = it
                        errorMessage = null
                    },
                    label = { Text("Style name") },
                    placeholder = { Text("e.g. Friendly short") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = "${nameText.trim().length} / ${AiCleanupPreferences.MAX_STYLE_NAME_LENGTH}",
                            color = colors.textTertiary,
                            style = FluenceTypography.labelSmall
                        )
                    },
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
                    value = hintText,
                    onValueChange = {
                        if (it.length <= AiCleanupPreferences.MAX_STYLE_HINT_LENGTH) hintText = it
                        errorMessage = null
                    },
                    label = { Text("Describe the style") },
                    placeholder = { Text("e.g. Make it funny, very friendly, and extremely short") },
                    minLines = 3,
                    maxLines = 6,
                    supportingText = {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "Your instruction guides the rewrite. Names, numbers, and facts are always kept exactly as spoken.",
                                color = colors.textTertiary,
                                style = FluenceTypography.labelSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${hintText.trim().length} / ${AiCleanupPreferences.MAX_STYLE_HINT_LENGTH}",
                                color = colors.textTertiary,
                                style = FluenceTypography.labelSmall
                            )
                        }
                    },
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
                    val name = nameText.trim()
                    val hint = hintText.trim()
                    when {
                        name.isEmpty() -> errorMessage = "Give your style a name"
                        hint.isEmpty() -> errorMessage = "Describe how it should write"
                        hint.length > AiCleanupPreferences.MAX_STYLE_HINT_LENGTH ->
                            errorMessage = "Hint is too long (max ${AiCleanupPreferences.MAX_STYLE_HINT_LENGTH} characters)"
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
