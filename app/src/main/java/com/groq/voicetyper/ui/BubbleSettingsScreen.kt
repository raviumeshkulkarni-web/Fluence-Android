package com.groq.voicetyper.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.groq.voicetyper.FeedbackBus
import com.groq.voicetyper.FluenceAccessibilityService
import com.groq.voicetyper.ClassicCollapsedOrb
import com.groq.voicetyper.FloatingBubblePreferences
import com.groq.voicetyper.FluenceSectionHeader
import com.groq.voicetyper.PillTheme
import com.groq.voicetyper.R
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.amethystObsidianGlow
import com.groq.voicetyper.isAccessibilityServiceEnabled
import com.groq.voicetyper.theme.*
import com.groq.voicetyper.ui.icons.FluenceIcons
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
    ) {
        // Frozen header: switch + preview never scroll, so the preview stays
        // visible while the options below slide. Only the inner column scrolls.
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            SettingsTopBar(
                title = "Floating Bubble",
                onBack = onNavigateBack,
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )

            Spacer(modifier = Modifier.height(FluenceSpacing.Base))

            // Master switch — same pref key the accessibility service reads.
            // Single home for the toggle (moved here from Permissions).
            Surface(
                color = colors.panel,
                shape = FluenceShapes.Medium,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluenceSpacing.Base)
                    .border(1.dp, colors.outlineSubtle, FluenceShapes.Medium)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = bubbleEnabled,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                if (!accessibilityEnabled) {
                                    FeedbackBus.show("Enable accessibility service first")
                                } else {
                                    bubbleEnabled = checked
                                    FloatingBubblePreferences.setBubbleEnabled(context, checked)
                                    if (!checked) {
                                        FeedbackBus.show("Floating bubble disabled")
                                    }
                                }
                            }
                        )
                        .padding(
                            horizontal = FluenceSpacing.Base,
                            vertical = FluenceSpacing.Base
                        ),
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
                                !accessibilityEnabled -> "Enable accessibility first"
                                bubbleEnabled -> "On"
                                else -> "Off"
                            },
                            color = colors.textSecondary,
                            style = FluenceTypography.bodySmall
                        )
                    }
                    Switch(
                        checked = bubbleEnabled,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                            checkedTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                            uncheckedThumbColor = colors.textPrimary,
                            uncheckedTrackColor = colors.panel
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Base))

            // Opt-in visibility mode: bubble shows only while the keyboard is
            // open. Off (default) = existing focus-based behavior.
            Surface(
                color = colors.panel,
                shape = FluenceShapes.Medium,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluenceSpacing.Base)
                    .border(1.dp, colors.outlineSubtle, FluenceShapes.Medium)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = imeOnly,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                imeOnly = checked
                                FloatingBubblePreferences.setImeOnly(context, checked)
                            }
                        )
                        .padding(
                            horizontal = FluenceSpacing.Base,
                            vertical = FluenceSpacing.Base
                        ),
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
                            checkedTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                            uncheckedThumbColor = colors.textPrimary,
                            uncheckedTrackColor = colors.panel
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Base))

            BubblePreviewCard(
                pillTheme = pillTheme,
                collapsedTheme = collapsedTheme,
                collapsedStyleName = effectiveCollapsedName,
                bubbleOpacity = bubbleOpacity,
                glowOn = glowOn,
            )

            Spacer(modifier = Modifier.height(FluenceSpacing.Base))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
            FluenceSectionHeader(label = "Day / night auto-switch")

            Spacer(modifier = Modifier.height(8.dp))

            // Opt-in overlay auto-switch: Light + Minimal by day, Obsidian +
            // Classic by night. Manual selections below are preserved but
            // ignored while this is on (rows dim + lock).
            Surface(
                color = colors.panel,
                shape = FluenceShapes.Medium,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluenceSpacing.Base)
                    .border(1.dp, colors.outlineSubtle, FluenceShapes.Medium)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = followSystem,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                followSystem = checked
                                FloatingBubblePreferences.setFollowSystem(context, checked)
                            }
                        )
                        .padding(
                            horizontal = FluenceSpacing.Base,
                            vertical = FluenceSpacing.Base
                        ),
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
                            checkedTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                            uncheckedThumbColor = colors.textPrimary,
                            uncheckedTrackColor = colors.panel
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Base))

            FluenceSectionHeader(label = "Collapsed bubble")

            Spacer(modifier = Modifier.height(8.dp))

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
            Spacer(modifier = Modifier.height(12.dp))
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
            Spacer(modifier = Modifier.height(12.dp))
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

            Spacer(modifier = Modifier.height(FluenceSpacing.Base))

            FluenceSectionHeader(label = "Recording pill")

            Spacer(modifier = Modifier.height(8.dp))

            PillTheme.entries.forEach { preset ->
                PillThemeRow(
                    preset = preset,
                    selected = pillThemeName == preset.prefValue,
                    enabled = !followSystem,
                    onSelect = {
                        pillThemeName = preset.prefValue
                        FloatingBubblePreferences.setPillTheme(context, preset.prefValue)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            ModeLegendRow(dot = pillTheme.waveT, label = "Transcription mode")

            Spacer(modifier = Modifier.height(8.dp))

            ModeLegendRow(dot = pillTheme.waveA, label = "Agent mode")

            Spacer(modifier = Modifier.height(12.dp))

            FluenceSectionHeader(label = "Pill glow")

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                color = colors.panel,
                shape = FluenceShapes.Medium,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluenceSpacing.Base)
                    .border(1.dp, colors.outlineSubtle, FluenceShapes.Medium)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = glowOn,
                            role = Role.Switch,
                            onValueChange = { checked ->
                                glowOn = checked
                                FloatingBubblePreferences.setGlowEnabled(context, checked)
                            }
                        )
                        .padding(
                            horizontal = FluenceSpacing.Base,
                            vertical = FluenceSpacing.Base
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Outer glow",
                            color = colors.textPrimary,
                            style = FluenceTypography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                        Text(
                            text = "Glow around the pill",
                            color = colors.textSecondary,
                            style = FluenceTypography.bodySmall
                        )
                    }

                    Switch(
                        checked = glowOn,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                            checkedTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                            uncheckedThumbColor = colors.textPrimary,
                            uncheckedTrackColor = colors.panel
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            FluenceSectionHeader(label = "Idle opacity")

            Spacer(modifier = Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluenceSpacing.Base)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Idle opacity",
                        color = colors.textSecondary,
                        style = FluenceTypography.bodyMedium
                    )
                    Text(
                        text = "${(bubbleOpacity * 100).roundToInt()}%",
                        color = colors.textPrimary,
                        style = FluenceTypography.titleMedium
                    )
                }
                Slider(
                    value = bubbleOpacity,
                    onValueChange = { newValue ->
                        bubbleOpacity = newValue
                        FloatingBubblePreferences.setOpacity(context, newValue)
                    },
                    valueRange = FloatingBubblePreferences.MIN_OPACITY..FloatingBubblePreferences.MAX_OPACITY,
                    colors = SliderDefaults.colors(
                        thumbColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                        activeTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                        inactiveTrackColor = colors.outlineSubtle
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Lg))
            }
        }
    }
}

@Composable
private fun ModeLegendRow(
    dot: Color,
    label: String,
) {
    val colors = PrecisionTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Base),
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
            text = label,
            color = colors.textSecondary,
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
    Surface(
        color = colors.panel,
        shape = FluenceShapes.Medium,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Base)
            .border(
                1.dp,
                if (selected) colors.textPrimary else colors.outlineSubtle,
                FluenceShapes.Medium
            )
            .clickable(
                enabled = enabled,
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
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = FluenceSpacing.Base,
                    vertical = FluenceSpacing.Base
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(
                        1.dp,
                        if (selected) colors.textPrimary else colors.inputBorder,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(colors.textPrimary)
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
    Surface(
        color = colors.panel,
        shape = FluenceShapes.Medium,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Base)
            .border(
                1.dp,
                if (selected) colors.textPrimary else colors.outlineSubtle,
                FluenceShapes.Medium
            )
            .clickable(
                enabled = enabled,
                onClickLabel = "Select $title collapsed style",
                role = Role.RadioButton,
                onClick = onSelect
            )
            .semantics {
                contentDescription = "$title collapsed style"
                stateDescription = when {
                    !enabled -> "Locked by day night auto-switch"
                    selected -> "Selected"
                    else -> "Not selected"
                }
            }
            .alpha(if (enabled) 1f else 0.4f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = FluenceSpacing.Base,
                    vertical = FluenceSpacing.Base
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(
                        1.dp,
                        if (selected) colors.textPrimary else colors.inputBorder,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(colors.textPrimary)
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
}

@Composable
private fun BubblePreviewCard(
    pillTheme: PillTheme,
    collapsedTheme: PillTheme,
    collapsedStyleName: String,
    bubbleOpacity: Float,
    glowOn: Boolean,
) {
    val colors = PrecisionTheme.colors
    Surface(
        color = colors.panel,
        shape = FluenceShapes.Medium,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Base)
            .border(1.dp, colors.outlineSubtle, FluenceShapes.Medium)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = FluenceSpacing.Base,
                    vertical = FluenceSpacing.Base
                ),
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
            Spacer(modifier = Modifier.height(FluenceSpacing.Base))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Collapsed idle — dimmed branch + idle opacity, like
                    // FloatingBubbleUI dimmed=true + graphicsLayer(alpha=dimAlpha).
                    // Uses the collapsed theme (independent from the pill).
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Expanded recording — active branch at full opacity, like
                    // FloatingBubbleUI dimmed=false + graphicsLayer(alpha=1f).
                    Box(
                        modifier = Modifier
                            .size(width = 240.dp, height = 64.dp)
                            .amethystObsidianGlow(
                                isExpanded = true,
                                theme = pillTheme,
                                glowOn = glowOn,
                                shape = RoundedCornerShape(32.dp),
                                dimmed = false
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
                                StaticWaveform(theme = pillTheme)
                            }
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(pillTheme.confirmBg, CircleShape),
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
                                        color = pillTheme.confirmIcon,
                                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Recording",
                        color = colors.textSecondary,
                        style = FluenceTypography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun StaticWaveform(theme: PillTheme) {
    // Frozen copy of SiriWaveform's draw pass: same 3-stroke structure,
    // same edge-fade gradients, fixed amplitude/phase instead of live input.
    val primaryColor = theme.waveT
    val forefrontColor = theme.waveTFore
    val amplitude = 0.35f
    val phase1 = 0.6f
    val phase2 = -0.42f
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2
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
