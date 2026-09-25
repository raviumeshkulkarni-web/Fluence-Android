package com.groq.voicetyper.update.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.groq.voicetyper.BuildConfig
import com.groq.voicetyper.SettingsJointCard
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
import com.groq.voicetyper.update.UpdateState
import com.groq.voicetyper.update.UpdateViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AboutAndUpdateCard(
    viewModel: UpdateViewModel,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val state by viewModel.updateState.collectAsState()
    val preferences = viewModel.preferences
    var autoCheck by remember { mutableStateOf(preferences.autoCheckEnabled) }
    var allowMeteredDownload by remember { mutableStateOf(preferences.allowMeteredDownloads) }

    val lastChecked = preferences.lastCheckedTimestamp
    val formattedLastChecked = if (lastChecked > 0) {
        val sdf = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())
        sdf.format(Date(lastChecked))
    } else {
        "Never"
    }

    SettingsJointCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(FluenceSpacing.Base)
        ) {
            // Current Version
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Installed Version", color = colors.textSecondary, style = FluenceTypography.bodyMedium)
                Text(
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    color = colors.textPrimary,
                    style = FluenceTypography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                )
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

            // Latest Version / Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Latest Version", color = colors.textSecondary, style = FluenceTypography.bodyMedium)
                val statusText = when (state) {
                    is UpdateState.Checking -> "Checking…"
                    is UpdateState.UpdateAvailable -> {
                        val meta = (state as UpdateState.UpdateAvailable).metadata
                        "v${meta.versionName} available"
                    }
                    is UpdateState.UpToDate -> "Up to date (${BuildConfig.VERSION_NAME})"
                    is UpdateState.Downloading -> "Downloading…"
                    is UpdateState.ReadyToInstall -> "Ready to install"
                    is UpdateState.Error -> "Check failed — tap to retry"
                    else -> "Up to date (${BuildConfig.VERSION_NAME})"
                }
                Text(
                    statusText,
                    color = if (state is UpdateState.UpdateAvailable) colors.textPrimary else colors.textSecondary,
                    style = FluenceTypography.bodyMedium.copy(fontWeight = FontWeight.Medium)
                )
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

            // Last Checked
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Last Checked", color = colors.textSecondary, style = FluenceTypography.bodyMedium)
                Text(
                    formattedLastChecked,
                    color = colors.textTertiary,
                    style = FluenceTypography.bodySmall.copy(fontFamily = GeistMonoFont)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Auto-Check Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Automatic Updates Check", color = colors.textPrimary, style = FluenceTypography.labelLarge)
                Switch(
                    checked = autoCheck,
                    onCheckedChange = { checked ->
                        autoCheck = checked
                        viewModel.setAutoCheckEnabled(checked)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                        checkedTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                        uncheckedThumbColor = colors.textPrimary,
                        uncheckedTrackColor = colors.panel
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Download over Mobile Data", style = FluenceTypography.labelLarge, color = colors.textPrimary)
                Switch(
                    checked = allowMeteredDownload,
                    onCheckedChange = { checked ->
                        allowMeteredDownload = checked
                        viewModel.setAllowMeteredDownloads(checked)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                        checkedTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                        uncheckedThumbColor = colors.textPrimary,
                        uncheckedTrackColor = colors.panel
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Check for Updates Button
            Button(
                onClick = { viewModel.checkForUpdates(force = true) },
                enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.buttonSecondary,
                    contentColor = colors.textPrimary
                ),
                shape = FluenceShapes.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(remember { MutableInteractionSource() })
            ) {
                if (state is UpdateState.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = colors.textPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Checking…", color = colors.textPrimary, style = FluenceTypography.labelLarge)
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Check for Updates", color = colors.textPrimary, style = FluenceTypography.labelLarge)
                }
            }
        }
    }
}
