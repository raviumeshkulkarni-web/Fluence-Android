package com.groq.voicetyper.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
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
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.snippets.SnippetPreferences
import com.groq.voicetyper.sync.SyncManager
import com.groq.voicetyper.sync.SyncStatus
import com.groq.voicetyper.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        color = Panel,
        shape = FluenceShapes.Medium,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Base)
            .pressScale(interactionSource)
            .border(1.dp, OutlineSubtle, FluenceShapes.Medium)
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
                tint = TextSecondary,
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(FluenceSpacing.Base))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = TextPrimary,
                    style = FluenceTypography.titleMedium
                )
                Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                Text(
                    text = summary,
                    color = TextSecondary,
                    style = FluenceTypography.bodySmall
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun EntranceRow(index: Int, content: @Composable () -> Unit) {
    // Reduced motion: render rows in final state — no fade/slide, no stagger.
    if (LocalMotionPreferences.current.reducedMotion) {
        content()
        return
    }
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(durationMillis = FluenceMotion.durationStructural, delayMillis = index * 40)) +
            slideInVertically(tween(durationMillis = FluenceMotion.durationStructural, delayMillis = index * 40)) { it / 8 }
    ) {
        content()
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
    val context = LocalContext.current

    val sttPreset = remember { mutableStateOf(SecurityUtils.getSttPreset(context)) }
    val sttModel = remember { mutableStateOf(SecurityUtils.getSttModel(context, sttPreset.value)) }
    val llmPreset = remember { mutableStateOf(SecurityUtils.getLlmPreset(context)) }
    val llmModel = remember { mutableStateOf(SecurityUtils.getLlmModel(context, llmPreset.value)) }
    val offlineEnabled = remember { mutableStateOf(false) }
    val modelReady = remember { mutableStateOf(false) }
    val duckingEnabled = remember { mutableStateOf(false) }
    val excludedAppCount = remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            sttPreset.value = SecurityUtils.getSttPreset(context)
            sttModel.value = SecurityUtils.getSttModel(context, sttPreset.value)
            llmPreset.value = SecurityUtils.getLlmPreset(context)
            llmModel.value = SecurityUtils.getLlmModel(context, llmPreset.value)
            offlineEnabled.value = OfflinePreferences.isOfflineModeEnabled(context)
            modelReady.value = ModelAssetManager.isModelReadySync(context)
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
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsTopBar(title = "Settings", onBack = onNavigateBack)

            Spacer(modifier = Modifier.height(16.dp))

            // AI Transcription
            EntranceRow(0) {
                SettingsRow(
                    icon = Icons.Default.Mic,
                    title = "AI Transcription",
                    summary = "$providerLabel \u00b7 ${sttModel.value}",
                    onClick = { onNavigateTo(Screen.SttConfig) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // AI Agent Mode
            EntranceRow(1) {
                SettingsRow(
                    icon = Icons.Default.AutoAwesome,
                    title = "AI Agent Mode",
                    summary = "$llmProviderLabel \u00b7 ${llmModel.value}",
                    onClick = { onNavigateTo(Screen.AgentConfig) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Offline Transcription
            EntranceRow(2) {
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Custom Dictionary
            EntranceRow(3) {
                SettingsRow(
                    icon = Icons.Default.Book,
                    title = "Custom Dictionary",
                    summary = if (com.groq.voicetyper.dictionary.DictionaryPreferences.isDictionaryEnabled(context)) "Active \u00b7 Manual replacements" else "Disabled",
                    onClick = { onNavigateTo(Screen.CustomDictionary) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Voice Snippets
            EntranceRow(4) {
                SettingsRow(
                    icon = Icons.AutoMirrored.Filled.TextSnippet,
                    title = "Voice Snippets",
                    summary = if (SnippetPreferences.isSnippetsEnabled(context)) "Active \u00b7 Text expansion" else "Disabled",
                    onClick = { onNavigateTo(Screen.Snippets) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Google Drive Sync
            EntranceRow(5) {
                val syncStatus by (syncManager?.status ?: remember { MutableStateFlow(SyncStatus()) }).collectAsState()
                SettingsRow(
                    icon = Icons.Default.CloudSync,
                    title = "Google Drive Sync",
                    summary = when {
                        !syncStatus.signedIn -> "Not configured"
                        !syncStatus.syncEnabled -> "Paused \u00b7 ${syncStatus.account ?: "Signed in"}"
                        syncStatus.running -> "Syncing\u2026"
                        syncStatus.lastError != null -> "Attention required \u00b7 ${syncStatus.account ?: "Signed in"}"
                        else -> "Active \u00b7 ${syncStatus.account ?: "Signed in"}"
                    },
                    onClick = { onNavigateTo(Screen.SyncConfig) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Permissions & Services
            EntranceRow(6) {
                SettingsRow(
                    icon = Icons.Default.Security,
                    title = "Permissions & Services",
                    summary = "Microphone, overlay, accessibility, battery",
                    onClick = { onNavigateTo(Screen.Permissions) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Privacy & App Exclusions
            EntranceRow(7) {
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
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Audio Focus Ducking
            EntranceRow(8) {
                Surface(
                    color = Panel,
                    shape = FluenceShapes.Medium,
                    shadowElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FluenceSpacing.Base)
                        .border(1.dp, OutlineSubtle, FluenceShapes.Medium)
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
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )

                        Spacer(modifier = Modifier.width(FluenceSpacing.Base))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Reduce media volume while dictating",
                                color = TextPrimary,
                                style = FluenceTypography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                            Text(
                                text = "Duck other apps' audio while recording",
                                color = TextSecondary,
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
                                checkedThumbColor = Panel,
                                checkedTrackColor = TextPrimary,
                                uncheckedThumbColor = TextPrimary,
                                uncheckedTrackColor = Panel
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // About
            EntranceRow(9) {
                SettingsRow(
                    icon = Icons.Default.Info,
                    title = "About",
                    summary = "Version \u00b7 Licenses",
                    onClick = { onNavigateTo(Screen.About) }
                )
            }
        }
    }
}
