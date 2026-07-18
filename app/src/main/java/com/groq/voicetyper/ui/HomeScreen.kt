package com.groq.voicetyper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.SecurityUtils
import com.groq.voicetyper.history.HistoryRepository
import com.groq.voicetyper.history.TranscriptionEntry
import com.groq.voicetyper.offline.ModelAssetManager
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToSttConfig: () -> Unit = {},
    onNavigateToAgentConfig: () -> Unit = {},
    onNavigateToOfflineConfig: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isKeyboardActive by remember { mutableStateOf(false) }
    var sttProvider by remember { mutableStateOf("groq") }
    var sttModel by remember { mutableStateOf("whisper-large-v3") }
    var llmProvider by remember { mutableStateOf("groq") }
    var llmModel by remember { mutableStateOf("llama-3.3-70b-versatile") }
    var offlineEnabled by remember { mutableStateOf(false) }
    var modelReady by remember { mutableStateOf(false) }
    var recentEntries by remember { mutableStateOf<List<TranscriptionEntry>>(emptyList()) }

    val repository = remember { HistoryRepository.init(context); HistoryRepository }

    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                val enabledMethods = imeManager.enabledInputMethodList
                val pkgName = context.packageName
                isKeyboardActive = enabledMethods.any { it.packageName == pkgName }

                sttProvider = SecurityUtils.getSttPreset(context)
                sttModel = SecurityUtils.getSttModel(context, sttProvider)
                llmProvider = SecurityUtils.getLlmPreset(context)
                llmModel = SecurityUtils.getLlmModel(context, llmProvider)
                offlineEnabled = OfflinePreferences.isOfflineModeEnabled(context)
                modelReady = ModelAssetManager.isModelReadySync(context)
            }
            kotlinx.coroutines.delay(2000)
        }
    }

    LaunchedEffect(Unit) {
        repository.getAll().collect { entries ->
            recentEntries = entries.take(5)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "flu",
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = SoraFont
                    )
                    Text(
                        text = "ence",
                        color = TextTertiary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = SoraFont
                    )
                    Text(
                        text = "transcribe",
                        color = TextTertiary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Normal,
                        fontFamily = SoraFont
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                IconButton(
                    onClick = onNavigateToSettings,
                    modifier = Modifier
                        .size(44.dp)
                        .pressScale(remember { MutableInteractionSource() })
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Zone 1: Status Card (Hero) ──────────────────────────────────
            val statusColor = when {
                isKeyboardActive -> Success
                !isKeyboardActive -> Error
                else -> Warning
            }
            val statusText = when {
                isKeyboardActive -> "Keyboard Active"
                else -> "Keyboard Inactive"
            }
            val providerSummary = "$sttProvider \u00b7 $sttModel"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel, FluenceShapes.Large)
                    .border(1.dp, OutlineSubtle, FluenceShapes.Large)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        val intent = android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                        context.startActivity(intent)
                    }
                    .padding(20.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusText,
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = HankenGroteskFont
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = providerSummary,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontFamily = HankenGroteskFont
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap to test \u2192",
                        color = TextTertiary,
                        fontSize = 12.sp,
                        fontFamily = HankenGroteskFont
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Zone 2: Quick Access Tiles ───────────────────────────────────
            val sttStatusColor = if (sttProvider.isNotBlank() && sttProvider != "Not configured") Success else Error
            val llmStatusColor = if (llmProvider.isNotBlank() && llmProvider != "Not configured") Success else Error
            val offlineStatusColor = when {
                offlineEnabled && modelReady -> Success
                modelReady -> Warning
                else -> Error
            }

            // STT Tile
            QuickAccessTile(
                statusColor = sttStatusColor,
                title = "AI Transcription",
                subtitle = "$sttProvider \u00b7 $sttModel",
                onClick = onNavigateToSttConfig
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Agent Tile
            QuickAccessTile(
                statusColor = llmStatusColor,
                title = "AI Agent Mode",
                subtitle = "$llmProvider \u00b7 $llmModel",
                onClick = onNavigateToAgentConfig
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Offline Tile
            val offlineSubtitle = when {
                offlineEnabled && modelReady -> "Active \u00b7 Model ready"
                modelReady -> "Model installed \u00b7 Disabled"
                else -> "Not installed"
            }
            QuickAccessTile(
                statusColor = offlineStatusColor,
                title = "Offline Transcription",
                subtitle = offlineSubtitle,
                onClick = onNavigateToOfflineConfig
            )

            Spacer(modifier = Modifier.height(28.dp))

            // ── Zone 3: Recent Transcriptions ───────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "RECENT",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = GeistMonoFont
                )
                if (recentEntries.isNotEmpty()) {
                    Text(
                        text = "Clear all",
                        color = TextTertiary,
                        fontSize = 12.sp,
                        fontFamily = HankenGroteskFont,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            coroutineScope.launch {
                                HistoryRepository.clearAll()
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            HorizontalDivider(
                color = OutlineSubtle,
                thickness = 1.dp
            )

            Spacer(modifier = Modifier.height(4.dp))

            if (recentEntries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.15f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No transcriptions yet",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontFamily = HankenGroteskFont
                    )
                }
            } else {
                recentEntries.forEachIndexed { index, entry ->
                    RecentTranscriptionRow(
                        entry = entry,
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("transcription", entry.text)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = {
                            coroutineScope.launch {
                                HistoryRepository.delete(entry)
                            }
                        }
                    )
                    if (index < recentEntries.lastIndex) {
                        HorizontalDivider(
                            color = OutlineSubtle,
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 0.dp)
                        )
                    }
                }

                if (recentEntries.size >= 5) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "View all \u2192",
                        color = BrandAmethyst,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = HankenGroteskFont,
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* TODO */ }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun QuickAccessTile(
    statusColor: androidx.compose.ui.graphics.Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Panel, FluenceShapes.Large)
            .border(1.dp, OutlineSubtle, FluenceShapes.Large)
            .pressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = HankenGroteskFont
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 12.sp,
                fontFamily = HankenGroteskFont
            )
        }
    }
}

@Composable
private fun RecentTranscriptionRow(
    entry: TranscriptionEntry,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressScale(interactionSource)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onCopy
                )
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.text,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = HankenGroteskFont
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = TextTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Copy", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            onCopy()
                            showMenu = false
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = Error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = Error, modifier = Modifier.size(18.dp)) },
                        onClick = {
                            onDelete()
                            showMenu = false
                        }
                    )
                }
            }
        }
    }
}

private fun relativeHomeTimestamp(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestampMs
    return when {
        diff < 0 -> "Just now"
        diff < 60_000L -> "Just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        diff < 172_800_000L -> "Yesterday"
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestampMs))
        }
    }
}
