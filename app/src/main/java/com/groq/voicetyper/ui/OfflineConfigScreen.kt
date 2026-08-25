package com.groq.voicetyper.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.offline.MoonshineModelManager
import com.groq.voicetyper.offline.ModelAssetManager
import com.groq.voicetyper.offline.OfflineEngineType
import com.groq.voicetyper.offline.OfflinePreferences
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
    var modelReady by remember { mutableStateOf(false) }
    var modelCorrupt by remember { mutableStateOf(false) }
    var modelVerifying by remember { mutableStateOf(true) }
    var modelSize by remember { mutableStateOf(0L) }
    val downloadProgress by ModelAssetManager.progress.collectAsState()

    var selectedEngineType by remember { mutableStateOf(OfflineEngineType.SENSEVOICE) }
    var moonshineReady by remember { mutableStateOf(false) }
    var moonshineCorrupt by remember { mutableStateOf(false) }
    var moonshineVerifying by remember { mutableStateOf(true) }
    var moonshineSize by remember { mutableStateOf(0L) }
    val moonshineDownloadProgress by MoonshineModelManager.progress.collectAsState()

    LaunchedEffect(Unit) {
        offlineEnabled = OfflinePreferences.isOfflineModeEnabled(context)
        modelSize = ModelAssetManager.getModelSizeOnDisk(context)
        selectedEngineType = OfflinePreferences.getEngineType(context)
        moonshineSize = MoonshineModelManager.getModelSizeOnDisk(context)

        modelVerifying = true
        modelReady = ModelAssetManager.isModelReady(context)
        modelCorrupt = !modelReady && ModelAssetManager.isModelReadySync(context)
        modelVerifying = false

        moonshineVerifying = true
        moonshineReady = MoonshineModelManager.isModelReady(context)
        moonshineCorrupt = !moonshineReady && MoonshineModelManager.isModelReadySync(context)
        moonshineVerifying = false
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

    LaunchedEffect(moonshineDownloadProgress.state) {
        if (moonshineDownloadProgress.state == MoonshineModelManager.DownloadState.COMPLETED) {
            moonshineReady = true
            moonshineCorrupt = false
            moonshineSize = MoonshineModelManager.getModelSizeOnDisk(context)
        } else if (moonshineDownloadProgress.state == MoonshineModelManager.DownloadState.IDLE) {
            moonshineReady = MoonshineModelManager.isModelReadySync(context)
            moonshineSize = MoonshineModelManager.getModelSizeOnDisk(context)
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
                            OfflineEngineType.MOONSHINE_BASE -> moonshineReady
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

            // Engine Selector
            Text(
                text = "Transcription Engine",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val senseVoiceInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(senseVoiceInteraction)
                        .selectable(
                            selected = selectedEngineType == OfflineEngineType.SENSEVOICE,
                            interactionSource = senseVoiceInteraction,
                            indication = null,
                            role = Role.RadioButton,
                            onClick = {
                                selectedEngineType = OfflineEngineType.SENSEVOICE
                                OfflinePreferences.setEngineType(context, OfflineEngineType.SENSEVOICE)
                            }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedEngineType == OfflineEngineType.SENSEVOICE,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = TextPrimary,
                            unselectedColor = TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = "SenseVoice (Default)",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Multilingual, ~239 MB",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                val moonshineInteraction = remember { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(moonshineInteraction)
                        .selectable(
                            selected = selectedEngineType == OfflineEngineType.MOONSHINE_BASE,
                            interactionSource = moonshineInteraction,
                            indication = null,
                            role = Role.RadioButton,
                            onClick = {
                                selectedEngineType = OfflineEngineType.MOONSHINE_BASE
                                OfflinePreferences.setEngineType(context, OfflineEngineType.MOONSHINE_BASE)
                            }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedEngineType == OfflineEngineType.MOONSHINE_BASE,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(
                            selectedColor = TextPrimary,
                            unselectedColor = TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Column {
                        Text(
                            text = "Moonshine Base (Experimental)",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "English only, ~287 MB",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Model Status (SenseVoice)
            Text(
                text = "SenseVoice Model",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (modelReady) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SenseVoice Model: Ready",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Storage: ${(modelSize / (1024 * 1024))} MB",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                ModelAssetManager.deleteModel(context)
                                modelReady = false
                                modelCorrupt = false
                                modelSize = 0
                                offlineEnabled = false
                                OfflinePreferences.setOfflineModeEnabled(context, false)
                                Toast.makeText(context, "Model deleted.", Toast.LENGTH_SHORT).show()
                            }
                        },
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
            } else if (modelVerifying) {
                Text(
                    text = "Verifying model integrity…",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            } else if (modelCorrupt) {
                Text(
                    text = "SenseVoice model is corrupted. Re-download required.",
                    color = Error,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            ModelAssetManager.downloadModel(context)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                    shape = FluenceShapes.Medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Re-download Model (~239 MB)", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            } else {
                when (downloadProgress.state) {
                    ModelAssetManager.DownloadState.DOWNLOADING,
                    ModelAssetManager.DownloadState.VERIFYING -> {
                        val progressPercentage = if (downloadProgress.totalBytes > 0) {
                            downloadProgress.bytesDownloaded.toFloat() / downloadProgress.totalBytes.toFloat()
                        } else 0f

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (downloadProgress.state == ModelAssetManager.DownloadState.VERIFYING) "Verifying…" else "Downloading…",
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
                            onClick = { ModelAssetManager.cancelDownload() },
                            colors = ButtonDefaults.buttonColors(containerColor = PanelElevated),
                            shape = FluenceShapes.Medium,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Cancel", color = TextPrimary)
                        }
                    }
                    ModelAssetManager.DownloadState.FAILED -> {
                        Text(
                            text = "Download failed: ${downloadProgress.errorMessage ?: "Unknown error"}",
                            color = Error,
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    ModelAssetManager.downloadModel(context)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                            shape = FluenceShapes.Medium,
                            modifier = Modifier.fillMaxWidth().pressScale(remember { MutableInteractionSource() })
                        ) {
                            Text("Retry Download (~239 MB)", color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> {
                        Text(
                            text = "SenseVoice model not installed.",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    ModelAssetManager.downloadModel(context)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                            shape = FluenceShapes.Medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Download SenseVoice Model (~239 MB)", color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Moonshine Model Status
            Text(
                text = "Moonshine Base Model (Experimental)",
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (moonshineReady) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Moonshine Model: Ready",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Storage: ${(moonshineSize / (1024 * 1024))} MB",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch {
                                MoonshineModelManager.deleteModel(context)
                                moonshineReady = false
                                moonshineCorrupt = false
                                moonshineSize = 0
                                if (selectedEngineType == OfflineEngineType.MOONSHINE_BASE) {
                                    offlineEnabled = false
                                    OfflinePreferences.setOfflineModeEnabled(context, false)
                                }
                                Toast.makeText(context, "Moonshine model deleted.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ButtonSubtle),
                        shape = FluenceShapes.Medium,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Moonshine model",
                                tint = Error,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete", color = Error, fontSize = 13.sp)
                    }
                }
            } else if (moonshineVerifying) {
                Text(
                    text = "Verifying model integrity…",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            } else if (moonshineCorrupt) {
                Text(
                    text = "Moonshine model is corrupted. Re-download required.",
                    color = Error,
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            MoonshineModelManager.downloadModel(context)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                    shape = FluenceShapes.Medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Re-download Model (~287 MB)", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            } else {
                when (moonshineDownloadProgress.state) {
                    MoonshineModelManager.DownloadState.DOWNLOADING,
                    MoonshineModelManager.DownloadState.VERIFYING -> {
                        val progressPercentage = if (moonshineDownloadProgress.totalBytes > 0) {
                            moonshineDownloadProgress.bytesDownloaded.toFloat() / moonshineDownloadProgress.totalBytes.toFloat()
                        } else 0f

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (moonshineDownloadProgress.state == MoonshineModelManager.DownloadState.VERIFYING) "Verifying…" else "Downloading…",
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
                            onClick = { MoonshineModelManager.cancelDownload() },
                            colors = ButtonDefaults.buttonColors(containerColor = PanelElevated),
                            shape = FluenceShapes.Medium,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("Cancel", color = TextPrimary)
                        }
                    }
                    MoonshineModelManager.DownloadState.FAILED -> {
                        Text(
                            text = "Download failed: ${moonshineDownloadProgress.errorMessage ?: "Unknown error"}",
                            color = Error,
                            fontSize = 13.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    MoonshineModelManager.downloadModel(context)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                            shape = FluenceShapes.Medium,
                            modifier = Modifier.fillMaxWidth().pressScale(remember { MutableInteractionSource() })
                        ) {
                            Text("Retry Download (~287 MB)", color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                    else -> {
                        Text(
                            text = "Moonshine model not installed.",
                            color = TextSecondary,
                            fontSize = 14.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    MoonshineModelManager.downloadModel(context)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                            shape = FluenceShapes.Medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Download Moonshine Model (~287 MB)", color = TextPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
