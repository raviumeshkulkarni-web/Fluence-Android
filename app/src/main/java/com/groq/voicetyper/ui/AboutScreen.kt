package com.groq.voicetyper.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.theme.*
import com.groq.voicetyper.update.UpdateViewModel
import com.groq.voicetyper.update.ui.AboutAndUpdateCard

@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    updateViewModel: UpdateViewModel = viewModel()
) {
    val context = LocalContext.current

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
            SettingsTopBar(title = "About", onBack = onNavigateBack)

            Spacer(modifier = Modifier.height(16.dp))

            // Logo
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                FluenceProductLockup(productName = "Transcribe", orbSize = 48.dp, wordmarkSize = 28.sp)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // About and Update Card
            AboutAndUpdateCard(viewModel = updateViewModel)

            Spacer(modifier = Modifier.height(24.dp))

            // Open Source Licenses
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        try {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/nicklausw/fluence/blob/main/LICENSES.md")
                            )
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Open Source Licenses",
                    color = TextPrimary,
                    style = FluenceTypography.titleMedium
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Open source licenses",
                    tint = TextSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Credits
            Text(
                text = "Built with Jetpack Compose, Material3, and Fluence Design System.",
                color = TextSecondary,
                style = FluenceTypography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Privacy Note: History is not backed up — transcriptions and API keys stay on this device only and are excluded from cloud backups.",
                color = TextTertiary,
                style = FluenceTypography.labelSmall
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
