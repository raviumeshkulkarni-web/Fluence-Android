package com.groq.voicetyper.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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

    // Refresh when returning from a picker (same ON_RESUME pattern as
    // FormattingScreen, so edits made in the picker are never stale).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                customs = AiCleanupPreferences.loadCustomStyles(context)
                overrides = AiCleanupPreferences.getOverrides(context)
                selectedIds = selectedIds.intersect(customs.map { it.id }.toSet())
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
                title = "AI cleanup styles",
                onBack = onNavigateBack,
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )
        }

        Text(
            text = "Give each app its own writing style: casual for WhatsApp, polished for Gmail. Tap a style, then tap the apps that should use it. The three built-in styles work out of the box.",
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
                            description = "Create a style that writes the way you want, e.g. funny and extremely short",
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
                            isCustom = true,
                            isSelected = style.id in selectedIds,
                            isMultiSelect = isMultiSelect,
                            onToggleSelect = {
                                selectedIds = if (style.id in selectedIds) {
                                    selectedIds - style.id
                                } else {
                                    selectedIds + style.id
                                }
                            },
                            onClick = { onNavigateTo(Screen.AiStylePicker(style.id)) },
                            onEdit = {
                                styleToEdit = style
                                showEditor = true
                            },
                            onDelete = {
                                pendingDeleteIds = listOf(style.id)
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
        val styleName = if (count == 1) {
            customs.find { it.id == pendingDeleteIds.first() }?.name
        } else null
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = colors.dialog,
            titleContentColor = colors.textPrimary,
            textContentColor = colors.textSecondary,
            title = { Text("Delete custom style${if (count > 1) "s" else ""}") },
            text = {
                Text(
                    if (count == 1 && styleName != null) "This action cannot be undone. Delete \"$styleName\"?"
                    else "This action cannot be undone. Delete ${if (count > 1) "$count custom styles" else "this style"}?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteIds.forEach { id ->
                            AiCleanupPreferences.deleteCustomStyle(context, id)
                        }
                        selectedIds = selectedIds - pendingDeleteIds.toSet()
                        customs = AiCleanupPreferences.loadCustomStyles(context)
                        overrides = AiCleanupPreferences.getOverrides(context)
                        showDeleteDialog = false
                        FeedbackBus.show(if (count > 1) "Deleted $count styles" else "Style deleted")
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiStyleRow(
    title: String,
    description: String,
    badge: String?,
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
        Row(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onClickLabel = if (isMultiSelect) "Toggle selection" else "Open $title apps",
                    onLongClickLabel = if (isCustom) "Select $title style" else null,
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
                        role = Role.Button
                    }
                }
                .padding(
                    start = if (isSelected) FluenceSpacing.Sm else FluenceSpacing.Base,
                    end = FluenceSpacing.Sm,
                    top = FluenceSpacing.Md,
                    bottom = FluenceSpacing.Md
                ),
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
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
