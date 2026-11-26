package com.groq.voicetyper.ui

import androidx.compose.runtime.Composable
import com.groq.voicetyper.cleanup.AiCleanupPreferences

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
    FluencePromptEditorDialog(
        title = if (styleToEdit == null) "New style" else "Edit style",
        introText = "Describe how your dictation should be rewritten. Names, numbers, and facts are always kept exactly as spoken.",
        initialName = styleToEdit?.name ?: "",
        initialHint = styleToEdit?.hint ?: "",
        nameLabel = "Style name",
        namePlaceholder = "e.g. Friendly short",
        nameMaxLength = AiCleanupPreferences.MAX_STYLE_NAME_LENGTH,
        hintLabel = "Describe the style",
        hintPlaceholder = "e.g. Make it funny, very friendly, and extremely short",
        hintHelperText = "Your instruction guides the rewrite. Names, numbers, and facts are always kept exactly as spoken.",
        hintMaxLength = AiCleanupPreferences.MAX_STYLE_HINT_LENGTH,
        emptyNameError = "Give your style a name",
        emptyHintError = "Describe how it should write",
        onDismiss = onDismiss,
        onSave = onSave
    )
}
