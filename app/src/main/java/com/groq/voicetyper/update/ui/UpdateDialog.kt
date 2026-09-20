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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
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
    val colors = PrecisionTheme.colors
    Dialog(onDismissRequest = onRemindMeLater) {
        Card(
            colors = CardDefaults.cardColors(containerColor = colors.dialog),
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
                    style = FluenceTypography.headlineMedium,
                    color = colors.textPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Version ${state.metadata.versionName} (Build ${state.metadata.versionCode})",
                    color = colors.textSecondary,
                    style = FluenceTypography.labelLarge
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "What's New:",
                    color = colors.textPrimary,
                    style = FluenceTypography.labelLarge
                )

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.canvas)
                        .padding(12.dp)
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = formatReleaseNotes(state.releaseNotes),
                            color = colors.textSecondary,
                            style = FluenceTypography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onUpdate,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.buttonSecondary,
                        contentColor = colors.textPrimary
                    ),
                    shape = FluenceShapes.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(remember { MutableInteractionSource() })
                ) {
                    Text("Update Now", color = colors.textPrimary, style = FluenceTypography.labelLarge)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onSkipVersion) {
                        Text("Skip Version", color = colors.textSecondary, style = FluenceTypography.labelMedium)
                    }
                    TextButton(onClick = onRemindMeLater) {
                        Text("Remind Me Later", color = colors.textSecondary, style = FluenceTypography.labelMedium)
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
    val colors = PrecisionTheme.colors
    Dialog(onDismissRequest = {}) {
        Card(
            colors = CardDefaults.cardColors(containerColor = colors.dialog),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(FluenceSpacing.Base)
        ) {
            Column(
                modifier = Modifier
                    .padding(FluenceSpacing.Lg)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Downloading Update…",
                    color = colors.textPrimary,
                    style = FluenceTypography.headlineMedium
                )

                Spacer(modifier = Modifier.height(FluenceSpacing.Lg))

                val progressFloat = state.progressPercent / 100f
                LinearProgressIndicator(
                    progress = { progressFloat },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .semantics { stateDescription = "${state.progressPercent}%" },
                    color = colors.textPrimary,
                    trackColor = colors.sunken,
                )

                Spacer(modifier = Modifier.height(FluenceSpacing.Md))

                val downloadedFormatted = formatBytes(state.bytesDownloaded)
                val totalFormatted = formatBytes(state.totalBytes)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${state.progressPercent}%",
                        color = colors.textPrimary,
                        style = FluenceTypography.bodySmall
                    )
                    Text(
                        text = if (state.totalBytes > 0) "$downloadedFormatted / $totalFormatted" else downloadedFormatted,
                        color = colors.textSecondary,
                        style = FluenceTypography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(FluenceSpacing.Lg))

                OutlinedButton(
                    onClick = onCancel,
                    shape = FluenceShapes.Medium,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.textSecondary),
                    modifier = Modifier.pressScale(remember { MutableInteractionSource() })
                ) {
                    Text("Cancel Download", style = FluenceTypography.labelLarge)
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
    val colors = PrecisionTheme.colors
    Dialog(onDismissRequest = onRemindMeLater) {
        Card(
            colors = CardDefaults.cardColors(containerColor = colors.dialog),
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
                    style = FluenceTypography.headlineMedium,
                    color = colors.textPrimary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Fluence Transcribe Version ${state.metadata.versionName} has been downloaded and verified.",
                    color = colors.textSecondary,
                    style = FluenceTypography.labelLarge
                )

                if (!canInstallPackages) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.warning.copy(alpha = if (colors.isLight) 0.10f else 0.15f))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "To complete installation, please allow 'Install unknown apps' permission in System Settings.",
                            color = colors.warning,
                            style = FluenceTypography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (canInstallPackages) {
                    Button(
                        onClick = onInstall,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.buttonSecondary,
                            contentColor = colors.textPrimary
                        ),
                        shape = FluenceShapes.Medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale(remember { MutableInteractionSource() })
                    ) {
                        Text("Install Now", color = colors.textPrimary, style = FluenceTypography.labelLarge)
                    }
                } else {
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.buttonSecondary,
                            contentColor = colors.textPrimary
                        ),
                        shape = FluenceShapes.Medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .pressScale(remember { MutableInteractionSource() })
                    ) {
                        Text("Grant Permission", color = colors.textPrimary, style = FluenceTypography.labelLarge)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(
                    onClick = onRemindMeLater,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Later", color = colors.textSecondary, style = FluenceTypography.labelMedium)
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
    val colors = PrecisionTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.dialog,
        title = {
            Text("Update Failed", color = colors.errorText, style = FluenceTypography.headlineSmall)
        },
        text = {
            Text(message, color = colors.textSecondary, style = FluenceTypography.labelLarge)
        },
        confirmButton = {
            TextButton(onClick = onRetry) {
                Text("Retry", color = colors.textPrimary, style = FluenceTypography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = colors.textSecondary, style = FluenceTypography.labelLarge)
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
