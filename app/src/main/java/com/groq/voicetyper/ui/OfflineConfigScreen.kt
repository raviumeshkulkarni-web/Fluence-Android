package com.groq.voicetyper.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.offline.ModelAssetManager
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
import kotlinx.coroutines.launch

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
fun OfflineConfigScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var offlineEnabled by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(false) }
    var modelSize by remember { mutableStateOf(0L) }
    val downloadProgress by ModelAssetManager.progress.collectAsState()

    LaunchedEffect(Unit) {
        offlineEnabled = OfflinePreferences.isOfflineModeEnabled(context)
        modelReady = ModelAssetManager.isModelReadySync(context)
        modelSize = ModelAssetManager.getModelSizeOnDisk(context)
    }

    LaunchedEffect(downloadProgress.state) {
        if (downloadProgress.state == ModelAssetManager.DownloadState.COMPLETED) {
            modelReady = true
            modelSize = ModelAssetManager.getModelSizeOnDisk(context)
        } else if (downloadProgress.state == ModelAssetManager.DownloadState.IDLE) {
            modelReady = ModelAssetManager.isModelReadySync(context)
            modelSize = ModelAssetManager.getModelSizeOnDisk(context)
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
                        if (checked && !modelReady) {
                            Toast.makeText(context, "Download the offline model first.", Toast.LENGTH_SHORT).show()
                        } else {
                            offlineEnabled = checked
                            OfflinePreferences.setOfflineModeEnabled(context, checked)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextPrimary,
                        checkedTrackColor = TextPrimary,
                        uncheckedTrackColor = TextPrimary.copy(alpha = 0.2f),
                        uncheckedThumbColor = TextPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Model Status
            Text(
                text = "Model",
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
                            fontWeight = FontWeight.Medium
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
                            contentDescription = null,
                            tint = Error,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Delete", color = Error, fontSize = 13.sp)
                    }
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
                                text = if (downloadProgress.state == ModelAssetManager.DownloadState.VERIFYING) "Verifying..." else "Downloading...",
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
        }
    }
}
