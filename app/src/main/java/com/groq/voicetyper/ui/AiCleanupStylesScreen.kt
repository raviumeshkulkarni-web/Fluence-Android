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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.FeedbackBus
import com.groq.voicetyper.FluenceEmptyState
import com.groq.voicetyper.FluenceSectionHeader
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.cleanup.AiCleanupPreferences
import com.groq.voicetyper.navigation.Screen
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.PrecisionTheme
import com.groq.voicetyper.ui.icons.FluenceIcons

/**
 * AI cleanup styles list. Same visual language as Text Formatting buckets
 * plus Snippets custom rows: one cardSurface container, section headers,
 * plain rows, dividers do the separating. Built ins stay fixed with hidden
 * prompts. Customs are user named with their own app groups.
 */
@Composable
fun AiCleanupStylesScreen(
    onNavigateBack: () -> Unit,
    onNavigateTo: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    var customs by remember { mutableStateOf(AiCleanupPreferences.loadCustomStyles(context)) }
    var overrides by remember { mutableStateOf(AiCleanupPreferences.getOverrides(context)) }
    var showEditor by remember { mutableStateOf(false) }
    var styleToEdit by remember { mutableStateOf<AiCleanupPreferences.CustomStyle?>(null) }

    // Refresh when returning from a picker.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        customs = AiCleanupPreferences.loadCustomStyles(context)
        overrides = AiCleanupPreferences.getOverrides(context)
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
            title = "AI cleanup styles",
            onBack = onNavigateBack,
            modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
        )

        Text(
            text = "Pick a style per app. Each app uses one AI style. Built in prompts stay hidden.",
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
                items(AiCleanupPreferences.BUILT_IN_IDS, key = { it }) { styleId ->
                    val meta = AiCleanupPreferences.builtInMeta(styleId)
                    val count = overrides.count { it.value == styleId }
                    AiStyleRow(
                        title = meta.title,
                        description = meta.explanation,
                        badge = if (count > 0) "$count apps" else "Tap to assign apps",
                        onClick = { onNavigateTo(Screen.AiStylePicker(styleId)) }
                    )
                    HorizontalDivider(
                        color = colors.outlineSubtle,
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
                    )
                }
                item {
                    FluenceSectionHeader(
                        label = "CUSTOM STYLES",
                        actionLabel = "+ New",
                        onAction = {
                            styleToEdit = null
                            showEditor = true
                        }
                    )
                }
                if (customs.isEmpty()) {
                    item {
                        FluenceEmptyState(
                            icon = FluenceIcons.Zap,
                            title = "No custom styles yet",
                            description = "Create your own style with your own wording, e.g. funny and extremely short",
                            actionLabel = "Create your first style",
                            onAction = {
                                styleToEdit = null
                                showEditor = true
                            },
                            modifier = Modifier.padding(vertical = FluenceSpacing.Xxl)
                        )
                    }
                } else {
                    items(customs, key = { it.id }) { style ->
                        val count = overrides.count { it.value == style.id }
                        AiStyleRow(
                            title = style.name,
                            description = if (count > 0) "$count apps" else "Tap to assign apps",
                            badge = null,
                            onClick = { onNavigateTo(Screen.AiStylePicker(style.id)) },
                            onEdit = {
                                styleToEdit = style
                                showEditor = true
                            },
                            onDelete = {
                                AiCleanupPreferences.deleteCustomStyle(context, style.id)
                                customs = AiCleanupPreferences.loadCustomStyles(context)
                                overrides = AiCleanupPreferences.getOverrides(context)
                                FeedbackBus.show("Style deleted")
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
        CustomStyleEditorDialog(
            styleToEdit = styleToEdit,
            onDismiss = {
                showEditor = false
                styleToEdit = null
            },
            onSave = { name, hint ->
                val saved = AiCleanupPreferences.saveCustomStyle(
                    context = context,
                    name = name,
                    hint = hint,
                    id = styleToEdit?.id
                )
                if (saved == null) {
                    "Could not save. Try a shorter name and hint."
                } else {
                    customs = AiCleanupPreferences.loadCustomStyles(context)
                    overrides = AiCleanupPreferences.getOverrides(context)
                    showEditor = false
                    styleToEdit = null
                    FeedbackBus.show("Style saved")
                    null
                }
            }
        )
    }
}

@Composable
private fun AiStyleRow(
    title: String,
    description: String,
    badge: String?,
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
                onClickLabel = "Open $title apps",
                role = Role.Button,
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
                style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.Normal)
            )
            if (badge != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = badge,
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
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}
