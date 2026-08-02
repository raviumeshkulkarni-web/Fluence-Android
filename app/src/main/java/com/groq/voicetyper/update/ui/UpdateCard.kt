package com.groq.voicetyper.update.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.groq.voicetyper.BuildConfig
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

    Card(
        colors = CardDefaults.cardColors(containerColor = PanelElevated),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "App Updates",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Current Version
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Installed Version", fontSize = 14.sp, color = TextSecondary)
                Text(
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Latest Version / Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Latest Version", fontSize = 14.sp, color = TextSecondary)
                val statusText = when (state) {
                    is UpdateState.Checking -> "Checking..."
                    is UpdateState.UpdateAvailable -> {
                        val meta = (state as UpdateState.UpdateAvailable).metadata
                        "v${meta.versionName} available"
                    }
                    is UpdateState.UpToDate -> "Up to date (${BuildConfig.VERSION_NAME})"
                    is UpdateState.Downloading -> "Downloading..."
                    is UpdateState.ReadyToInstall -> "Ready to install"
                    is UpdateState.Error -> "Check failed — tap to retry"
                    else -> "Up to date (${BuildConfig.VERSION_NAME})"
                }
                Text(
                    statusText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (state is UpdateState.UpdateAvailable) TextPrimary else TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Last Checked
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Last Checked", fontSize = 14.sp, color = TextSecondary)
                Text(
                    formattedLastChecked,
                    fontSize = 13.sp,
                    color = TextTertiary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Auto-Check Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Automatic Updates Check", fontSize = 14.sp, color = TextPrimary)
                Switch(
                    checked = autoCheck,
                    onCheckedChange = { checked ->
                        autoCheck = checked
                        viewModel.setAutoCheckEnabled(checked)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Panel,
                        checkedTrackColor = TextPrimary,
                        uncheckedThumbColor = TextPrimary,
                        uncheckedTrackColor = Panel
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Download over Mobile Data", fontSize = 14.sp, color = TextPrimary)
                Switch(
                    checked = allowMeteredDownload,
                    onCheckedChange = { checked ->
                        allowMeteredDownload = checked
                        viewModel.setAllowMeteredDownloads(checked)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Panel,
                        checkedTrackColor = TextPrimary,
                        uncheckedThumbColor = TextPrimary,
                        uncheckedTrackColor = Panel
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Check for Updates Button
            Button(
                onClick = { viewModel.checkForUpdates(force = true) },
                enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ButtonSecondary,
                    contentColor = TextPrimary
                ),
                shape = FluenceShapes.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(remember { MutableInteractionSource() })
            ) {
                if (state is UpdateState.Checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = TextPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Checking...", color = TextPrimary, fontWeight = FontWeight.Bold)
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Check for Updates", color = TextPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
