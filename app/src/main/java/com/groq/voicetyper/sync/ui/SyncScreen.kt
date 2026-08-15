package com.groq.voicetyper.sync.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.sync.SyncManager
import com.groq.voicetyper.theme.*
import java.text.DateFormat
import java.util.Date

/**
 * Dedicated Google Drive Sync settings screen.
 * Follows the Fluence monochrome design system with pure dark surfaces,
 * tactile press feedback, and consistent typography.
 */
@Composable
fun SyncScreen(
    onNavigateBack: () -> Unit,
    manager: SyncManager,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val status by manager.status.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                manager.refreshStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Rotating sync icon animation when sync is active
    val infiniteTransition = rememberInfiniteTransition(label = "sync_rotation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing)
        ),
        label = "sync_spin"
    )

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
                .padding(horizontal = FluenceSpacing.Base)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsTopBar(
                title = "Google Drive Sync",
                onBack = onNavigateBack
            )

            Spacer(modifier = Modifier.height(FluenceSpacing.Base))

            // ── Account & Status Card ──────────────────────────────────────────
            Surface(
                color = Panel,
                shape = FluenceShapes.Medium,
                border = BorderStroke(1.dp, OutlineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FluenceSpacing.Base)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(PanelElevated)
                                .border(1.dp, OutlineSubtle, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (status.signedIn) Icons.Default.CloudSync else Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = if (status.signedIn) TextPrimary else TextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(FluenceSpacing.Base))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (status.signedIn) {
                                    status.account ?: "Google Account"
                                } else {
                                    "Not Connected"
                                },
                                color = TextPrimary,
                                style = FluenceTypography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (status.signedIn) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (status.lastError != null) Error else Success)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = when {
                                        !status.signedIn -> "Sign in to enable cross-device sync"
                                        status.running -> "Syncing changes\u2026"
                                        status.lastError != null -> "Attention required"
                                        !status.syncEnabled -> "Sync paused"
                                        else -> "Connected & Synced"
                                    },
                                    color = when {
                                        status.lastError != null -> Error
                                        status.signedIn -> TextSecondary
                                        else -> TextTertiary
                                    },
                                    style = FluenceTypography.bodySmall
                                )
                            }
                        }
                    }

                    if (status.signedIn) {
                        Spacer(modifier = Modifier.height(FluenceSpacing.Md))
                        HorizontalDivider(color = OutlineSubtle, thickness = 1.dp)
                        Spacer(modifier = Modifier.height(FluenceSpacing.Md))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .graphicsLayer {
                                        if (status.running) rotationZ = rotationAngle
                                    }
                            )
                            Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                            Text(
                                text = when {
                                    status.running -> "Sync in progress\u2026"
                                    status.lastError != null -> "Last pass: ${status.lastError}"
                                    status.lastSyncAtMs != null -> {
                                        "Last synced: " + DateFormat.getDateTimeInstance(
                                            DateFormat.MEDIUM,
                                            DateFormat.SHORT
                                        ).format(Date(status.lastSyncAtMs!!))
                                    }
                                    else -> "Ready to sync"
                                },
                                color = if (status.lastError != null) Error else TextSecondary,
                                style = FluenceTypography.bodySmall
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Base))

            // ── Automatic Sync Toggle (When Signed In) ───────────────────────────
            if (status.signedIn) {
                Surface(
                    color = Panel,
                    shape = FluenceShapes.Medium,
                    border = BorderStroke(1.dp, OutlineSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Base),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Automatic Sync",
                                color = TextPrimary,
                                style = FluenceTypography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                            Text(
                                text = "Sync in background and on app launch",
                                color = TextSecondary,
                                style = FluenceTypography.bodySmall
                            )
                        }

                        Switch(
                            checked = status.syncEnabled,
                            onCheckedChange = { checked ->
                                SyncManager.setSyncEnabled(context, checked)
                                manager.refreshStatus()
                            },
                            modifier = Modifier.semantics {
                                role = Role.Switch
                                stateDescription = if (status.syncEnabled) "On" else "Off"
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Panel,
                                checkedTrackColor = TextPrimary,
                                uncheckedThumbColor = TextPrimary,
                                uncheckedTrackColor = PanelElevated,
                                uncheckedBorderColor = OutlineSubtle
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(FluenceSpacing.Base))
            }

            // ── Actions Section ────────────────────────────────────────────────
            if (status.signedIn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FluenceSpacing.Md)
                ) {
                    // Sync Now Button
                    Button(
                        onClick = { manager.syncNow() },
                        enabled = !status.running,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TextPrimary,
                            contentColor = Canvas,
                            disabledContainerColor = PanelElevated,
                            disabledContentColor = TextDisabled
                        ),
                        shape = FluenceShapes.Medium,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .pressScale(remember { MutableInteractionSource() })
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = null,
                            modifier = Modifier
                                .size(18.dp)
                                .graphicsLayer {
                                    if (status.running) rotationZ = rotationAngle
                                }
                        )
                        Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                        Text(
                            text = if (status.running) "Syncing\u2026" else "Sync now",
                            style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }

                    // Sign Out Button
                    OutlinedButton(
                        onClick = onSignOutClick,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = TextSecondary
                        ),
                        border = BorderStroke(1.dp, OutlineSubtle),
                        shape = FluenceShapes.Medium,
                        modifier = Modifier
                            .height(48.dp)
                            .pressScale(remember { MutableInteractionSource() })
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = TextSecondary
                        )
                        Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                        Text(
                            text = "Sign out",
                            style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.Medium),
                            color = TextSecondary
                        )
                    }
                }
            } else {
                // Sign In Button
                Button(
                    onClick = onSignInClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TextPrimary,
                        contentColor = Canvas
                    ),
                    shape = FluenceShapes.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .pressScale(remember { MutableInteractionSource() })
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(FluenceSpacing.Base))
                    Text(
                        text = "Sign in with Google",
                        style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Lg))

            // ── Synced Categories Overview ─────────────────────────────────────
            Text(
                text = "Synced Items",
                color = TextPrimary,
                style = FluenceTypography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = FluenceSpacing.Xs)
            )

            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

            Surface(
                color = Panel,
                shape = FluenceShapes.Medium,
                border = BorderStroke(1.dp, OutlineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm)
                ) {
                    SyncedItemRow(
                        icon = Icons.Default.Book,
                        title = "Custom Dictionary",
                        description = "Custom words and phonetic replacements"
                    )
                    HorizontalDivider(color = OutlineSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = FluenceSpacing.Xs))
                    SyncedItemRow(
                        icon = Icons.AutoMirrored.Filled.TextSnippet,
                        title = "Voice Snippets",
                        description = "Triggers, text expansions, and templates"
                    )
                    HorizontalDivider(color = OutlineSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = FluenceSpacing.Xs))
                    SyncedItemRow(
                        icon = Icons.Default.History,
                        title = "Transcription History",
                        description = "Recent voice typings and logs"
                    )
                    HorizontalDivider(color = OutlineSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = FluenceSpacing.Xs))
                    SyncedItemRow(
                        icon = Icons.Default.Settings,
                        title = "Settings & Preferences",
                        description = "Configured options and toggles"
                    )
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Base))

            // ── Privacy & Security Note ────────────────────────────────────────
            Surface(
                color = PanelElevated,
                shape = FluenceShapes.Medium,
                border = BorderStroke(1.dp, OutlineSubtle),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FluenceSpacing.Base),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier
                            .size(18.dp)
                            .padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(FluenceSpacing.Base))
                    Text(
                        text = "Data is synced directly to your private Google Drive app storage (Fluence Sync folder). Your data remains under your ownership and is never sent to third-party servers.",
                        color = TextSecondary,
                        style = FluenceTypography.bodySmall,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Xxl))
        }
    }
}

@Composable
private fun SyncedItemRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FluenceSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(FluenceSpacing.Base))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                style = FluenceTypography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = description,
                color = TextSecondary,
                style = FluenceTypography.bodySmall
            )
        }
    }
}
