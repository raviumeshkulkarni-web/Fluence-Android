package com.groq.voicetyper.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.AudioFocusPreferences
import com.groq.voicetyper.PrivacyPreferences
import com.groq.voicetyper.SecurityUtils
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.navigation.Screen
import com.groq.voicetyper.offline.ModelAssetManager
import com.groq.voicetyper.offline.OfflineEngineType
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.sync.SyncManager
import com.groq.voicetyper.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    val colors = PrecisionTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        color = colors.panel,
        shape = FluenceShapes.Medium,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Base)
            .pressScale(interactionSource)
            .border(1.dp, colors.outlineSubtle, FluenceShapes.Medium)
            .clickable(
                onClickLabel = "Open $title",
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Base),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(FluenceSpacing.Base))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = colors.textPrimary,
                    style = FluenceTypography.titleMedium
                )
                Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                Text(
                    text = summary,
                    color = colors.textSecondary,
                    style = FluenceTypography.bodySmall
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

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateTo: (Screen) -> Unit,
    modifier: Modifier = Modifier,
    syncManager: SyncManager? = null,
    syncSection: @Composable () -> Unit = {}
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current

    val sttPreset = remember { mutableStateOf("groq") }
    val sttModel = remember { mutableStateOf("whisper-large-v3") }
    val llmPreset = remember { mutableStateOf("groq") }
    val llmModel = remember { mutableStateOf("llama-3.3-70b-versatile") }
    val offlineEnabled = remember { mutableStateOf(false) }
    val modelReady = remember { mutableStateOf(false) }
    val duckingEnabled = remember { mutableStateOf(false) }
    val excludedAppCount = remember { mutableStateOf(0) }
    val prefs = remember {
        context.getSharedPreferences(FluencePrefsName, Context.MODE_PRIVATE)
    }
    val themeMode = remember { mutableStateOf(getThemeMode(prefs)) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            sttPreset.value = SecurityUtils.getSttPreset(context)
            sttModel.value = SecurityUtils.getSttModel(context, sttPreset.value)
            llmPreset.value = SecurityUtils.getLlmPreset(context)
            llmModel.value = SecurityUtils.getLlmModel(context, llmPreset.value)
            offlineEnabled.value = OfflinePreferences.isOfflineModeEnabled(context)
            modelReady.value = offlineModelReady(context)
            duckingEnabled.value = AudioFocusPreferences.isDuckingEnabled(context)
            excludedAppCount.value = PrivacyPreferences.getExcludedPackages(context).size
        }
    }

    val providerLabel = when (sttPreset.value) {
        "groq" -> "Groq"
        "mistral" -> "Mistral"
        "custom" -> "Custom"
        else -> "Not configured"
    }

    val llmProviderLabel = when (llmPreset.value) {
        "groq" -> "Groq"
        "mistral" -> "Mistral"
        "custom" -> "Custom"
        else -> "Not configured"
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
                .verticalScroll(rememberScrollState())
        ) {
            SettingsTopBar(
                title = "Settings",
                onBack = onNavigateBack,
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // AI Transcription
            SettingsRow(
                icon = Icons.Default.Mic,
                title = "AI Transcription",
                summary = "$providerLabel \u00b7 ${sttModel.value}",
                onClick = { onNavigateTo(Screen.SttConfig) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // AI Agent Mode
            SettingsRow(
                icon = Icons.Default.AutoAwesome,
                title = "AI Agent Mode",
                summary = "$llmProviderLabel \u00b7 ${llmModel.value}",
                onClick = { onNavigateTo(Screen.AgentConfig) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Offline Transcription
            SettingsRow(
                icon = Icons.Default.PhoneAndroid,
                title = "Offline Transcription",
                summary = when {
                    offlineEnabled.value && modelReady.value -> "Active \u00b7 Model ready"
                    modelReady.value -> "Model installed \u00b7 Disabled"
                    else -> "Model not installed"
                },
                onClick = { onNavigateTo(Screen.OfflineConfig) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Permissions & Services
            SettingsRow(
                icon = Icons.Default.Security,
                title = "Permissions & Services",
                summary = "Microphone, overlay, accessibility, battery",
                onClick = { onNavigateTo(Screen.Permissions) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Privacy & App Exclusions
            SettingsRow(
                icon = Icons.Default.Lock,
                title = "Privacy & App Exclusions",
                summary = when (excludedAppCount.value) {
                    0 -> "Disabled"
                    1 -> "Active \u00b7 1 app excluded"
                    else -> "Active \u00b7 ${excludedAppCount.value} apps excluded"
                },
                onClick = { onNavigateTo(Screen.PrivacyExclusions) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Floating Bubble
            SettingsRow(
                icon = Icons.Default.Circle,
                title = "Floating Bubble",
                summary = "Personalize the bubble look",
                onClick = { onNavigateTo(Screen.BubbleSettings) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Audio Focus Ducking
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
                        .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Base),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.VolumeDown,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(22.dp)
                    )

                    Spacer(modifier = Modifier.width(FluenceSpacing.Base))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Reduce media volume while dictating",
                            color = colors.textPrimary,
                            style = FluenceTypography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                        Text(
                            text = "Duck other apps' audio while recording",
                            color = colors.textSecondary,
                            style = FluenceTypography.bodySmall
                        )
                    }

                    Switch(
                        checked = duckingEnabled.value,
                        onCheckedChange = { checked ->
                            duckingEnabled.value = checked
                            AudioFocusPreferences.setDuckingEnabled(context, checked)
                        },
                        modifier = Modifier.semantics {
                            role = Role.Switch
                            stateDescription = if (duckingEnabled.value) "On" else "Off"
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                            checkedTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                            uncheckedThumbColor = colors.textPrimary,
                            uncheckedTrackColor = colors.panel
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Appearance, 3-option selector: follow the phone day/night
            // state, or pin light / dark. Writes the same `theme_mode` pref
            // MainActivity observes, so the switch is instant, no restart.
            // Unknown/legacy values fall back to dark (see getThemeMode).
            Surface(
                color = colors.panel,
                shape = FluenceShapes.Medium,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluenceSpacing.Base)
                    .border(1.dp, colors.outlineSubtle, FluenceShapes.Medium)
            ) {
                val effectiveDark = resolveDarkTheme(themeMode.value)
                val appearanceSummary = when (themeMode.value) {
                    ThemeModeSystem -> "Follow phone · currently ${if (effectiveDark) "dark" else "light"}"
                    ThemeModeLight -> "Light surfaces, deepened teal accents"
                    else -> "Signature dark surfaces"
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Base)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (effectiveDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(22.dp)
                        )

                        Spacer(modifier = Modifier.width(FluenceSpacing.Base))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Appearance",
                                color = colors.textPrimary,
                                style = FluenceTypography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                            Text(
                                text = appearanceSummary,
                                color = colors.textSecondary,
                                style = FluenceTypography.bodySmall
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

                    val themeOptions = remember {
                        listOf(
                            SegmentChoice(label = "System", accessibilityLabel = "Follow system day night theme"),
                            SegmentChoice(label = "Light", accessibilityLabel = "Light theme"),
                            SegmentChoice(label = "Dark", accessibilityLabel = "Dark theme"),
                        )
                    }
                    val selectedThemeIndex = when (themeMode.value) {
                        ThemeModeSystem -> 0
                        ThemeModeLight -> 1
                        else -> 2
                    }
                    FluenceSegmentedControl(
                        options = themeOptions,
                        selectedIndex = selectedThemeIndex,
                        onSelect = { index ->
                            val mode = when (index) {
                                0 -> ThemeModeSystem
                                1 -> ThemeModeLight
                                else -> ThemeModeDark
                            }
                            themeMode.value = mode
                            setThemeMode(prefs, mode)
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // About
            SettingsRow(
                icon = Icons.Default.Info,
                title = "About",
                summary = "Version \u00b7 Licenses",
                onClick = { onNavigateTo(Screen.About) }
            )
        }
    }
}

private fun offlineModelReady(context: Context): Boolean {
    return when (OfflinePreferences.getEngineType(context)) {
        OfflineEngineType.SENSEVOICE -> ModelAssetManager.isModelReadySync(context)
        OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING ->
            com.groq.voicetyper.offline.v2.MoonshineV2ModelManager.isModelReadySync(
                context, com.groq.voicetyper.offline.v2.MoonshineV2ModelType.SMALL)
        OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING ->
            com.groq.voicetyper.offline.v2.MoonshineV2ModelManager.isModelReadySync(
                context, com.groq.voicetyper.offline.v2.MoonshineV2ModelType.MEDIUM)
    }
}
