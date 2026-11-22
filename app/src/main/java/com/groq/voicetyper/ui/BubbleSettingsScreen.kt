package com.groq.voicetyper.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.HorizontalDivider
import com.groq.voicetyper.FeedbackBus
import com.groq.voicetyper.FluenceAccessibilityService
import com.groq.voicetyper.ClassicCollapsedOrb
import com.groq.voicetyper.FloatingBubblePreferences
import com.groq.voicetyper.PillTheme
import com.groq.voicetyper.R
import com.groq.voicetyper.SettingsDivider
import com.groq.voicetyper.SettingsJointCard
import com.groq.voicetyper.SettingsSectionHeader
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.amethystObsidianGlow
import com.groq.voicetyper.isAccessibilityServiceEnabled
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
import androidx.compose.ui.geometry.Offset
import com.groq.voicetyper.WaveformBarMath
import com.groq.voicetyper.ui.icons.FluenceIcons
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

// ── Floating-bubble settings ────────────────────────────────────────────────
// Dedicated home for every bubble customization: master switch (moved here
// from Permissions & Services), frozen live preview, independent collapsed
// style, expanded-pill presets, glow, idle opacity. In-app screen, so
// PrecisionTheme applies.
// The overlay pill itself reads the same prefs with its own listener — theme
// and opacity apply live, no restart.
@Composable
fun BubbleSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember {
        context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
    }
    var pillThemeName by remember {
        mutableStateOf(FloatingBubblePreferences.getPillTheme(context))
    }
    var collapsedStyleName by remember {
        mutableStateOf(FloatingBubblePreferences.getCollapsedStyle(context))
    }
    var waveformStyleName by remember {
        mutableStateOf(FloatingBubblePreferences.getWaveformStyle(context))
    }
    var previewAgentMode by remember { mutableStateOf(false) }
    // Day/night auto-switch (opt-in). When on, the preview and the overlay
    // resolve the fixed day/night pair at read time; the manual selections
    // above are preserved but ignored until follow is turned off.
    var followSystem by remember {
        mutableStateOf(FloatingBubblePreferences.isFollowSystem(context))
    }
    val systemDark = isSystemInDarkTheme()
    val effectivePillName =
        FloatingBubblePreferences.getEffectivePillTheme(context, systemDark).let {
            if (followSystem) it else pillThemeName
        }
    val effectiveCollapsedName =
        FloatingBubblePreferences.getEffectiveCollapsedStyle(context, systemDark).let {
            if (followSystem) it else collapsedStyleName
        }
    val pillTheme = remember(effectivePillName) { PillTheme.forName(effectivePillName) }
    // Same independence rule as the overlay: collapsed follows its own style,
    // expanded follows the pill theme. Minimal adapts to Light for contrast.
    val isMinimalCollapsed = collapsedStyleName == FloatingBubblePreferences.COLLAPSED_MINIMAL
    val isClassicCollapsed = collapsedStyleName == FloatingBubblePreferences.COLLAPSED_CLASSIC
    val collapsedTheme = remember(effectiveCollapsedName, effectivePillName) {
        when {
            effectiveCollapsedName == FloatingBubblePreferences.COLLAPSED_MINIMAL &&
                effectivePillName == FloatingBubblePreferences.PILL_THEME_LIGHT -> PillTheme.LIGHT
            effectiveCollapsedName == FloatingBubblePreferences.COLLAPSED_MINIMAL -> PillTheme.MONO
            else -> PillTheme.OBSIDIAN
        }
    }
    var bubbleOpacity by remember {
        mutableFloatStateOf(FloatingBubblePreferences.getOpacity(context))
    }
    var glowOn by remember {
        mutableStateOf(FloatingBubblePreferences.isGlowEnabled(context))
    }
    var bubbleEnabled by remember {
        mutableStateOf(FloatingBubblePreferences.isBubbleEnabled(context))
    }
    // Opt-in: bubble appears only while the soft keyboard is open. Default
    // off = today's focus-based behavior, unchanged.
    var imeOnly by remember {
        mutableStateOf(FloatingBubblePreferences.isImeOnly(context))
    }
    var accessibilityEnabled by remember {
        mutableStateOf(isAccessibilityServiceEnabled(context, FluenceAccessibilityService::class.java))
    }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                FloatingBubblePreferences.KEY_PILL_THEME ->
                    pillThemeName = FloatingBubblePreferences.getPillTheme(context)
                FloatingBubblePreferences.KEY_COLLAPSED_STYLE ->
                    collapsedStyleName = FloatingBubblePreferences.getCollapsedStyle(context)
                FloatingBubblePreferences.KEY_OPACITY ->
                    bubbleOpacity = FloatingBubblePreferences.getOpacity(context)
                FloatingBubblePreferences.KEY_GLOW_ENABLED ->
                    glowOn = FloatingBubblePreferences.isGlowEnabled(context)
                FloatingBubblePreferences.KEY_FOLLOW_SYSTEM ->
                    followSystem = FloatingBubblePreferences.isFollowSystem(context)
                FloatingBubblePreferences.KEY_BUBBLE_ENABLED ->
                    bubbleEnabled = FloatingBubblePreferences.isBubbleEnabled(context)
                FloatingBubblePreferences.KEY_BUBBLE_IME_ONLY ->
                    imeOnly = FloatingBubblePreferences.isImeOnly(context)
                FloatingBubblePreferences.KEY_WAVEFORM_STYLE ->
                    waveformStyleName = FloatingBubblePreferences.getWaveformStyle(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // Accessibility can change while this screen is in the background
    // (user leaves for system settings and returns) — refresh on resume so
    // the enable toggle never shows a stale gate. Same pattern as
    // PermissionsScreen; prefs listener above covers the toggle itself.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled =
                    isAccessibilityServiceEnabled(context, FluenceAccessibilityService::class.java)
                bubbleEnabled = FloatingBubblePreferences.isBubbleEnabled(context)
                imeOnly = FloatingBubblePreferences.isImeOnly(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FluenceSpacing.Base)
        ) {
            SettingsTopBar(
                title = "Floating Bubble",
                onBack = onNavigateBack
            )

            Spacer(modifier = Modifier.height(FluenceSpacing.Xs))

            // Pinned compact preview card stays visible while scrolling options below.
            SettingsJointCard {
                BubblePreviewContent(
                    pillTheme = pillTheme,
                    collapsedTheme = collapsedTheme,
                    collapsedStyleName = effectiveCollapsedName,
                    waveformStyleName = waveformStyleName,
                    bubbleOpacity = bubbleOpacity,
                    glowOn = glowOn,
                    previewAgentMode = previewAgentMode,
                    onTogglePreviewMode = { previewAgentMode = !previewAgentMode },
                )
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

            // Scrollable settings below the pinned preview card
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "A persistent floating bubble for instant voice typing and AI agent actions across any app.",
                    color = colors.textSecondary,
                    style = FluenceTypography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = FluenceSpacing.Sm)
                )

                // Section 1: Activation & Visibility
                SettingsSectionHeader(
                    title = "Activation & Visibility",
                    description = "Master switch and keyboard-visibility trigger"
                )

                SettingsJointCard {
                    val masterInteraction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale(masterInteraction)
                            .toggleable(
                                value = bubbleEnabled,
                                interactionSource = masterInteraction,
                                indication = LocalIndication.current,
                                role = Role.Switch,
                                onValueChange = { checked ->
                                    if (!accessibilityEnabled) {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                        }.onFailure {
                                            FeedbackBus.show("Enable accessibility service first")
                                        }
                                    } else {
                                        bubbleEnabled = checked
                                        FloatingBubblePreferences.setBubbleEnabled(context, checked)
                                        if (!checked) {
                                            FeedbackBus.show("Floating bubble disabled")
                                        }
                                    }
                                }
                            )
                            .padding(horizontal = FluenceSpacing.Base, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Floating bubble",
                                color = colors.textPrimary,
                                style = FluenceTypography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                            Text(
                                text = when {
                                    !accessibilityEnabled -> "Required · tap to open Accessibility settings"
                                    bubbleEnabled -> "On"
                                    else -> "Off"
                                },
                                color = if (!accessibilityEnabled) colors.textLink else colors.textSecondary,
                                style = FluenceTypography.bodySmall
                            )
                        }
                        Switch(
                            checked = bubbleEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                                checkedTrackColor = colors.activeControl,
                                uncheckedThumbColor = colors.textPrimary,
                                uncheckedTrackColor = colors.panel,
                                uncheckedBorderColor = colors.outlineSubtle
                            )
                        )
                    }

                    SettingsDivider()

                    val imeInteraction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (bubbleEnabled) 1f else 0.55f)
                            .pressScale(imeInteraction)
                            .toggleable(
                                value = imeOnly,
                                interactionSource = imeInteraction,
                                indication = LocalIndication.current,
                                role = Role.Switch,
                                onValueChange = { checked ->
                                    imeOnly = checked
                                    FloatingBubblePreferences.setImeOnly(context, checked)
                                }
                            )
                            .padding(horizontal = FluenceSpacing.Base, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show only when keyboard is visible",
                                color = colors.textPrimary,
                                style = FluenceTypography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                            Text(
                                text = if (imeOnly) {
                                    "On · bubble appears only while typing"
                                } else {
                                    "Bubble shows whenever a text field is focused"
                                },
                                color = colors.textSecondary,
                                style = FluenceTypography.bodySmall
                            )
                        }
                        Switch(
                            checked = imeOnly,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                                checkedTrackColor = colors.activeControl,
                                uncheckedThumbColor = colors.textPrimary,
                                uncheckedTrackColor = colors.panel,
                                uncheckedBorderColor = colors.outlineSubtle
                            )
                        )
                    }
                }

                // Section 2: Day / Night Auto-Switch
                SettingsSectionHeader(
                    title = "Day / Night Auto-Switch",
                    description = "Automatically adapt bubble style to phone theme"
                )

                SettingsJointCard {
                    val followInteraction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale(followInteraction)
                            .toggleable(
                                value = followSystem,
                                interactionSource = followInteraction,
                                indication = LocalIndication.current,
                                role = Role.Switch,
                                onValueChange = { checked ->
                                    followSystem = checked
                                    FloatingBubblePreferences.setFollowSystem(context, checked)
                                }
                            )
                            .padding(horizontal = FluenceSpacing.Base, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Follow phone day / night",
                                color = colors.textPrimary,
                                style = FluenceTypography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                            Text(
                                text = if (followSystem) {
                                    "On · now showing ${if (systemDark) "night (Obsidian + Classic)" else "day (Light + Minimal)"}"
                                } else {
                                    "Light + Minimal by day, Obsidian + Classic by night"
                                },
                                color = colors.textSecondary,
                                style = FluenceTypography.bodySmall
                            )
                        }
                        Switch(
                            checked = followSystem,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                                checkedTrackColor = colors.activeControl,
                                uncheckedThumbColor = colors.textPrimary,
                                uncheckedTrackColor = colors.panel,
                                uncheckedBorderColor = colors.outlineSubtle
                            )
                        )
                    }
                }

                // Section 4: Collapsed Bubble
                SettingsSectionHeader(
                    title = "Collapsed Bubble",
                    description = "Choose the collapsed floating orb appearance"
                )

                SettingsJointCard {
                    CollapsedStyleRow(
                        title = "Original",
                        description = "Signature orb of Fluence",
                        selected = !isMinimalCollapsed && !isClassicCollapsed,
                        enabled = !followSystem,
                        onSelect = {
                            collapsedStyleName = FloatingBubblePreferences.COLLAPSED_ORIGINAL
                            FloatingBubblePreferences.setCollapsedStyle(
                                context,
                                FloatingBubblePreferences.COLLAPSED_ORIGINAL
                            )
                        }
                    )
                    SettingsDivider()
                    CollapsedStyleRow(
                        title = "Classic",
                        description = "Amethyst glow, equalizer mark",
                        selected = isClassicCollapsed,
                        enabled = !followSystem,
                        onSelect = {
                            collapsedStyleName = FloatingBubblePreferences.COLLAPSED_CLASSIC
                            FloatingBubblePreferences.setCollapsedStyle(
                                context,
                                FloatingBubblePreferences.COLLAPSED_CLASSIC
                            )
                        }
                    )
                    SettingsDivider()
                    CollapsedStyleRow(
                        title = "Minimal",
                        description = "Waveform mark, no color",
                        selected = isMinimalCollapsed,
                        enabled = !followSystem,
                        onSelect = {
                            collapsedStyleName = FloatingBubblePreferences.COLLAPSED_MINIMAL
                            FloatingBubblePreferences.setCollapsedStyle(
                                context,
                                FloatingBubblePreferences.COLLAPSED_MINIMAL
                            )
                        }
                    )
                }

                // Section 5: Recording Pill
                SettingsSectionHeader(
                    title = "Recording Pill",
                    description = "Visual theme and color scheme during active dictation"
                )

                SettingsJointCard {
                    PillTheme.entries.forEachIndexed { index, preset ->
                        PillThemeRow(
                            preset = preset,
                            selected = pillThemeName == preset.prefValue,
                            enabled = !followSystem,
                            onSelect = {
                                pillThemeName = preset.prefValue
                                FloatingBubblePreferences.setPillTheme(context, preset.prefValue)
                            }
                        )
                        if (index < PillTheme.entries.size - 1) {
                            SettingsDivider()
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                ModeLegendRow(
                    dot = pillTheme.waveT,
                    label = "Transcription mode",
                    active = !previewAgentMode,
                    onClick = { previewAgentMode = false }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ModeLegendRow(
                    dot = pillTheme.waveA,
                    label = "Agent mode",
                    active = previewAgentMode,
                    onClick = { previewAgentMode = true }
                )

                // Waveform animation (Windows Floating Bubble parity). Paint-only
                // pick: colors stay mode-owned in every style.
                Spacer(modifier = Modifier.height(24.dp))
                SettingsSectionHeader(
                    title = "Waveform Animation",
                    description = "Choose how the recording waveform animates"
                )

                SettingsJointCard {
                    CollapsedStyleRow(
                        title = "Waves",
                        description = "Layered ripples. The animation Fluence ships with",
                        selected = waveformStyleName == FloatingBubblePreferences.WAVEFORM_WAVES,
                        onSelect = {
                            waveformStyleName = FloatingBubblePreferences.WAVEFORM_WAVES
                            FloatingBubblePreferences.setWaveformStyle(
                                context,
                                FloatingBubblePreferences.WAVEFORM_WAVES
                            )
                        }
                    )
                    SettingsDivider()
                    CollapsedStyleRow(
                        title = "Amp Bars",
                        description = "Equalizer bars that track how loud you speak",
                        selected = waveformStyleName == FloatingBubblePreferences.WAVEFORM_BARS,
                        onSelect = {
                            waveformStyleName = FloatingBubblePreferences.WAVEFORM_BARS
                            FloatingBubblePreferences.setWaveformStyle(
                                context,
                                FloatingBubblePreferences.WAVEFORM_BARS
                            )
                        }
                    )
                }

                // Section 6: Appearance & Opacity
                SettingsSectionHeader(
                    title = "Appearance & Opacity",
                    description = "Configure outer aura and resting transparency"
                )

                SettingsJointCard {
                    val glowInteraction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale(glowInteraction)
                            .toggleable(
                                value = glowOn,
                                interactionSource = glowInteraction,
                                indication = LocalIndication.current,
                                role = Role.Switch,
                                onValueChange = { checked ->
                                    glowOn = checked
                                    FloatingBubblePreferences.setGlowEnabled(context, checked)
                                }
                            )
                            .padding(horizontal = FluenceSpacing.Base, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Halo Glow",
                                color = colors.textPrimary,
                                style = FluenceTypography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                            Text(
                                text = "Adds a soft glow around the bubble. Purple while you dictate, teal in Agent mode",
                                color = colors.textSecondary,
                                style = FluenceTypography.bodySmall
                            )
                        }

                        Switch(
                            checked = glowOn,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                                checkedTrackColor = colors.activeControl,
                                uncheckedThumbColor = colors.textPrimary,
                                uncheckedTrackColor = colors.panel,
                                uncheckedBorderColor = colors.outlineSubtle
                            )
                        )
                    }

                    SettingsDivider()

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FluenceSpacing.Base, vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Idle opacity",
                                color = colors.textPrimary,
                                style = FluenceTypography.titleMedium
                            )
                            Text(
                                text = "${(bubbleOpacity * 100).roundToInt()}%",
                                color = colors.textPrimary,
                                style = FluenceTypography.titleMedium
                            )
                        }
                        Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
                        Slider(
                            value = bubbleOpacity,
                            onValueChange = { newValue ->
                                bubbleOpacity = newValue
                                FloatingBubblePreferences.setOpacity(context, newValue)
                            },
                            valueRange = FloatingBubblePreferences.MIN_OPACITY..FloatingBubblePreferences.MAX_OPACITY,
                            colors = SliderDefaults.colors(
                                thumbColor = colors.activeControl,
                                activeTrackColor = colors.activeControl,
                                inactiveTrackColor = colors.outlineSubtle
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(FluenceSpacing.Xl))
            }
        }
    }
}

@Composable
private fun ModeLegendRow(
    dot: Color,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = PrecisionTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(FluenceShapes.Small)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = "Preview $label",
                onClick = onClick
            )
            .padding(vertical = FluenceSpacing.Xs, horizontal = FluenceSpacing.Xxs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(dot)
                .border(1.dp, colors.outlineSubtle, CircleShape)
        )
        Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
        Text(
            text = if (active) "$label · previewing" else label,
            color = if (active) colors.textPrimary else colors.textSecondary,
            style = FluenceTypography.bodyMedium
        )
    }
}

@Composable
private fun PillThemeRow(
    preset: PillTheme,
    selected: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = PrecisionTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClickLabel = "Select ${preset.label} theme",
                role = Role.RadioButton,
                onClick = onSelect
            )
            .semantics {
                contentDescription = "${preset.label} pill theme"
                stateDescription = when {
                    !enabled -> "Locked by day night auto-switch"
                    selected -> "Selected"
                    else -> "Not selected"
                }
            }
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = FluenceSpacing.Base, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    1.dp,
                    if (selected) colors.selectedControl else colors.inputBorder,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colors.selectedControl)
                )
            }
        }

        Spacer(modifier = Modifier.width(FluenceSpacing.Base))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = preset.label,
                color = colors.textPrimary,
                style = FluenceTypography.titleMedium
            )
            Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
            Text(
                text = preset.description,
                color = colors.textSecondary,
                style = FluenceTypography.bodySmall
            )
        }
    }
}

// ── Static preview — exact production paints, zero service dependency ────────
// Reuses the real overlay code paths verbatim:
// - same PillTheme color fields (no copied hex)
// - same amethystObsidianGlow modifier (same glow/border/dimmed branch)
// - same geometry: collapsed 56dp / 28dp radius, expanded 240x64dp / 32dp
//   radius, cancel 44dp + X 14dp/2dp, wave well 48dp / 24dp radius,
//   confirm 44dp + check 16dp/2.5dp
// Static on purpose: no BubbleController flows, no pointerInput, no
// LaunchedEffect/amplitude. Waveform is a frozen sine at 0.35 amplitude so the
// preview never animates or recomposes at 60fps inside settings.
@Composable
private fun CollapsedStyleRow(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = PrecisionTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactionSource)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClickLabel = "Select $title",
                role = Role.RadioButton,
                onClick = onSelect
            )
            .semantics {
                contentDescription = title
                stateDescription = when {
                    !enabled -> "Locked by day night auto-switch"
                    selected -> "Selected"
                    else -> "Not selected"
                }
            }
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = FluenceSpacing.Base, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    1.dp,
                    if (selected) colors.selectedControl else colors.inputBorder,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(colors.selectedControl)
                )
            }
        }
        Spacer(modifier = Modifier.width(FluenceSpacing.Base))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = FluenceTypography.titleMedium
            )
            Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
            Text(
                text = description,
                color = colors.textSecondary,
                style = FluenceTypography.bodySmall
            )
        }
    }
}

@Composable
private fun BubblePreviewContent(
    pillTheme: PillTheme,
    collapsedTheme: PillTheme,
    collapsedStyleName: String,
    waveformStyleName: String,
    bubbleOpacity: Float,
    glowOn: Boolean,
    previewAgentMode: Boolean,
    onTogglePreviewMode: () -> Unit,
) {
    val colors = PrecisionTheme.colors
    val obsidianAgentConfirm = previewAgentMode &&
        pillTheme.prefValue == FloatingBubblePreferences.PILL_THEME_OBSIDIAN
    val confirmBgColor = if (obsidianAgentConfirm) pillTheme.waveA else pillTheme.confirmBg
    val confirmIconColor = if (obsidianAgentConfirm) PillTheme.HIGH_CONTRAST.confirmIcon else pillTheme.confirmIcon

    val idlePreview: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .amethystObsidianGlow(
                        isExpanded = false,
                        theme = collapsedTheme,
                        glowOn = glowOn,
                        shape = RoundedCornerShape(28.dp),
                        dimmed = true
                    )
                    .clip(RoundedCornerShape(28.dp))
                    .graphicsLayer { alpha = bubbleOpacity },
                contentAlignment = Alignment.Center
            ) {
                when (collapsedStyleName) {
                    FloatingBubblePreferences.COLLAPSED_MINIMAL -> Icon(
                        imageVector = FluenceIcons.AudioWaveform,
                        contentDescription = null,
                        tint = collapsedTheme.cancelIcon,
                        modifier = Modifier.size(22.dp)
                    )
                    FloatingBubblePreferences.COLLAPSED_CLASSIC -> ClassicCollapsedOrb()
                    else -> Image(
                        painter = painterResource(id = R.drawable.ic_fluence_logo),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Idle",
                color = colors.textSecondary,
                style = FluenceTypography.bodySmall
            )
        }
    }

    val recordingPreview: @Composable () -> Unit = {
        val previewInteraction = remember { MutableInteractionSource() }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .pressScale(previewInteraction)
                .clickable(
                    interactionSource = previewInteraction,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = if (previewAgentMode) "Preview transcription mode" else "Preview agent mode",
                    onClick = onTogglePreviewMode
                )
        ) {
            Box(
                modifier = Modifier
                    .size(width = 240.dp, height = 64.dp)
                    .amethystObsidianGlow(
                        isExpanded = true,
                        theme = pillTheme,
                        glowOn = glowOn,
                        shape = RoundedCornerShape(32.dp),
                        dimmed = false,
                        agentMode = previewAgentMode
                    )
                    .clip(RoundedCornerShape(32.dp))
                    .graphicsLayer { alpha = 1f },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(pillTheme.cancelWell, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(14.dp)) {
                            val w = size.width
                            val h = size.height
                            drawLine(
                                color = pillTheme.cancelIcon,
                                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                                end = androidx.compose.ui.geometry.Offset(w, h),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            drawLine(
                                color = pillTheme.cancelIcon,
                                start = androidx.compose.ui.geometry.Offset(w, 0f),
                                end = androidx.compose.ui.geometry.Offset(0f, h),
                                strokeWidth = 2.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(pillTheme.waveWellBg)
                            .border(1.dp, pillTheme.waveWellBorder, RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        StaticWaveform(
                            theme = pillTheme,
                            waveformStyle = waveformStyleName,
                            agentMode = previewAgentMode,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(confirmBgColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(16.dp)) {
                            val w = size.width
                            val h = size.height
                            val path = Path().apply {
                                moveTo(w * 0.2f, h * 0.5f)
                                lineTo(w * 0.45f, h * 0.75f)
                                lineTo(w * 0.85f, h * 0.25f)
                            }
                            drawPath(
                                path = path,
                                color = confirmIconColor,
                                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (previewAgentMode) "Agent · tap for Recording" else "Recording · tap for Agent",
                color = colors.textSecondary,
                style = FluenceTypography.bodySmall
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Sm, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Preview",
            color = colors.textPrimary,
            style = FluenceTypography.titleMedium
        )
        Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
        Text(
            text = "Idle at ${(bubbleOpacity * 100).roundToInt()}% · recording at 100%",
            color = colors.textSecondary,
            style = FluenceTypography.bodySmall
        )
        Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            if (maxWidth >= 304.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    idlePreview()
                    recordingPreview()
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(FluenceSpacing.Md)
                ) {
                    recordingPreview()
                    idlePreview()
                }
            }
        }
    }
}

@Composable
private fun StaticWaveform(
    theme: PillTheme,
    waveformStyle: String = FloatingBubblePreferences.WAVEFORM_WAVES,
    agentMode: Boolean = false,
) {
    // Frozen copy of SiriWaveform's draw pass: same 3-stroke structure,
    // same edge-fade gradients, fixed amplitude/phase instead of live input.
    // Amp Bars mirrors the frozen Windows preview (same bar math, fixed
    // phase), so the picker row shows the shipped shapes without motion.
    val primaryColor = if (agentMode) theme.waveA else theme.waveT
    val forefrontColor = if (agentMode) theme.waveAFore else theme.waveTFore
    val amplitude = 0.35f
    val phase1 = 0.6f
    val phase2 = -0.42f
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        if (waveformStyle == FloatingBubblePreferences.WAVEFORM_BARS) {
            if (width < 4f || height < 4f) return@Canvas
            val dpPx = 1.dp.toPx()
            val widthDp = width / dpPx
            val insetPx = WaveformBarMath.horizontalInsetDp(widthDp) * dpPx
            val usableWidth = (width - insetPx * 2f).coerceAtLeast(1f)
            val count = WaveformBarMath.barCountForWidthDp(widthDp)
            val maxHalfH = height * 0.43f
            val minHalfH = 1.6.dp.toPx()
            val barStroke = 2.6.dp.toPx()
            val bedStroke = 3.0.dp.toPx()
            val glowStroke = barStroke * 1.95f
            val previewLevel = 0.52f
            val bloomAlpha = WaveformBarMath.smoothstep(0.14f, 0.72f, previewLevel)
            val sameTone = primaryColor == forefrontColor

            val bedBrush = Brush.horizontalGradient(
                colors = listOf(
                    primaryColor.copy(alpha = if (sameTone) 0.18f else 0.30f),
                    primaryColor.copy(alpha = if (sameTone) 0.26f else 0.44f),
                    primaryColor.copy(alpha = if (sameTone) 0.18f else 0.30f)
                ),
                startX = 0f,
                endX = width
            )
            val foreBrush = Brush.horizontalGradient(
                colors = listOf(
                    forefrontColor.copy(alpha = if (sameTone) 0.82f else 0.78f),
                    forefrontColor.copy(alpha = 0.96f),
                    forefrontColor.copy(alpha = if (sameTone) 0.82f else 0.78f)
                ),
                startX = 0f,
                endX = width
            )
            val glowBrush = Brush.horizontalGradient(
                colors = listOf(
                    forefrontColor.copy(alpha = if (sameTone) 0.08f else 0.14f),
                    forefrontColor.copy(alpha = if (sameTone) 0.14f else 0.22f),
                    forefrontColor.copy(alpha = if (sameTone) 0.08f else 0.14f)
                ),
                startX = 0f,
                endX = width
            )

            for (i in 0 until count) {
                val u = WaveformBarMath.barPositionFraction(i, count)
                val x = insetPx + u * usableWidth
                val frac = WaveformBarMath.barTargetFraction(u, phase1, previewLevel)
                val halfH = max(minHalfH, frac * maxHalfH)
                val bedHalfH = max(minHalfH, (frac * maxHalfH * 1.05f).coerceAtMost(height * 0.46f))
                drawLine(
                    brush = bedBrush,
                    start = Offset(x, centerY - bedHalfH),
                    end = Offset(x, centerY + bedHalfH),
                    strokeWidth = bedStroke,
                    cap = StrokeCap.Round
                )
                if (bloomAlpha > 0.01f) {
                    drawLine(
                        brush = glowBrush,
                        start = Offset(x, centerY - halfH),
                        end = Offset(x, centerY + halfH),
                        strokeWidth = glowStroke,
                        cap = StrokeCap.Round,
                        alpha = bloomAlpha
                    )
                }
                drawLine(
                    brush = foreBrush,
                    start = Offset(x, centerY - halfH),
                    end = Offset(x, centerY + halfH),
                    strokeWidth = barStroke,
                    cap = StrokeCap.Round
                )
            }
            return@Canvas
        }

        val activeAmplitude = (amplitude * 0.8f + 0.1f) * (height * 0.45f)
        val gradientBrush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, forefrontColor.copy(alpha = 0.9f), Color.Transparent),
            startX = 0f,
            endX = width
        )
        val bgGradientBrush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, primaryColor.copy(alpha = 0.4f), Color.Transparent),
            startX = 0f,
            endX = width
        )
        fun waveY(xVal: Float, freq: Float, phase: Float, scale: Float, jitter: Float): Float {
            val envelope = sin((xVal / width) * Math.PI.toFloat())
            val angle = (xVal / width) * 2f * Math.PI.toFloat() * freq + phase
            val vibration = sin(xVal * 0.1f + phase * 3f) * amplitude * jitter
            return centerY + (sin(angle) * activeAmplitude * scale + vibration) * envelope
        }
        val path1 = Path().apply {
            moveTo(0f, centerY)
            for (x in 0..width.toInt() step 6) {
                lineTo(x.toFloat(), waveY(x.toFloat(), 1.5f, phase1, 0.5f, 4f))
            }
        }
        drawPath(path = path1, brush = bgGradientBrush, style = Stroke(width = 1.5.dp.toPx()))
        val path2 = Path().apply {
            moveTo(0f, centerY)
            for (x in 0..width.toInt() step 6) {
                lineTo(x.toFloat(), waveY(x.toFloat(), 2.5f, phase2, 0.7f, 3f))
            }
        }
        drawPath(path = path2, brush = bgGradientBrush, style = Stroke(width = 1.8.dp.toPx()))
        val path3 = Path().apply {
            moveTo(0f, centerY)
            for (x in 0..width.toInt() step 6) {
                lineTo(x.toFloat(), waveY(x.toFloat(), 1.2f, (phase1 - phase2) * 0.5f, 0.9f, 5f))
            }
        }
        drawPath(path = path3, brush = gradientBrush, style = Stroke(width = 2.dp.toPx()))
    }
}
