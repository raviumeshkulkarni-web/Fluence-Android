package com.groq.voicetyper.update.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
import com.groq.voicetyper.update.UpdateState
import java.util.Locale

@Composable
fun UpdateDialogHost(
    updateState: UpdateState,
    canInstallPackages: Boolean,
    onStartDownload: (UpdateState.UpdateAvailable) -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: (UpdateState.ReadyToInstall) -> Unit,
    onSkipVersion: (Int) -> Unit,
    onRemindMeLater: () -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    onRequestInstallPermission: () -> Unit
) {
    when (updateState) {
        is UpdateState.UpdateAvailable -> {
            UpdateAvailableDialog(
                state = updateState,
                onUpdate = { onStartDownload(updateState) },
                onRemindMeLater = onRemindMeLater,
                onSkipVersion = { onSkipVersion(updateState.metadata.versionCode) }
            )
        }
        is UpdateState.Downloading -> {
            UpdateDownloadingDialog(
                state = updateState,
                onCancel = onCancelDownload
            )
        }
        is UpdateState.ReadyToInstall -> {
            UpdateReadyToInstallDialog(
                state = updateState,
                canInstallPackages = canInstallPackages,
                onInstall = { onInstall(updateState) },
                onRequestPermission = onRequestInstallPermission,
                onRemindMeLater = onRemindMeLater
            )
        }
        is UpdateState.Error -> {
            UpdateErrorDialog(
                message = updateState.message,
                onRetry = onRetry,
                onDismiss = onDismissError
            )
        }
        else -> {}
    }
}

@Composable
private fun UpdateAvailableDialog(
    state: UpdateState.UpdateAvailable,
    onUpdate: () -> Unit,
    onRemindMeLater: () -> Unit,
    onSkipVersion: () -> Unit
) {
    Dialog(onDismissRequest = onRemindMeLater) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DialogSurface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Update Available",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Version ${state.metadata.versionName} (Build ${state.metadata.versionCode})",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "What's New:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Canvas)
                        .padding(12.dp)
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = formatReleaseNotes(state.releaseNotes),
                            fontSize = 13.sp,
                            color = TextSecondary,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onUpdate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ButtonSecondary,
                        contentColor = TextPrimary
                    ),
                    shape = FluenceShapes.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(remember { MutableInteractionSource() })
                ) {
                    Text("Update Now", color = TextPrimary, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onSkipVersion) {
                        Text("Skip Version", color = TextTertiary, fontSize = 13.sp)
                    }
                    TextButton(onClick = onRemindMeLater) {
                        Text("Remind Me Later", color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateDownloadingDialog(
    state: UpdateState.Downloading,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = {}) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DialogSurface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Downloading Update...",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(20.dp))

                val progressFloat = state.progressPercent / 100f
                LinearProgressIndicator(
                    progress = { progressFloat },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = TextPrimary,
                    trackColor = Canvas,
                )

                Spacer(modifier = Modifier.height(12.dp))

                val downloadedFormatted = formatBytes(state.bytesDownloaded)
                val totalFormatted = formatBytes(state.totalBytes)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${state.progressPercent}%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = if (state.totalBytes > 0) "$downloadedFormatted / $totalFormatted" else downloadedFormatted,
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                OutlinedButton(
                    onClick = onCancel,
                    shape = FluenceShapes.Medium,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    modifier = Modifier.pressScale(remember { MutableInteractionSource() })
                ) {
                    Text("Cancel Download")
                }
            }
        }
    }
}

@Composable
private fun UpdateReadyToInstallDialog(
    state: UpdateState.ReadyToInstall,
    canInstallPackages: Boolean,
    onInstall: () -> Unit,
    onRequestPermission: () -> Unit,
    onRemindMeLater: () -> Unit
) {
    Dialog(onDismissRequest = onRemindMeLater) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DialogSurface),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "Ready to Install",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Fluence Transcribe Version ${state.metadata.versionName} has been downloaded and verified.",
                    fontSize = 14.sp,
                    color = TextSecondary,
                    lineHeight = 20.sp
                )

                if (!canInstallPackages) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Warning.copy(alpha = 0.15f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "To complete installation, please allow 'Install unknown apps' permission in System Settings.",
                            fontSize = 13.sp,
                            color = Warning,
                            lineHeight = 18.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (canInstallPackages) {
                    Button(
                        onClick = onInstall,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ButtonSecondary,
                            contentColor = TextPrimary
                        ),
                        shape = FluenceShapes.Medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale(remember { MutableInteractionSource() })
                    ) {
                        Text("Install Now", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ButtonSecondary,
                            contentColor = TextPrimary
                        ),
                        shape = FluenceShapes.Medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale(remember { MutableInteractionSource() })
                    ) {
                        Text("Grant Permission", color = TextPrimary, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = onRemindMeLater,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Later", color = TextTertiary, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun UpdateErrorDialog(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DialogSurface,
        title = {
            Text("Update Failed", color = Error, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(message, color = TextSecondary, fontSize = 14.sp)
        },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text("Retry", color = TextPrimary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = TextTertiary)
            }
        }
    )
}

private fun formatReleaseNotes(raw: String): String {
    if (raw.isBlank()) return "No detailed release notes provided."
    return raw.replace("\r\n", "\n").trim()
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes.toDouble() / (1024 * 1024)
    return String.format(Locale.US, "%.1f MB", mb)
}
