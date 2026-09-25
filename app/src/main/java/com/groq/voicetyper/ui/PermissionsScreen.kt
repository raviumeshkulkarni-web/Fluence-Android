package com.groq.voicetyper.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.groq.voicetyper.FeedbackBus
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.groq.voicetyper.FluenceAccessibilityService
import com.groq.voicetyper.SettingsJointCard
import com.groq.voicetyper.SettingsSectionHeader
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.isAccessibilityServiceEnabled
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    val colors = PrecisionTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactionSource)
            .semantics(mergeDescendants = true) {
                stateDescription = if (isGranted) "Granted" else "Not granted"
            }
            .clickable(onClick = onRequest)
            .padding(horizontal = FluenceSpacing.Base, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = FluenceTypography.titleMedium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = colors.textSecondary,
                style = FluenceTypography.bodySmall
            )
        }

        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = if (isGranted) colors.success else colors.error,
                    shape = MaterialTheme.shapes.extraSmall
                )
        )
    }
}



@Composable
fun PermissionsScreen(
    onNavigateBack: () -> Unit
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var keyboardEnabled by remember { mutableStateOf(false) }
    var micGranted by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var batteryUnrestricted by remember { mutableStateOf(false) }

    fun refreshStatuses() {
        val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        keyboardEnabled = imeManager.enabledInputMethodList.any { it.packageName == context.packageName }

        micGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        overlayGranted = Settings.canDrawOverlays(context)
        accessibilityEnabled = isAccessibilityServiceEnabled(context, FluenceAccessibilityService::class.java)
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    LaunchedEffect(Unit) {
        refreshStatuses()
    }

    LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            refreshStatuses()
        }
    }.let { observer ->
        DisposableEffect(lifecycleOwner) {
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        micGranted = isGranted
        if (!isGranted) {
            FeedbackBus.show("Microphone permission is required for voice typing", long = true)
        }
    }

    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        overlayGranted = Settings.canDrawOverlays(context)
    }

    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        batteryUnrestricted = pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    Box(
        modifier = Modifier
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
            SettingsTopBar(
                title = "Permissions & Services",
                onBack = onNavigateBack
            )

            SettingsSectionHeader(
                title = "Required Permissions",
                description = "Core capabilities needed for transcription and overlay functionality"
            )

            SettingsJointCard {
                PermissionRow(
                    icon = Icons.Default.Keyboard,
                    title = "Voice Typing Keyboard",
                    description = if (keyboardEnabled) "Enabled & Selected" else "Tap to enable or select as active keyboard",
                    isGranted = keyboardEnabled,
                    onRequest = {
                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                    }
                )

                HorizontalDivider(color = colors.divider, thickness = 1.dp)

                PermissionRow(
                    icon = Icons.Default.Mic,
                    title = "Microphone",
                    description = if (micGranted) "Granted" else "Required for voice typing",
                    isGranted = micGranted,
                    onRequest = {
                        if (micGranted) {
                            FeedbackBus.show("Microphone permission already granted")
                        } else {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )

                HorizontalDivider(color = colors.divider, thickness = 1.dp)

                PermissionRow(
                    icon = Icons.Default.PictureInPicture,
                    title = "Display Over Other Apps",
                    description = if (overlayGranted) "Granted" else "Required for floating bubble",
                    isGranted = overlayGranted,
                    onRequest = {
                        if (overlayGranted) {
                            FeedbackBus.show("Overlay permission already granted")
                        } else {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlayLauncher.launch(intent)
                        }
                    }
                )

                HorizontalDivider(color = colors.divider, thickness = 1.dp)

                PermissionRow(
                    icon = Icons.Default.Accessibility,
                    title = "Accessibility Service",
                    description = if (accessibilityEnabled) "Enabled" else "Required for orb and auto-mode",
                    isGranted = accessibilityEnabled,
                    onRequest = {
                        if (accessibilityEnabled) {
                            FeedbackBus.show("Accessibility service already enabled")
                        } else {
                            FeedbackBus.show("Find \"Fluence Transcribe\" and enable it", long = true)
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                        }
                    }
                )

                HorizontalDivider(color = colors.divider, thickness = 1.dp)

                PermissionRow(
                    icon = Icons.Default.BatteryAlert,
                    title = "Battery Optimization",
                    description = if (batteryUnrestricted) "Unrestricted" else "May kill background service",
                    isGranted = batteryUnrestricted,
                    onRequest = {
                        if (batteryUnrestricted) {
                            FeedbackBus.show("Already unrestricted")
                        } else {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            batteryLauncher.launch(intent)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Xl))
        }
    }
}
