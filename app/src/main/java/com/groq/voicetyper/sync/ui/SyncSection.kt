package com.groq.voicetyper.sync.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.groq.voicetyper.sync.SyncManager
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.OutlineSubtle
import com.groq.voicetyper.theme.Panel
import com.groq.voicetyper.theme.TextPrimary
import com.groq.voicetyper.theme.TextSecondary
import java.text.DateFormat
import java.util.Date

/**
 * Sync settings section (spec §27 phase 8) embedded in SettingsScreen.
 * Shows the Drive sync toggle, account, pass status, and sign in/out + sync
 * now actions. Status refreshes when the screen resumes (e.g. after the OAuth
 * redirect activity finishes).
 */
@Composable
fun SyncSection(
    manager: SyncManager,
    modifier: Modifier = Modifier,
    onSignInClick: () -> Unit = {},
    onSignOutClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val status by manager.status.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val refresh = remember { { manager.refreshStatus() } }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        color = Panel,
        shape = FluenceShapes.Medium,
        shadowElevation = 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Base)
            .border(1.dp, OutlineSubtle, FluenceShapes.Medium)
    ) {
        Column(modifier = Modifier.padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Base)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(FluenceSpacing.Base))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Sync", color = TextPrimary, style = FluenceTypography.titleMedium)
                    Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                    Text(
                        text = if (status.signedIn) "Synced to Google Drive" else "Not signed in",
                        color = TextSecondary,
                        style = FluenceTypography.bodySmall
                    )
                }
                Switch(
                    checked = status.syncEnabled,
                    onCheckedChange = { checked ->
                        SyncManager.setSyncEnabled(context, checked)
                        manager.refreshStatus()
                    }
                )
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Base))

            val statusLine = buildString {
                when {
                    status.running -> append("Syncing\u2026")
                    status.lastError != null -> append("Last pass: ${status.lastError}")
                    status.lastSyncAtMs != null -> append(
                        "Last synced: " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(status.lastSyncAtMs!!))
                    )
                    else -> append("Idle")
                }
            }
            Text(
                text = statusLine,
                color = if (status.lastError != null) MaterialTheme.colorScheme.error else TextSecondary,
                style = FluenceTypography.bodySmall
            )
            if (status.account != null) {
                Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                Text(
                    text = "Account: ${status.account}",
                    color = TextSecondary,
                    style = FluenceTypography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Base))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status.signedIn) {
                    OutlinedButton(
                        onClick = {
                            if (onSignOutClick != null) {
                                onSignOutClick()
                            } else {
                                manager.signOut()
                            }
                        }
                    ) {
                        Text("Sign out")
                    }
                } else {
                    Button(
                        onClick = onSignInClick
                    ) {
                        Text("Sign in with Google")
                    }
                }
                Spacer(modifier = Modifier.width(FluenceSpacing.Base))
                FilledTonalButton(
                    onClick = { manager.syncNow() },
                    enabled = status.signedIn
                ) {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(FluenceSpacing.Xxs))
                    Text("Sync now")
                }
            }
        }
    }
}