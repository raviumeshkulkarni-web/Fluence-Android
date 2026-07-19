package com.groq.voicetyper.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.SecurityUtils
import com.groq.voicetyper.offline.ModelAssetManager
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.navigation.Screen
import com.groq.voicetyper.pressScale
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
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                color = TextSecondary,
                fontSize = 13.sp
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

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = OutlineSubtle,
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = 58.dp)
    )
}

@Composable
private fun SettingsTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(44.dp)
                .pressScale(remember { MutableInteractionSource() })
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Text(
            text = title,
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateTo: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val sttPreset = remember { mutableStateOf(SecurityUtils.getSttPreset(context)) }
    val sttModel = remember { mutableStateOf(SecurityUtils.getSttModel(context, sttPreset.value)) }
    val llmPreset = remember { mutableStateOf(SecurityUtils.getLlmPreset(context)) }
    val llmModel = remember { mutableStateOf(SecurityUtils.getLlmModel(context, llmPreset.value)) }
    val offlineEnabled = remember { mutableStateOf(false) }
    val modelReady = remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            sttPreset.value = SecurityUtils.getSttPreset(context)
            sttModel.value = SecurityUtils.getSttModel(context, sttPreset.value)
            llmPreset.value = SecurityUtils.getLlmPreset(context)
            llmModel.value = SecurityUtils.getLlmModel(context, llmPreset.value)
            offlineEnabled.value = OfflinePreferences.isOfflineModeEnabled(context)
            modelReady.value = ModelAssetManager.isModelReadySync(context)
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
