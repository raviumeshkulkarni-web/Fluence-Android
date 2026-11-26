package com.groq.voicetyper.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.PrecisionTheme
import com.groq.voicetyper.ui.icons.FluenceIcons

@Composable
fun fluenceSwitchColors(): SwitchColors {
    val colors = PrecisionTheme.colors
    return SwitchDefaults.colors(
        checkedThumbColor = if (colors.isLight) Color.White else colors.panel,
        checkedTrackColor = colors.activeControl,
        uncheckedThumbColor = colors.textPrimary,
        uncheckedTrackColor = colors.panel,
        uncheckedBorderColor = colors.outlineSubtle
    )
}

@Composable
fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    toggleLabel: String = title,
    enabled: Boolean = true,
    descriptionColor: Color? = null
) {
    val colors = PrecisionTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .pressScale(interactionSource)
            .toggleable(
                value = checked,
                enabled = enabled,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = FluenceSpacing.Base, vertical = 14.dp)
            .heightIn(min = FluenceSpacing.Xxl),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = FluenceTypography.titleMedium
            )
            Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
            Text(
                text = description,
                color = descriptionColor ?: colors.textSecondary,
                style = FluenceTypography.bodySmall
            )
        }
        Spacer(modifier = Modifier.width(FluenceSpacing.Base))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = fluenceSwitchColors(),
            modifier = Modifier.semantics {
                contentDescription = toggleLabel
                stateDescription = if (checked) "On" else "Off"
            }
        )
    }
}

@Composable
fun FluenceConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    busy: Boolean = false,
    cancelLabel: String = "Cancel"
) {
    val colors = PrecisionTheme.colors
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = colors.dialog,
        titleContentColor = colors.textPrimary,
        textContentColor = colors.textSecondary,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy
            ) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) colors.errorText else colors.textPrimary,
                    style = FluenceTypography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !busy
            ) {
                Text(
                    text = cancelLabel,
                    color = colors.textSecondary,
                    style = FluenceTypography.labelLarge
                )
            }
        }
    )
}

@Composable
fun FluenceSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = {
            Text(
                text = placeholder,
                color = colors.textSecondary,
                style = FluenceTypography.bodySmall,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        },
        singleLine = true,
        maxLines = 1,
        leadingIcon = {
            Icon(
                imageVector = FluenceIcons.Search,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                val clearInteraction = remember { MutableInteractionSource() }
                IconButton(
                    onClick = { onQueryChange("") },
                    interactionSource = clearInteraction,
                    modifier = Modifier
                        .size(FluenceSpacing.Xxl)
                        .pressScale(clearInteraction)
                ) {
                    Icon(
                        imageVector = FluenceIcons.X,
                        contentDescription = "Clear search",
                        tint = colors.textTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            focusedBorderColor = colors.controlFocusBorder,
            unfocusedBorderColor = colors.inputBorder,
            focusedContainerColor = colors.panelElevated,
            unfocusedContainerColor = colors.panelElevated,
            cursorColor = colors.textPrimary
        ),
        shape = FluenceShapes.Small,
        textStyle = FluenceTypography.bodySmall,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = FluenceSpacing.Xxl)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FluenceBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = PrecisionTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = colors.panelElevated,
        contentColor = colors.textPrimary,
        shape = FluenceShapes.Large,
        windowInsets = WindowInsets(0, 0, 0, 0),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = FluenceSpacing.Md)
                    .width(FluenceSpacing.Xl)
                    .height(FluenceSpacing.Xs)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.textPrimary.copy(alpha = 0.18f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = FluenceSpacing.Base),
            content = content
        )
    }
}

@Composable
fun FluenceMultiSelectTopBar(
    selectedCount: Int,
    allSelected: Boolean,
    onExit: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleSelectAll: (() -> Unit)? = null,
    extraActions: (@Composable RowScope.() -> Unit)? = null
) {
    val colors = PrecisionTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = FluenceSpacing.Base),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onExit, modifier = Modifier.size(FluenceSpacing.Xxl)) {
            Icon(
                imageVector = FluenceIcons.X,
                contentDescription = "Exit selection",
                tint = colors.textPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(FluenceSpacing.Base))
        Text(
            text = "$selectedCount selected",
            color = colors.textPrimary,
            style = FluenceTypography.headlineMedium
        )
        Spacer(modifier = Modifier.weight(1f))
        if (onToggleSelectAll != null) {
            TextButton(
                onClick = onToggleSelectAll,
                contentPadding = PaddingValues(horizontal = FluenceSpacing.Sm),
                modifier = Modifier.heightIn(min = FluenceSpacing.Xxl)
            ) {
                Text(
                    text = if (allSelected) "Deselect all" else "Select all",
                    color = colors.textSecondary,
                    style = FluenceTypography.labelMedium
                )
            }
        }
        if (extraActions != null) {
            extraActions()
        }
        IconButton(onClick = onDeleteSelected, modifier = Modifier.size(FluenceSpacing.Xxl)) {
            Icon(
                imageVector = FluenceIcons.Trash2,
                contentDescription = "Delete selected",
                tint = colors.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun FluencePromptEditorDialog(
    title: String,
    introText: String,
    initialName: String,
    initialHint: String,
    nameLabel: String,
    namePlaceholder: String,
    nameMaxLength: Int,
    hintLabel: String,
    hintPlaceholder: String,
    hintHelperText: String,
    hintMaxLength: Int,
    emptyNameError: String,
    emptyHintError: String,
    onDismiss: () -> Unit,
    onSave: (name: String, hint: String) -> String?,
    sanitizeNameInput: (String) -> String = { it },
    normalizeNameForSave: (String) -> String = { it.trim() },
    normalizeHintForSave: (String) -> String = { it.trim() }
) {
    val colors = PrecisionTheme.colors
    var nameText by remember { mutableStateOf(initialName) }
    var hintText by remember { mutableStateOf(initialHint) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedContainerColor = colors.inputBg,
        unfocusedContainerColor = colors.inputBg,
        focusedBorderColor = colors.controlFocusBorder,
        unfocusedBorderColor = colors.inputBorder,
        focusedLabelColor = colors.textPrimary,
        unfocusedLabelColor = colors.textSecondary,
        focusedTextColor = colors.textPrimary,
        unfocusedTextColor = colors.textPrimary,
        cursorColor = colors.textPrimary
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.dialog,
        modifier = Modifier.imePadding(),
        title = {
            Text(
                text = title,
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
                Text(
                    text = introText,
                    color = colors.textSecondary,
                    style = FluenceTypography.bodySmall
                )
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { raw ->
                        val sanitized = sanitizeNameInput(raw)
                        if (sanitized.length <= nameMaxLength) nameText = sanitized
                        errorMessage = null
                    },
                    label = { Text(nameLabel) },
                    placeholder = { Text(namePlaceholder) },
                    singleLine = true,
                    supportingText = {
                        Text(
                            text = "${nameText.trim().length} /$nameMaxLength",
                            color = colors.textTertiary,
                            style = FluenceTypography.labelSmall
                        )
                    },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = hintText,
                    onValueChange = { raw ->
                        if (raw.length <= hintMaxLength) hintText = raw
                        errorMessage = null
                    },
                    label = { Text(hintLabel) },
                    placeholder = { Text(hintPlaceholder) },
                    minLines = 3,
                    maxLines = 6,
                    supportingText = {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = hintHelperText,
                                color = colors.textTertiary,
                                style = FluenceTypography.labelSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                            Text(
                                text = "${hintText.trim().length} /$hintMaxLength",
                                color = colors.textTertiary,
                                style = FluenceTypography.labelSmall
                            )
                        }
                    },
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = colors.errorText,
                        style = FluenceTypography.labelMedium
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val name = normalizeNameForSave(nameText)
                    val hint = normalizeHintForSave(hintText)
                    when {
                        name.isEmpty() -> errorMessage = emptyNameError
                        hint.isEmpty() -> errorMessage = emptyHintError
                        hint.length > hintMaxLength ->
                            errorMessage = "Hint is too long (max $hintMaxLength characters)"
                        else -> {
                            val err = onSave(name, hint)
                            if (err != null) errorMessage = err
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
