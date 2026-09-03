package com.groq.voicetyper.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.offline.ModelAssetManager
import com.groq.voicetyper.offline.OfflineEngineType
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.offline.v2.MoonshineV2ModelManager
import com.groq.voicetyper.offline.v2.MoonshineV2ModelType
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
import kotlinx.coroutines.launch

@Composable
fun OfflineConfigScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var offlineEnabled by remember { mutableStateOf(false) }
    var selectedEngineType by remember { mutableStateOf(OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING) }

    // Multilingual (SenseVoice)
    var modelReady by remember { mutableStateOf(false) }
    var modelCorrupt by remember { mutableStateOf(false) }
    var modelVerifying by remember { mutableStateOf(true) }
    var modelSize by remember { mutableStateOf(0L) }
    val downloadProgress by ModelAssetManager.progress.collectAsState()

    // Fast (English) - v2 Small Streaming
    var v2SmallReady by remember { mutableStateOf(false) }
    var v2SmallCorrupt by remember { mutableStateOf(false) }
    var v2SmallVerifying by remember { mutableStateOf(true) }
    var v2SmallSize by remember { mutableStateOf(0L) }
    val v2SmallDownloadProgress by MoonshineV2ModelManager.getProgress(MoonshineV2ModelType.SMALL).collectAsState()

    // Pro (English) - v2 Medium Streaming
    var v2MediumReady by remember { mutableStateOf(false) }
    var v2MediumCorrupt by remember { mutableStateOf(false) }
    var v2MediumVerifying by remember { mutableStateOf(true) }
    var v2MediumSize by remember { mutableStateOf(0L) }
    val v2MediumDownloadProgress by MoonshineV2ModelManager.getProgress(MoonshineV2ModelType.MEDIUM).collectAsState()

    LaunchedEffect(Unit) {
        offlineEnabled = OfflinePreferences.isOfflineModeEnabled(context)
        selectedEngineType = OfflinePreferences.getEngineType(context)

        modelSize = ModelAssetManager.getModelSizeOnDisk(context)
        v2SmallSize = MoonshineV2ModelManager.getModelSizeOnDisk(context, MoonshineV2ModelType.SMALL)
        v2MediumSize = MoonshineV2ModelManager.getModelSizeOnDisk(context, MoonshineV2ModelType.MEDIUM)

        modelVerifying = true
        modelReady = ModelAssetManager.isModelReady(context)
        modelCorrupt = !modelReady && ModelAssetManager.isModelReadySync(context)
        modelVerifying = false

        v2SmallVerifying = true
        v2SmallReady = MoonshineV2ModelManager.isModelReady(context, MoonshineV2ModelType.SMALL)
        v2SmallCorrupt = !v2SmallReady && MoonshineV2ModelManager.isModelReadySync(context, MoonshineV2ModelType.SMALL)
        v2SmallVerifying = false

        v2MediumVerifying = true
        v2MediumReady = MoonshineV2ModelManager.isModelReady(context, MoonshineV2ModelType.MEDIUM)
        v2MediumCorrupt = !v2MediumReady && MoonshineV2ModelManager.isModelReadySync(context, MoonshineV2ModelType.MEDIUM)
        v2MediumVerifying = false
    }

    LaunchedEffect(downloadProgress.state) {
        if (downloadProgress.state == ModelAssetManager.DownloadState.COMPLETED) {
            modelReady = true
            modelCorrupt = false
            modelSize = ModelAssetManager.getModelSizeOnDisk(context)
        } else if (downloadProgress.state == ModelAssetManager.DownloadState.IDLE) {
            modelReady = ModelAssetManager.isModelReadySync(context)
            modelSize = ModelAssetManager.getModelSizeOnDisk(context)
        }
    }

    LaunchedEffect(v2SmallDownloadProgress.state) {
        if (v2SmallDownloadProgress.state == MoonshineV2ModelManager.DownloadState.COMPLETED) {
            v2SmallReady = true
            v2SmallCorrupt = false
            v2SmallSize = MoonshineV2ModelManager.getModelSizeOnDisk(context, MoonshineV2ModelType.SMALL)
        } else if (v2SmallDownloadProgress.state == MoonshineV2ModelManager.DownloadState.IDLE) {
            v2SmallReady = MoonshineV2ModelManager.isModelReadySync(context, MoonshineV2ModelType.SMALL)
            v2SmallSize = MoonshineV2ModelManager.getModelSizeOnDisk(context, MoonshineV2ModelType.SMALL)
        }
    }

    LaunchedEffect(v2MediumDownloadProgress.state) {
        if (v2MediumDownloadProgress.state == MoonshineV2ModelManager.DownloadState.COMPLETED) {
            v2MediumReady = true
            v2MediumCorrupt = false
            v2MediumSize = MoonshineV2ModelManager.getModelSizeOnDisk(context, MoonshineV2ModelType.MEDIUM)
        } else if (v2MediumDownloadProgress.state == MoonshineV2ModelManager.DownloadState.IDLE) {
            v2MediumReady = MoonshineV2ModelManager.isModelReadySync(context, MoonshineV2ModelType.MEDIUM)
            v2MediumSize = MoonshineV2ModelManager.getModelSizeOnDisk(context, MoonshineV2ModelType.MEDIUM)
        }
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
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsTopBar(title = "Offline Transcription", onBack = onNavigateBack)

            Spacer(modifier = Modifier.height(16.dp))

            // Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Offline Mode",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Transcribe without internet.",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }

                Switch(
                    checked = offlineEnabled,
                    onCheckedChange = { checked ->
                        val selectedModelReady = when (selectedEngineType) {
                            OfflineEngineType.SENSEVOICE -> modelReady
                            OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING -> v2SmallReady
                            OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING -> v2MediumReady
                        }
                        if (checked && !selectedModelReady) {
                            Toast.makeText(context, "Download the selected model first.", Toast.LENGTH_SHORT).show()
                        } else {
                            offlineEnabled = checked
                            OfflinePreferences.setOfflineModeEnabled(context, checked)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Panel,
                        checkedTrackColor = TextPrimary,
                        uncheckedThumbColor = TextPrimary,
                        uncheckedTrackColor = Panel
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Model Selector
            Text(
                text = "Choose a model",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Pick the option that fits how you dictate. You can change this any time.",
                color = TextSecondary,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModelOptionCard(
                    title = "Fast (English)",
                    description = "Quick, reliable English dictation for everyday use.",
                    speedLevel = 4,
                    accuracyLevel = 4,
                    recommended = true,
                    isSelected = selectedEngineType == OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING,
                    onSelect = {
                        selectedEngineType = OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING
                        OfflinePreferences.setEngineType(context, OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING)
                    }
                )

                ModelOptionCard(
                    title = "Fast (Multilingual)",
                    description = "Dictate in many languages with fast, on-device transcription.",
                    speedLevel = 5,
                    accuracyLevel = 3,
                    recommended = false,
                    isSelected = selectedEngineType == OfflineEngineType.SENSEVOICE,
                    onSelect = {
                        selectedEngineType = OfflineEngineType.SENSEVOICE
                        OfflinePreferences.setEngineType(context, OfflineEngineType.SENSEVOICE)
                    }
                )

                ModelOptionCard(
                    title = "Pro (English)",
                    description = "Our most accurate English model — ideal when every word matters. Slightly slower to respond.",
                    speedLevel = 2,
                    accuracyLevel = 5,
                    recommended = false,
                    isSelected = selectedEngineType == OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING,
                    onSelect = {
                        selectedEngineType = OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING
                        OfflinePreferences.setEngineType(context, OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING)
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Multilingual (SenseVoice) Model Status
            ModelDownloadCard(
                title = "Fast (Multilingual)",
                sizeEstimate = "~239 MB",
                isReady = modelReady,
                isVerifying = modelVerifying,
                isCorrupt = modelCorrupt,
                diskSize = modelSize,
                downloadState = downloadProgress.state.name,
                bytesDownloaded = downloadProgress.bytesDownloaded,
                totalBytes = downloadProgress.totalBytes,
                errorMessage = downloadProgress.errorMessage,
                onDownload = { coroutineScope.launch { ModelAssetManager.downloadModel(context) } },
                onCancel = { ModelAssetManager.cancelDownload() },
                onDelete = {
                    coroutineScope.launch {
                        ModelAssetManager.deleteModel(context)
                        modelReady = false
                        modelCorrupt = false
                        modelSize = 0
                        if (selectedEngineType == OfflineEngineType.SENSEVOICE) {
                            offlineEnabled = false
                            OfflinePreferences.setOfflineModeEnabled(context, false)
                        }
                        Toast.makeText(context, "Fast (Multilingual) model deleted.", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Fast (English) Model Status - v2 Small
            ModelDownloadCard(
                title = "Fast (English)",
                sizeEstimate = "~142 MB",
                isReady = v2SmallReady,
                isVerifying = v2SmallVerifying,
                isCorrupt = v2SmallCorrupt,
                diskSize = v2SmallSize,
                downloadState = v2SmallDownloadProgress.state.name,
                bytesDownloaded = v2SmallDownloadProgress.bytesDownloaded,
                totalBytes = v2SmallDownloadProgress.totalBytes,
                errorMessage = v2SmallDownloadProgress.errorMessage,
                onDownload = { coroutineScope.launch { MoonshineV2ModelManager.downloadModel(context, MoonshineV2ModelType.SMALL) } },
                onCancel = { MoonshineV2ModelManager.cancelDownload(MoonshineV2ModelType.SMALL) },
                onDelete = {
                    coroutineScope.launch {
                        MoonshineV2ModelManager.deleteModel(context, MoonshineV2ModelType.SMALL)
                        v2SmallReady = false
                        v2SmallCorrupt = false
                        v2SmallSize = 0
                        if (selectedEngineType == OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING) {
                            offlineEnabled = false
                            OfflinePreferences.setOfflineModeEnabled(context, false)
                        }
                        Toast.makeText(context, "Fast (English) model deleted.", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Pro (English) Model Status - v2 Medium
            ModelDownloadCard(
                title = "Pro (English)",
                sizeEstimate = "~269 MB",
                isReady = v2MediumReady,
                isVerifying = v2MediumVerifying,
                isCorrupt = v2MediumCorrupt,
                diskSize = v2MediumSize,
                downloadState = v2MediumDownloadProgress.state.name,
                bytesDownloaded = v2MediumDownloadProgress.bytesDownloaded,
                totalBytes = v2MediumDownloadProgress.totalBytes,
                errorMessage = v2MediumDownloadProgress.errorMessage,
                onDownload = { coroutineScope.launch { MoonshineV2ModelManager.downloadModel(context, MoonshineV2ModelType.MEDIUM) } },
                onCancel = { MoonshineV2ModelManager.cancelDownload(MoonshineV2ModelType.MEDIUM) },
                onDelete = {
                    coroutineScope.launch {
                        MoonshineV2ModelManager.deleteModel(context, MoonshineV2ModelType.MEDIUM)
                        v2MediumReady = false
                        v2MediumCorrupt = false
                        v2MediumSize = 0
                        if (selectedEngineType == OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING) {
                            offlineEnabled = false
                            OfflinePreferences.setOfflineModeEnabled(context, false)
                        }
                        Toast.makeText(context, "Pro (English) model deleted.", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ModelOptionCard(
    title: String,
    description: String,
    speedLevel: Int,
    accuracyLevel: Int,
    recommended: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val borderColor = if (isSelected) TextPrimary else OutlineSubtle
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, FluenceShapes.Medium)
            .border(if (isSelected) 2.dp else 1.dp, borderColor, FluenceShapes.Medium)
            .pressScale(interaction)
            .selectable(
                selected = isSelected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = isSelected,
                onClick = null,
                colors = RadioButtonDefaults.colors(
                    selectedColor = TextPrimary,
                    unselectedColor = TextSecondary
                )
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (recommended) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .background(TextPrimary, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "Recommended",
                        color = Canvas,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.width(34.dp))
            Text(
                text = description,
                color = TextSecondary,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            MetricBar(label = "Speed", level = speedLevel)
            Spacer(modifier = Modifier.width(12.dp))
            MetricBar(label = "Accuracy", level = accuracyLevel)
        }
    }
}

@Composable
private fun MetricBar(label: String, level: Int, maxLevel: Int = 5) {
    val safeLevel = level.coerceIn(0, maxLevel)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "$label $safeLevel out of $maxLevel" }
    ) {
        Text(
            text = label,
            color = TextTertiary,
            fontSize = 11.sp,
            modifier = Modifier.width(52.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(maxLevel) { index ->
                Box(
                    modifier = Modifier
                        .width(14.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if (index < safeLevel) Success else TextDisabled)
                )
            }
        }
    }
}

@Composable
private fun ModelDownloadCard(
    title: String,
    sizeEstimate: String,
    isReady: Boolean,
    isVerifying: Boolean,
    isCorrupt: Boolean,
    diskSize: Long,
    downloadState: String,
    bytesDownloaded: Long,
    totalBytes: Long,
    errorMessage: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Text(
        text = title,
        color = TextPrimary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (isReady) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Status: Ready",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Storage: ${(diskSize / (1024 * 1024))} MB",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            }

            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = ButtonSubtle),
                shape = FluenceShapes.Medium,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete model",
                    tint = Error,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Delete", color = Error, fontSize = 13.sp)
            }
        }
    } else if (isVerifying) {
        Text(
            text = "Verifying model integrity…",
            color = TextSecondary,
            fontSize = 14.sp
        )
    } else if (isCorrupt) {
        Text(
            text = "Model is corrupted. Re-download required.",
            color = Error,
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onDownload,
            colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
            shape = FluenceShapes.Medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Re-download Model ($sizeEstimate)", color = TextPrimary, fontWeight = FontWeight.Bold)
        }
    } else {
        when (downloadState) {
            "DOWNLOADING", "VERIFYING" -> {
                val progressPercentage = if (totalBytes > 0) {
                    bytesDownloaded.toFloat() / totalBytes.toFloat()
                } else 0f

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (downloadState == "VERIFYING") "Verifying…" else "Downloading…",
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "${(progressPercentage * 100).toInt()}%",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { progressPercentage },
                    color = TextPrimary,
                    trackColor = TextPrimary.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(containerColor = PanelElevated),
                    shape = FluenceShapes.Medium
                ) {
                    Text("Cancel", color = TextPrimary)
                }
            }
            "FAILED" -> {
                Text(
                    text = "Download failed: ${errorMessage ?: "Unknown error"}",
                    color = Error,
                    fontSize = 13.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onDownload,
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                    shape = FluenceShapes.Medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Retry Download ($sizeEstimate)", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            }
            else -> {
                Text(
                    text = "Model not installed.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onDownload,
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                    shape = FluenceShapes.Medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Download Model ($sizeEstimate)", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
