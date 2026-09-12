package com.groq.voicetyper.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.FloatingBubblePreferences
import com.groq.voicetyper.FluenceSectionHeader
import com.groq.voicetyper.PillTheme
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.theme.*
import kotlin.math.roundToInt

// ── Floating-bubble settings ────────────────────────────────────────────────
// Dedicated home for every bubble customization: expanded-pill theme presets
// plus the idle-opacity slider (moved here from Permissions & Services so all
// bubble options live in one place). In-app screen, so PrecisionTheme applies.
// The overlay pill itself reads the same prefs with its own listener — theme
// and opacity apply live, no restart.
@Composable
fun BubbleSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
    }
    var pillThemeName by remember {
        mutableStateOf(FloatingBubblePreferences.getPillTheme(context))
    }
    var bubbleOpacity by remember {
        mutableFloatStateOf(FloatingBubblePreferences.getOpacity(context))
    }
    DisposableEffect(prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                FloatingBubblePreferences.KEY_PILL_THEME ->
                    pillThemeName = FloatingBubblePreferences.getPillTheme(context)
                FloatingBubblePreferences.KEY_OPACITY ->
                    bubbleOpacity = FloatingBubblePreferences.getOpacity(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsTopBar(
                title = "Floating Bubble",
                onBack = onNavigateBack,
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )

            Spacer(modifier = Modifier.height(16.dp))

            FluenceSectionHeader(label = "Pill theme")

            Spacer(modifier = Modifier.height(8.dp))

            PillTheme.entries.forEach { preset ->
                PillThemeRow(
                    preset = preset,
                    selected = pillThemeName == preset.prefValue,
                    onSelect = {
                        pillThemeName = preset.prefValue
                        FloatingBubblePreferences.setPillTheme(context, preset.prefValue)
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
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
                        text = "Idle Opacity",
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

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PillThemeRow(
    preset: PillTheme,
    selected: Boolean,
    onSelect: () -> Unit,
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
                onClickLabel = "Select ${preset.label} theme",
                role = Role.Button,
                onClick = onSelect
            )
            .semantics {
                contentDescription = "${preset.label} pill theme"
                stateDescription = if (selected) "Selected" else "Not selected"
            }
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
