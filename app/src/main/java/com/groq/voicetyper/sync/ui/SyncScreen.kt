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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.groq.voicetyper.SettingsJointCard
import com.groq.voicetyper.SettingsSectionHeader
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.sync.SyncManager
import com.groq.voicetyper.theme.*
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/**
 * Dedicated Google Drive Sync settings screen.
 * Follows the Fluence monochrome design system with pure dark surfaces,
 * tactile press feedback, and consistent typography.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onNavigateBack: () -> Unit,
    manager: SyncManager,
    onSignInClick: () -> Unit,
    onSignOutClick: () -> Unit,
    onConsentClick: () -> Unit = {},
    signInError: String? = null,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val status by manager.status.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Swipe-down-to-sync (Material 3 1.2.x pull-to-refresh, wired manually: the
    // state owns a nested scroll connection that triggers startRefresh() once the
    // pull crosses the threshold; we observe isRefreshing and drive the pass).
    // The `enabled` lambda is a rememberSaveable key in 1.2.x, so it must be a
    // stable instance; it reads the live `status` snapshot when invoked.
    val canPullToRefresh = remember { { status.signedIn && status.syncEnabled && !status.running } }
    val pullRefreshState = rememberPullToRefreshState(
        enabled = canPullToRefresh
    )

    // When the gesture crosses the threshold, run a manual sync pass and keep the
    // indicator up until the pass actually spins up and completes, then release.
    LaunchedEffect(pullRefreshState.isRefreshing) {
        if (pullRefreshState.isRefreshing) {
            // Same syncEnabled gate as the Sync-now button; release the
            // indicator immediately when sync is paused.
            val started = manager.syncNow()
            if (!started) {
                pullRefreshState.endRefresh()
            }
            while (pullRefreshState.isRefreshing) {
                delay(100)
                if (!manager.status.value.running) {
                    // Confirm the pass truly finished (not just not-started yet).
                    delay(200)
                    if (!manager.status.value.running) {
                        pullRefreshState.endRefresh()
                    }
                }
            }
        }
    }

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
    // Reduced motion: the transition still exists but is never applied to
    // rotation below, so the icon renders static.
    val motionPrefs = LocalMotionPreferences.current
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
            .background(colors.canvas)
            .nestedScroll(pullRefreshState.nestedScrollConnection)
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

                        // Section 1: Account & Status
            SettingsSectionHeader(
                title = "Account & Status",
                description = "Google Drive cloud backup status and account connection"
            )
            SettingsJointCard {
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
                                .background(colors.panelElevated)
                                .border(1.dp, colors.outlineSubtle, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (status.signedIn) Icons.Default.CloudSync else Icons.Default.AccountCircle,
                                contentDescription = null,
                                tint = if (status.signedIn) colors.textPrimary else colors.textSecondary,
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
                                color = colors.textPrimary,
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
                                            .background(if (status.lastError != null) colors.error else colors.success)
                                            .offset(y = 1.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                }
                                Text(
                                    text = when {
                                        !status.signedIn -> "Sign in to enable cross-device sync"
                                        status.running -> "Syncing changes\u2026"
                                        status.secureStorageUnavailable -> "Secure storage unavailable"
                                        status.lastError != null -> "Attention required"
                                        !status.syncEnabled -> "Sync paused"
                                        status.lastSyncAtMs != null -> "Connected & Synced"
                                        else -> "Connected"
                                    },
                                    color = when {
                                        status.lastError != null || status.secureStorageUnavailable -> colors.error
                                        status.signedIn -> colors.textSecondary
                                        else -> colors.textTertiary
                                    },
                                    style = FluenceTypography.bodySmall
                                )
                            }
                        }
                    }
                }

                if (status.signedIn) {
                    HorizontalDivider(color = colors.divider, thickness = 1.dp)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FluenceSpacing.Base)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                tint = colors.textSecondary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .graphicsLayer {
                                        if (status.running && !motionPrefs.reducedMotion) rotationZ = rotationAngle
                                    }
                            )
                            Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                            Text(
                                text = when {
                                    status.running -> "Syncing…"
                                    status.secureStorageUnavailable ->
                                        "Please restart the app to restore sync"
                                    status.lastError != null -> syncOutcomeMessage(status.lastError)
                                    status.lastSyncAtMs != null -> {
                                        "Last synced: " + DateFormat.getDateTimeInstance(
                                            DateFormat.MEDIUM,
                                            DateFormat.SHORT
                                        ).format(Date(status.lastSyncAtMs!!))
                                    }
                                    else -> "Ready to sync"
                                },
                                color = if (status.lastError != null || status.secureStorageUnavailable) colors.error else colors.textSecondary,
                                style = FluenceTypography.bodySmall
                            )
                        }

                        val needsReconnect = status.signedIn && (status.recoveryPending || status.lastError == "AUTH_REQUIRED") && !status.running && !status.secureStorageUnavailable
                        if (needsReconnect) {
                            Spacer(modifier = Modifier.height(FluenceSpacing.Base))
                            Button(
                                onClick = { if (status.recoveryPending) onConsentClick() else onSignInClick() },
                                colors = ButtonDefaults.buttonColors(containerColor = if (colors.isLight) colors.charcoal else colors.textPrimary, contentColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.canvas),
                                shape = FluenceShapes.Medium,
                                modifier = Modifier.fillMaxWidth().height(48.dp)
                            ) {
                                Text(text = "Reconnect", style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "We could not connect to Google Drive. Tap to reconnect.",
                                color = colors.textSecondary,
                                style = FluenceTypography.bodySmall
                            )
                        }
                    }
                }
            }

            // Section 2: Sync Settings
            SettingsSectionHeader(
                title = "Sync Settings",
                description = if (status.signedIn) "Automatic background sync and manual sync triggers" else "Sign in to activate cloud backup and cross-device sync"
            )
            SettingsJointCard {
                if (status.signedIn) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FluenceSpacing.Base, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Automatic Sync",
                                color = colors.textPrimary,
                                style = FluenceTypography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                            Text(
                                text = "Sync in background and on app launch",
                                color = colors.textSecondary,
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
                                checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                                checkedTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                                uncheckedThumbColor = colors.textPrimary,
                                uncheckedTrackColor = colors.panel,
                                uncheckedBorderColor = colors.outlineSubtle
                            )
                        )
                    }

                    HorizontalDivider(color = colors.divider, thickness = 1.dp)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FluenceSpacing.Base)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(FluenceSpacing.Md)
                        ) {
                            Button(
                                onClick = { manager.syncNow() },
                                enabled = status.signedIn && status.syncEnabled && !status.running,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                                    contentColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.canvas,
                                    disabledContainerColor = colors.panelElevated,
                                    disabledContentColor = colors.textDisabled
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
                                            if (status.running && !motionPrefs.reducedMotion) rotationZ = rotationAngle
                                        }
                                )
                                Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                                Text(
                                    text = if (status.running) "Syncing\u2026" else "Sync Now",
                                    style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }

                            OutlinedButton(
                                onClick = onSignOutClick,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = colors.textSecondary
                                ),
                                border = BorderStroke(1.dp, colors.outlineSubtle),
                                shape = FluenceShapes.Medium,
                                modifier = Modifier
                                    .height(48.dp)
                                    .pressScale(remember { MutableInteractionSource() })
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Logout,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = colors.textSecondary
                                )
                                Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                                Text(
                                    text = "Sign Out",
                                    style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                    color = colors.textSecondary
                                )
                            }
                        }
                        if (!status.syncEnabled) {
                            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
                            Text(
                                text = "Enable sync to use Sync now",
                                color = colors.textTertiary,
                                style = FluenceTypography.bodySmall
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(FluenceSpacing.Base)
                    ) {
                        if (status.secureStorageUnavailable) {
                            Text(
                                text = "Sync is temporarily unavailable. Please restart the app.",
                                color = colors.error,
                                style = FluenceTypography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Button(
                            onClick = onSignInClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                                contentColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.canvas
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
                                style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                        if (signInError != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = signInError,
                                color = colors.error,
                                style = FluenceTypography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Section 3: Privacy & Encryption
            SettingsSectionHeader(
                title = "Privacy & Encryption",
                description = "Zero-knowledge encryption and personal data isolation"
            )
            SettingsJointCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FluenceSpacing.Base),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier
                            .size(18.dp)
                            .padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.width(FluenceSpacing.Base))
                    Text(
                        text = "Your dictionary and settings are backed up securely to your Google Drive. Only you can access them. Transcripts never leave this device.",
                        color = colors.textSecondary,
                        style = FluenceTypography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Xxl))
        }

        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = FluenceSpacing.Base)
                // Hide the idle circular indicator — without this a faint 4dp dot
                // bleeds at the very top edge (overlaps the camera cutout) when
                // the list is at rest. Keep it visible only while pulling/refreshing.
                .graphicsLayer { alpha = if (pullRefreshState.isRefreshing) 1f else 0f },
            containerColor = colors.panel,
            contentColor = colors.textPrimary
        )
    }
}

/** Simple status for non-technical users. */
internal fun syncOutcomeMessage(lastError: String?): String = when (lastError) {
    null -> ""
    "RETRYABLE" -> "Sync will retry automatically"
    "FATAL" -> "Sync paused. Tap Sync now to try again"
    "AUTH_REQUIRED" -> "Please reconnect your Google account"
    "REJECTED" -> "Data too large to sync"
    else -> "Sync needs attention"
}
