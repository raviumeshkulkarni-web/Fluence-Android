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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.FluenceSectionHeader
import com.groq.voicetyper.SecurityUtils
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.cleanup.CleanupPreferences
import com.groq.voicetyper.formatting.FormattingCategory
import com.groq.voicetyper.formatting.FormattingPreferences
import com.groq.voicetyper.navigation.Screen
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.PrecisionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Text Formatting ─────────────────────────────────────────────────────
// Dictionary language: one cardSurface container, section headers, plain
// rows, dividers do the separating. No per-row cards. Each bucket row shows
// a one-line explanation plus a live example computed by the real
// formatter, so what the user reads is what dictation produces. Tapping a
// row opens that bucket's app picker. Apps in no bucket follow the field
// type.
private fun bucketExplanation(category: FormattingCategory): String = when (category) {
    FormattingCategory.FORMAL -> "Email style with capitals and full stops."
    FormattingCategory.CASUAL -> "Chat style that keeps capitals but drops the last full stop."
    else -> "Lowercase texting style with minimal punctuation."
}

private val bucketOrder = listOf(
    FormattingCategory.FORMAL,
    FormattingCategory.CASUAL,
    FormattingCategory.VERY_CASUAL
)

@Composable
fun FormattingScreen(
    onNavigateBack: () -> Unit,
    onNavigateTo: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    var masterEnabled by remember {
        mutableStateOf(FormattingPreferences.isMasterEnabled(context))
    }
    var cleanupEnabled by remember { mutableStateOf(CleanupPreferences.isCleanupEnabled(context)) }
    var cleanupPreset by remember { mutableStateOf(CleanupPreferences.getCleanupPreset(context)) }
    var cleanupModel by remember { mutableStateOf(CleanupPreferences.getCleanupModel(context)) }
    var agentPresetId by remember { mutableStateOf("") }
    var agentPresetLabel by remember { mutableStateOf("") }
    var agentModelLabel by remember { mutableStateOf("") }
    var showModelSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val preset = withContext(Dispatchers.IO) { SecurityUtils.getLlmPreset(context) }
        agentPresetId = preset
        agentPresetLabel = preset.replaceFirstChar { it.uppercase() }
        agentModelLabel = withContext(Dispatchers.IO) { SecurityUtils.getLlmModel(context, preset) }
    }

    val cleanupModelSummary = if (cleanupPreset.isNotBlank() && cleanupModel.isNotBlank()) {
        val label = when (cleanupPreset) {
            "groq" -> "Groq"
            "mistral" -> "Mistral"
            "custom" -> "Custom"
            else -> cleanupPreset.replaceFirstChar { it.uppercase() }
        }
        "$label · $cleanupModel"
    } else if (agentModelLabel.isNotBlank()) {
        "Follows AI Agent Mode · $agentPresetLabel · $agentModelLabel"
    } else {
        "Follows AI Agent Mode"
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
            title = "Text Formatting",
            onBack = onNavigateBack,
            modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
        )

        Text(
            text = "Match dictation tone to the app. Off by default, transcripts stay exactly as spoken until you opt in.",
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

        // Card fills the remaining viewport (Dictionary parity): toggle and
        // bucket rows, then AI cleanup. No per-row cards, dividers separate.
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = FluenceSpacing.Base)
                .background(colors.cardSurface, FluenceShapes.Medium)
        ) {
            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    ToggleRow(
                        title = "Text formatting",
                        description = if (masterEnabled) "On" else "Off · transcripts unchanged",
                        checked = masterEnabled,
                        toggleLabel = "Text formatting",
                        onCheckedChange = { enabled ->
                            FormattingPreferences.setMasterEnabled(context, enabled)
                            masterEnabled = enabled
                        }
                    )
                }
                item {
                    HorizontalDivider(
                        color = colors.outlineSubtle,
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
                    )
                }
                item {
                    FluenceSectionHeader(label = "STYLE BUCKETS")
                }
                items(bucketOrder, key = { it.name }) { category ->
                    BucketRow(
                        category = category,
                        onClick = { onNavigateTo(Screen.BucketPicker(category.name)) }
                    )
                    if (category != bucketOrder.last()) {
                        HorizontalDivider(
                            color = colors.outlineSubtle,
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
                        )
                    }
                }
                item {
                    Text(
                        text = "Apps in no bucket follow the field you type in.",
                        color = colors.textSecondary,
                        style = FluenceTypography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = FluenceSpacing.Base,
                                vertical = FluenceSpacing.Sm
                            )
                    )
                }
                item {
                    FluenceSectionHeader(label = "AI CLEANUP")
                }
                item {
                    ToggleRow(
                        title = "AI cleanup",
                        description = if (cleanupEnabled) "On · needs internet, skipped offline" else "Off",
                        checked = cleanupEnabled,
                        toggleLabel = "AI cleanup",
                        onCheckedChange = { enabled ->
                            CleanupPreferences.setCleanupEnabled(context, enabled)
                            cleanupEnabled = enabled
                        }
                    )
                }
                item {
                    Text(
                        text = "Polishes filler words and grammar after dictation.",
                        color = colors.textSecondary,
                        style = FluenceTypography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = FluenceSpacing.Base,
                                end = FluenceSpacing.Base,
                                bottom = FluenceSpacing.Sm
                            )
                    )
                }
                if (cleanupEnabled) {
                    item {
                        val modelRowSource = remember { MutableInteractionSource() }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressScale(modelRowSource)
                                .clickable(
                                    interactionSource = modelRowSource,
                                    indication = androidx.compose.foundation.LocalIndication.current,
                                    onClickLabel = "Choose cleanup model",
                                    role = Role.Button,
                                    onClick = { showModelSheet = true }
                                )
                                .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm)
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Cleanup model",
                                    color = colors.textPrimary,
                                    style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = cleanupModelSummary,
                                    color = colors.textSecondary,
                                    style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.Normal)
                                )
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
                item {
                    val stylesRowSource = remember { MutableInteractionSource() }
                    val customCount = remember(cleanupEnabled) {
                        com.groq.voicetyper.cleanup.AiCleanupPreferences.loadCustomStyles(context).size
                    }
                    val stylesSummary = if (customCount > 0) {
                        "Proofread, Natural, Professional plus $customCount custom"
                    } else {
                        "Proofread, Natural, Professional"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale(stylesRowSource)
                            .clickable(
                                interactionSource = stylesRowSource,
                                indication = androidx.compose.foundation.LocalIndication.current,
                                onClickLabel = "Open AI cleanup styles",
                                role = Role.Button,
                                onClick = { onNavigateTo(Screen.AiCleanupStyles) }
                            )
                            .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm)
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "AI cleanup styles",
                                color = colors.textPrimary,
                                style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stylesSummary,
                                color = colors.textSecondary,
                                style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.Normal)
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(FluenceSpacing.Md))
                }
            }
        }
    }

    if (showModelSheet) {
        CleanupModelSheet(
            activePreset = cleanupPreset,
            activeModel = cleanupModel,
            agentPreset = agentPresetId,
            agentModel = agentModelLabel,
            onSelectModel = { preset, model ->
                CleanupPreferences.setCleanupPreset(context, preset)
                CleanupPreferences.setCleanupModel(context, model)
                cleanupPreset = preset
                cleanupModel = model
            },
            onFollowAgent = {
                CleanupPreferences.clearCleanupModel(context)
                cleanupPreset = ""
                cleanupModel = ""
            },
            onDismiss = { showModelSheet = false }
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    toggleLabel: String,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm)
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
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                checkedTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                uncheckedThumbColor = colors.textPrimary,
                uncheckedTrackColor = colors.panel
            ),
            modifier = Modifier.semantics {
                role = Role.Switch
                contentDescription = toggleLabel
                stateDescription = if (checked) "On" else "Off"
            }
        )
    }
}

@Composable
private fun BucketRow(
    category: FormattingCategory,
    onClick: () -> Unit
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
                onClickLabel = "Open ${FormattingCategory.label(category)} apps",
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = FluenceSpacing.Base, vertical = 12.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = FormattingCategory.label(category),
                color = colors.textPrimary,
                style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = bucketExplanation(category),
                color = colors.textSecondary,
                style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.Normal)
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}
