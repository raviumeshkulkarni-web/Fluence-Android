package com.groq.voicetyper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.SecurityUtils
import com.groq.voicetyper.history.HistoryRepository
import com.groq.voicetyper.history.TranscriptionEntry
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val AVG_WPM = 40.0

private fun computeStats(entries: List<TranscriptionEntry>): Triple<String, String, String> {
    val totalChars = entries.sumOf { it.text.length }
    val totalMinutes = entries.sumOf { it.durationMs } / 60_000.0
    val hoursSaved = (totalChars / 5.0) / AVG_WPM / 60.0
    val savedText = if (hoursSaved < 1.0) "${(hoursSaved * 60).toInt()}m" else String.format(Locale.US, "%.1fh", hoursSaved)
    val ideasCount = entries.size
    val totalH = totalMinutes.toInt() / 60
    val totalM = totalMinutes.toInt() % 60
    val dictText = if (totalH > 0) "${totalH}h ${totalM}m" else "${totalM}m"
    val charsText = if (totalChars >= 1_000_000) String.format(Locale.US, "%.1fM", totalChars / 1_000_000.0)
    else if (totalChars >= 1_000) String.format(Locale.US, "%.1fK", totalChars / 1_000.0)
    else totalChars.toString()
    return Triple(savedText, ideasCount.toString(), dictText).let { (s, i, d) -> Triple(s, i, d) }
}

private fun groupEntries(entries: List<TranscriptionEntry>): List<Pair<String, List<TranscriptionEntry>>> {
    val now = System.currentTimeMillis()
    val startOfToday = { java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis }
    val todayStart = startOfToday()
    val yesterdayStart = todayStart - TimeUnit.DAYS.toMillis(1)
    val thisWeekStart = todayStart - TimeUnit.DAYS.toMillis(
        (java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) - java.util.Calendar.MONDAY).coerceAtLeast(0).toLong()
    )
    val lastWeekStart = thisWeekStart - TimeUnit.DAYS.toMillis(7)

    val groups = mutableListOf<Pair<String, List<TranscriptionEntry>>>()
    val today = entries.filter { it.timestamp >= todayStart }
    val yesterday = entries.filter { it.timestamp in yesterdayStart until todayStart }
    val thisWeek = entries.filter { it.timestamp in thisWeekStart until yesterdayStart }
    val lastWeek = entries.filter { it.timestamp in lastWeekStart until thisWeekStart }
    val older = entries.filter { it.timestamp < lastWeekStart }

    if (today.isNotEmpty()) groups.add("TODAY" to today)
    if (yesterday.isNotEmpty()) groups.add("YESTERDAY" to yesterday)
    if (thisWeek.isNotEmpty()) groups.add("THIS WEEK" to thisWeek)
    if (lastWeek.isNotEmpty()) groups.add("LAST WEEK" to lastWeek)
    if (older.isNotEmpty()) groups.add("EARLIER" to older)
    return groups
}

@OptIn(ExperimentalFoundationApi::class)
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

    var allEntries by remember { mutableStateOf<List<TranscriptionEntry>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }

    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val isMultiSelect = selectedIds.isNotEmpty()
    var showClearAllDialog by remember { mutableStateOf(false) }
    var pendingDeleteIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val repository = remember { HistoryRepository.init(context); HistoryRepository }

    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                isKeyboardActive = imeManager.enabledInputMethodList.any { it.packageName == context.packageName }
                sttProvider = SecurityUtils.getSttPreset(context)
                sttModel = SecurityUtils.getSttModel(context, sttProvider)
            }
            kotlinx.coroutines.delay(2000)
        }
    }

    LaunchedEffect(Unit) {
        repository.getAll().collect { allEntries = it }
    }

    val displayedEntries = if (searchQuery.isBlank()) allEntries
    else allEntries.filter { it.text.contains(searchQuery, ignoreCase = true) }

    val groupedEntries = remember(displayedEntries) { groupEntries(displayedEntries) }

    val (savedTime, ideasCount, dictTime) = remember(allEntries) { computeStats(allEntries) }
    val totalChars = remember(allEntries) { allEntries.sumOf { it.text.length } }
    val charsText = if (totalChars >= 1_000_000) String.format(Locale.US, "%.1fM", totalChars / 1_000_000.0)
    else if (totalChars >= 1_000) String.format(Locale.US, "%.1fK", totalChars / 1_000.0)
    else totalChars.toString()

    fun deleteSelected() {
        coroutineScope.launch { repository.deleteByIds(selectedIds.toList()) }
        selectedIds = emptySet()
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
                if (isMultiSelect) {
                    IconButton(
                        onClick = { selectedIds = emptySet() },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Exit selection",
                            tint = TextPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${selectedIds.size} selected",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = SoraFont
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            pendingDeleteIds = selectedIds.toList()
                            showDeleteDialog = true
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete selected",
                            tint = Error,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "fluence",
                            color = TextPrimary,
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
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Status Card ─────────────────────────────────────────────────
            val statusColor = if (isKeyboardActive) Success else Error
            val statusText = if (isKeyboardActive) "Keyboard Active" else "Keyboard Inactive"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Panel, FluenceShapes.Large)
                    .border(1.dp, OutlineSubtle, FluenceShapes.Large)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { context.startActivity(android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
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
                        text = "$sttProvider \u00b7 $sttModel",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontFamily = HankenGroteskFont
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ── Statistics ──────────────────────────────────────────────────
            Text(
                text = "YOU SAVED",
                color = TextTertiary,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = GeistMonoFont
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = savedTime,
                color = BrandAmethyst,
                fontSize = 48.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = SoraFont,
                lineHeight = 52.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Estimated typing time saved",
                color = TextSecondary,
                fontSize = 13.sp,
                fontFamily = HankenGroteskFont
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = "Ideas Captured",
                    value = ideasCount,
                    suffix = "This Month"
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = "Dictation Time",
                    value = dictTime,
                    suffix = null
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    label = "Characters",
                    value = charsText,
                    suffix = null
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            // ── Search Bar + Sort ───────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = "Search transcriptions...",
                            color = TextTertiary,
                            fontSize = 14.sp,
                            fontFamily = HankenGroteskFont
                        )
                    },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailingIcon = if (searchQuery.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear",
                                    tint = TextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    } else null,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = BrandAmethyst,
                        unfocusedBorderColor = OutlineSubtle,
                        focusedContainerColor = PanelElevated,
                        unfocusedContainerColor = PanelElevated,
                        cursorColor = TextPrimary
                    ),
                    shape = FluenceShapes.Small,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .onFocusChanged { isSearchFocused = it.isFocused }
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { /* TODO: sort options */ },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(FluenceShapes.Small)
                        .background(PanelElevated)
                        .border(1.dp, OutlineSubtle, FluenceShapes.Small)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = "Sort",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Transcript Library ──────────────────────────────────────────
            if (displayedEntries.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
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
                        text = if (searchQuery.isNotEmpty()) "No results found" else "No transcriptions yet",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        fontFamily = HankenGroteskFont
                    )
                }
            } else {
                groupedEntries.forEach { (label, entries) ->
                    Text(
                        text = label,
                        color = TextTertiary,
                        fontSize = 11.sp,
                        letterSpacing = 2.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = GeistMonoFont,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    HorizontalDivider(color = OutlineSubtle, thickness = 1.dp)
                    entries.forEach { entry ->
                        TranscriptRow(
                            entry = entry,
                            isSelected = entry.id in selectedIds,
                            onToggleSelect = {
                                selectedIds = if (entry.id in selectedIds) selectedIds - entry.id
                                else selectedIds + entry.id
                            },
                            onCopy = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("transcription", entry.text))
                                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            },
                            onDelete = {
                                pendingDeleteIds = listOf(entry.id)
                                showDeleteDialog = true
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
            Spacer(modifier = Modifier.navigationBarsPadding())
        }

        // ── Recording FAB ────────────────────────────────────────────────
        AnimatedVisibility(
            visible = !isMultiSelect,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            enter = fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                    scaleIn(initialScale = 0.4f, animationSpec = tween(300, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(220, easing = FastOutSlowInEasing)) +
                   scaleOut(targetScale = 0.4f, animationSpec = tween(220, easing = FastOutSlowInEasing))
        ) {
            Surface(
                shape = CircleShape,
                color = PanelElevated,
                contentColor = TextPrimary,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .size(56.dp)
                    .border(1.dp, OutlineSubtle, CircleShape)
                    .pressScale(remember { MutableInteractionSource() })
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { Toast.makeText(context, "Start recording...", Toast.LENGTH_SHORT).show() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Start recording",
                        tint = BrandAmethyst,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        // ── Delete Confirmation Dialog ────────────────────────────────────
        if (showDeleteDialog) {
            val count = pendingDeleteIds.size
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                containerColor = DialogSurface,
                titleContentColor = TextPrimary,
                textContentColor = TextSecondary,
                title = { Text("Delete transcription${if (count > 1) "s" else ""}") },
                text = { Text("This action cannot be undone. Delete ${if (count > 1) "$count transcriptions" else "this transcription"}?") },
                confirmButton = {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            repository.deleteByIds(pendingDeleteIds)
                            selectedIds = selectedIds - pendingDeleteIds.toSet()
                        }
                        showDeleteDialog = false
                    }) {
                        Text("Delete", color = Error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            )
        }

        // ── Clear All Confirmation Dialog ─────────────────────────────────
        if (showClearAllDialog) {
            AlertDialog(
                onDismissRequest = { showClearAllDialog = false },
                containerColor = DialogSurface,
                titleContentColor = TextPrimary,
                textContentColor = TextSecondary,
                title = { Text("Clear all transcriptions") },
                text = { Text("This will permanently delete all ${allEntries.size} transcriptions. This action cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        coroutineScope.launch { repository.clearAll() }
                        showClearAllDialog = false
                    }) {
                        Text("Clear All", color = Error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllDialog = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                }
            )
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    suffix: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(Panel, FluenceShapes.Small)
            .border(1.dp, OutlineSubtle, FluenceShapes.Small)
            .padding(horizontal = 12.dp, vertical = 14.dp)
    ) {
        Text(
            text = label,
            color = TextTertiary,
            fontSize = 11.sp,
            fontFamily = HankenGroteskFont,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = SoraFont
        )
        if (suffix != null) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = suffix,
                color = TextTertiary,
                fontSize = 11.sp,
                fontFamily = HankenGroteskFont
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptRow(
    entry: TranscriptionEntry,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val bgColor by remember(isSelected) {
        mutableStateOf(if (isSelected) BrandAmethyst.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
    }

    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        if (isSelected) onToggleSelect()
                        else onCopy()
                    },
                    onLongClick = { onToggleSelect() }
                )
                .background(bgColor)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(BrandAmethyst),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = TextPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.text,
                    color = TextPrimary,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontFamily = HankenGroteskFont
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formatTimestamp(entry.timestamp),
                    color = TextTertiary,
                    fontSize = 12.sp,
                    fontFamily = GeistMonoFont
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
                        onClick = { onCopy(); showMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = Error) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = Error, modifier = Modifier.size(18.dp)) },
                        onClick = { onDelete(); showMenu = false }
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestampMs
    return when {
        diff < 60_000L -> "Just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        diff < 172_800_000L -> "Yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampMs))
    }
}
