package com.groq.voicetyper.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.groq.voicetyper.SettingsJointCard
import com.groq.voicetyper.SettingsSectionHeader
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
import com.groq.voicetyper.update.UpdateViewModel
import com.groq.voicetyper.update.ui.AboutAndUpdateCard

@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    updateViewModel: UpdateViewModel = viewModel()
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FluenceSpacing.Base)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsTopBar(title = "About", onBack = onNavigateBack)

            Spacer(modifier = Modifier.height(16.dp))

            // Logo
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                FluenceProductLockup(productName = "Transcribe", orbSize = 48.dp, wordmarkSize = 24.sp)
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Md))

            // Section 1: App Updates
            SettingsSectionHeader(
                title = "App Updates",
                description = "Version information and update preferences"
            )
            AboutAndUpdateCard(viewModel = updateViewModel)

            // Section 2: Legal & Privacy
            SettingsSectionHeader(
                title = "Legal & Privacy",
                description = "Open source licenses and data safety notices"
            )
            SettingsJointCard {
                val licensesInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pressScale(licensesInteraction)
                        .clickable(
                            interactionSource = licensesInteraction,
                            indication = androidx.compose.foundation.LocalIndication.current,
                            role = Role.Button,
                            onClickLabel = "Open source licenses",
                            onClick = {
                                try {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/nicklausw/fluence/blob/main/LICENSES.md")
                                    )
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            }
                        )
                        .padding(horizontal = FluenceSpacing.Base, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Open Source Licenses",
                        color = colors.textPrimary,
                        style = FluenceTypography.titleMedium
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Open source licenses",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                HorizontalDivider(color = colors.divider, thickness = 1.dp)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FluenceSpacing.Base)
                ) {
                    Text(
                        text = "Built with Jetpack Compose, Material3, and Fluence Design System.",
                        color = colors.textSecondary,
                        style = FluenceTypography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Privacy Note: History is not backed up — transcriptions and API keys stay on this device only and are excluded from cloud backups.",
                        color = colors.textSecondary,
                        style = FluenceTypography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Xl))
        }
    }
}
