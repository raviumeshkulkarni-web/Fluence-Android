package com.groq.voicetyper.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.groq.voicetyper.FloatingBubblePreferences
import com.groq.voicetyper.FluenceAccessibilityService
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.isAccessibilityServiceEnabled
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
import kotlin.math.roundToInt

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactionSource)
            .semantics(mergeDescendants = true) {
                stateDescription = if (isGranted) "Granted" else "Not granted"
            }
            .clickable(onClick = onRequest)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                style = FluenceTypography.titleMedium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                color = TextSecondary,
                style = FluenceTypography.bodySmall
            )
        }

        Box(
            modifier = Modifier
                .size(10.dp)
                .background(
                    color = if (isGranted) Success else Error,
                    shape = MaterialTheme.shapes.extraSmall
                )
        )
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        color = OutlineSubtle,
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = 20.dp)
    )
}

@Composable
fun PermissionsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var keyboardEnabled by remember { mutableStateOf(false) }
    var micGranted by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var batteryUnrestricted by remember { mutableStateOf(false) }
    var bubbleEnabled by remember { mutableStateOf(false) }
    var bubbleOpacity by remember { mutableFloatStateOf(FloatingBubblePreferences.getOpacity(context)) }

    val prefs = remember { context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE) }

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
        bubbleEnabled = prefs.getBoolean("floating_bubble_enabled", false)
        bubbleOpacity = FloatingBubblePreferences.getOpacity(context)
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
            Toast.makeText(context, "Microphone permission is required for voice typing", Toast.LENGTH_LONG).show()
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
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SettingsTopBar(
                title = "Permissions & Services",
                onBack = onNavigateBack
            )

            Spacer(modifier = Modifier.height(16.dp))

            PermissionRow(
                icon = Icons.Default.Keyboard,
                title = "Voice Typing Keyboard",
                description = if (keyboardEnabled) "Enabled & Selected" else "Tap to enable or select as active keyboard",
                isGranted = keyboardEnabled,
                onRequest = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            )

            SectionDivider()

            PermissionRow(
                icon = Icons.Default.Mic,
                title = "Microphone",
                description = if (micGranted) "Granted" else "Required for voice typing",
                isGranted = micGranted,
                onRequest = {
                    if (micGranted) {
                        Toast.makeText(context, "Microphone permission already granted", Toast.LENGTH_SHORT).show()
                    } else {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )

            SectionDivider()

            PermissionRow(
                icon = Icons.Default.PictureInPicture,
                title = "Display Over Other Apps",
                description = if (overlayGranted) "Granted" else "Required for floating bubble",
                isGranted = overlayGranted,
                onRequest = {
                    if (overlayGranted) {
                        Toast.makeText(context, "Overlay permission already granted", Toast.LENGTH_SHORT).show()
                    } else {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        overlayLauncher.launch(intent)
                    }
                }
            )

            SectionDivider()

            PermissionRow(
                icon = Icons.Default.Accessibility,
                title = "Accessibility Service",
                description = if (accessibilityEnabled) "Enabled" else "Required for orb and auto-mode",
                isGranted = accessibilityEnabled,
                onRequest = {
                    if (accessibilityEnabled) {
                        Toast.makeText(context, "Accessibility service already enabled", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Find \"Fluence Transcribe\" and enable it", Toast.LENGTH_LONG).show()
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        context.startActivity(intent)
                    }
                }
            )

            SectionDivider()

            PermissionRow(
                icon = Icons.Default.BatteryAlert,
                title = "Battery Optimization",
                description = if (batteryUnrestricted) "Unrestricted" else "May kill background service",
                isGranted = batteryUnrestricted,
                onRequest = {
                    if (batteryUnrestricted) {
                        Toast.makeText(context, "Already unrestricted", Toast.LENGTH_SHORT).show()
                    } else {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        batteryLauncher.launch(intent)
                    }
                }
            )

            SectionDivider()

            val interactionSource = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale(interactionSource)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = {
                            if (!accessibilityEnabled) {
                                Toast.makeText(context, "Enable accessibility service first", Toast.LENGTH_SHORT).show()
                            } else {
                                val newValue = !bubbleEnabled
                                prefs.edit().putBoolean("floating_bubble_enabled", newValue).apply()
                                bubbleEnabled = newValue
                                if (!newValue) {
                                    Toast.makeText(context, "Floating bubble disabled", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.BubbleChart,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Floating Bubble",
                        color = TextPrimary,
                        style = FluenceTypography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when {
                            !accessibilityEnabled -> "Enable accessibility first"
                            bubbleEnabled -> "Active — orb will appear in text fields"
                            else -> "Tap to enable"
                        },
                        color = TextSecondary,
                        style = FluenceTypography.bodySmall
                    )
                }

                Switch(
                    checked = bubbleEnabled,
                    onCheckedChange = { newValue ->
                        if (!accessibilityEnabled) {
                            Toast.makeText(context, "Enable accessibility service first", Toast.LENGTH_SHORT).show()
                        } else {
                            prefs.edit().putBoolean("floating_bubble_enabled", newValue).apply()
                            bubbleEnabled = newValue
                            if (!newValue) {
                                Toast.makeText(context, "Floating bubble disabled", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Panel,
                        checkedTrackColor = TextPrimary,
                        uncheckedThumbColor = TextPrimary,
                        uncheckedTrackColor = Panel
                    ),
                    modifier = Modifier.semantics {
                        role = Role.Switch
                        stateDescription = if (bubbleEnabled) "On" else "Off"
                        contentDescription = "Floating Bubble"
                    }
                )
            }

            if (bubbleEnabled && accessibilityEnabled) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Idle Opacity",
                            color = TextSecondary,
                            style = FluenceTypography.bodyMedium
                        )
                        Text(
                            text = "${(bubbleOpacity * 100).roundToInt()}%",
                            color = TextPrimary,
                            style = FluenceTypography.titleMedium
                        )
                    }
                    Slider(
                        value = bubbleOpacity,
                        onValueChange = { newValue ->
                            bubbleOpacity = newValue
                            FloatingBubblePreferences.setOpacity(context, newValue)
                        },
                        valueRange = FloatingBubblePreferences.MIN_OPACITY..FloatingBubblePreferences.MAX_OPACITY,
                        colors = SliderDefaults.colors(
                            thumbColor = TextPrimary,
                            activeTrackColor = TextPrimary,
                            inactiveTrackColor = OutlineSubtle
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
