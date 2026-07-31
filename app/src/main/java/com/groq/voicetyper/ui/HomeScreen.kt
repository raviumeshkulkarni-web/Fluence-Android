package com.groq.voicetyper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.FluenceEmptyState
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

enum class SortOption(val displayName: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    DURATION_DESC("Longest first"),
    DURATION_ASC("Shortest first")
}

private const val AVG_WPM = 40.0
private const val PREVIEW_COUNT = 5

private fun getEffectiveDurationMs(entry: TranscriptionEntry): Long {
    if (entry.durationMs > 0L) return entry.durationMs
    val wordCount = entry.text.split(Regex("\\s+")).filter { it.isNotBlank() }.size
    if (wordCount == 0) return 0L
    return ((wordCount / 140.0) * 60_000.0).toLong().coerceAtLeast(1_000L)
}

private data class DashboardStats(
    val totalWords: String,
    val timeSaved: String,
    val dictationTime: String,
    val monthlySaved: String,
    val weeklyWords: String,
    val weeklySavedHours: Double,
    val weeklySpokenHours: Double,
    val dayWordCounts: List<Int>,
    val dayLabels: List<String>
)

private fun computeDashboardStats(entries: List<TranscriptionEntry>): DashboardStats {
    val totalWords = entries.sumOf { it.text.split(Regex("\\s+")).filter { it.isNotBlank() }.size }

    val totalSavedHours = totalWords / AVG_WPM / 60.0
    val timeSaved = if (totalSavedHours < 1.0) "${(totalSavedHours * 60).toInt()}m"
        else String.format(Locale.US, "%.1fh", totalSavedHours)

    val totalDictMs = entries.sumOf { getEffectiveDurationMs(it) }
    val totalDictMinutes = totalDictMs / 60_000.0
    val dictH = totalDictMinutes.toInt() / 60
    val dictM = totalDictMinutes.toInt() % 60
    val dictationTime = if (dictH > 0) "${dictH}h ${dictM}m" else "${dictM}m"

    fun abbreviate(n: Int): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }

    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis

    val monthStart = todayStart - TimeUnit.DAYS.toMillis(30)
    val monthWords = entries.filter { it.timestamp >= monthStart }
        .sumOf { it.text.split(Regex("\\s+")).filter { it.isNotBlank() }.size }
    val monthSavedHours = monthWords / AVG_WPM / 60.0
    val monthlySaved = if (monthSavedHours < 1.0) "${(monthSavedHours * 60).toInt()}m"
        else String.format(Locale.US, "%.1fh", monthSavedHours)

    val weekStart = todayStart - TimeUnit.DAYS.toMillis(6)
    val weekEntries = entries.filter { it.timestamp >= weekStart }
    val weekWords = weekEntries.sumOf { it.text.split(Regex("\\s+")).filter { it.isNotBlank() }.size }
    val weekSavedHours = weekWords / AVG_WPM / 60.0
    val weekDictMs = weekEntries.sumOf { getEffectiveDurationMs(it) }
    val weekSpokenHours = weekDictMs / 3_600_000.0

    val dayWordCounts = (6 downTo 0).map { i ->
        val dayStart = todayStart - TimeUnit.DAYS.toMillis(i.toLong())
        val dayEnd = dayStart + TimeUnit.DAYS.toMillis(1)
        entries.filter { it.timestamp in dayStart until dayEnd }
            .sumOf { it.text.split(Regex("\\s+")).filter { it.isNotBlank() }.size }
    }

    val dayLabels = (6 downTo 0).map { i ->
        val dayCal = java.util.Calendar.getInstance()
        dayCal.add(java.util.Calendar.DAY_OF_YEAR, -i)
        when (dayCal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> "Mon"
            java.util.Calendar.TUESDAY -> "Tue"
            java.util.Calendar.WEDNESDAY -> "Wed"
            java.util.Calendar.THURSDAY -> "Thu"
            java.util.Calendar.FRIDAY -> "Fri"
            java.util.Calendar.SATURDAY -> "Sat"
            java.util.Calendar.SUNDAY -> "Sun"
            else -> ""
        }
    }

    return DashboardStats(
        totalWords = abbreviate(totalWords),
        timeSaved = timeSaved,
        dictationTime = dictationTime,
        monthlySaved = monthlySaved,
        weeklyWords = abbreviate(weekWords),
        weeklySavedHours = weekSavedHours,
        weeklySpokenHours = weekSpokenHours,
        dayWordCounts = dayWordCounts,
        dayLabels = dayLabels
    )
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

private fun groupEntries(entries: List<TranscriptionEntry>, sortOption: SortOption): List<Pair<String, List<TranscriptionEntry>>> {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val todayStart = cal.timeInMillis
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
    
    if (sortOption == SortOption.OLDEST) {
        if (older.isNotEmpty()) groups.add("EARLIER" to older)
        if (lastWeek.isNotEmpty()) groups.add("LAST WEEK" to lastWeek)
        if (thisWeek.isNotEmpty()) groups.add("THIS WEEK" to thisWeek)
        if (yesterday.isNotEmpty()) groups.add("YESTERDAY" to yesterday)
        if (today.isNotEmpty()) groups.add("TODAY" to today)
    } else {
        if (today.isNotEmpty()) groups.add("TODAY" to today)
        if (yesterday.isNotEmpty()) groups.add("YESTERDAY" to yesterday)
        if (thisWeek.isNotEmpty()) groups.add("THIS WEEK" to thisWeek)
        if (lastWeek.isNotEmpty()) groups.add("LAST WEEK" to lastWeek)
        if (older.isNotEmpty()) groups.add("EARLIER" to older)
    }
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
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val isMultiSelect = selectedIds.isNotEmpty()
    var showClearAllDialog by remember { mutableStateOf(false) }
    var pendingDeleteIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var expandedGroups by remember { mutableStateOf(setOf<String>()) }
    var currentSortOption by remember { mutableStateOf(SortOption.NEWEST) }
    var showSortSheet by remember { mutableStateOf(false) }
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

    val displayedEntries = remember(allEntries, searchQuery, currentSortOption) {
        val filtered = if (searchQuery.isBlank()) allEntries
        else allEntries.filter { it.text.contains(searchQuery, ignoreCase = true) }
        
        when (currentSortOption) {
            SortOption.NEWEST -> filtered.sortedByDescending { it.timestamp }
            SortOption.OLDEST -> filtered.sortedBy { it.timestamp }
            SortOption.DURATION_DESC -> filtered.sortedByDescending { getEffectiveDurationMs(it) }
            SortOption.DURATION_ASC -> filtered.sortedBy { getEffectiveDurationMs(it) }
        }
    }
    
    val groupedEntries = remember(displayedEntries, currentSortOption) {
        if (currentSortOption == SortOption.DURATION_DESC || currentSortOption == SortOption.DURATION_ASC) {
            listOf("ALL TRANSCRIPTIONS" to displayedEntries)
        } else {
            groupEntries(displayedEntries, currentSortOption)
        }
    }
    
    val dashboardStats = remember(allEntries) { computeDashboardStats(allEntries) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluenceSpacing.Base)
            ) {
                Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
                HomeHeader(
                    isMultiSelect = isMultiSelect,
                    selectedCount = selectedIds.size,
                    onCancelSelect = { selectedIds = emptySet() },
                    onDeleteSelect = {
                        pendingDeleteIds = selectedIds.toList()
                        showDeleteDialog = true
                    },
                    onSettings = onNavigateToSettings
                )
                Spacer(modifier = Modifier.height(FluenceSpacing.Md))
                HomeStatusBanner(
                    isKeyboardActive = isKeyboardActive,
                    sttProvider = sttProvider,
                    sttModel = sttModel,
                    context = context
                )
                Spacer(modifier = Modifier.height(FluenceSpacing.Md))
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize(),
                state = rememberLazyListState()
            ) {
                item(key = "dashboard_stats") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FluenceSpacing.Base)
                    ) {
                        Spacer(modifier = Modifier.height(FluenceSpacing.Md))
                        DashboardHeroStats(
                            totalWords = dashboardStats.totalWords,
                            timeSaved = dashboardStats.timeSaved,
                            dictationTime = dashboardStats.dictationTime,
                            monthlySaved = dashboardStats.monthlySaved
                        )
                        Spacer(modifier = Modifier.height(FluenceSpacing.Md))
                        if (allEntries.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Panel, FluenceShapes.Medium)
                                    .border(1.dp, OutlineSubtle, FluenceShapes.Medium)
                                    .padding(FluenceSpacing.Xl),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = BrandAmethyst.copy(alpha = 0.4f),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
                                Text(
                                    "Your dashboard will come alive here",
                                    color = TextSecondary,
                                    style = FluenceTypography.bodySmall
                                )
                                Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
                                Text(
                                    "Start dictating to see your weekly activity",
                                    color = TextTertiary,
                                    style = FluenceTypography.labelSmall
                                )
                            }
                        } else {
                            WeeklyActivityChart(
                                weeklyWords = dashboardStats.weeklyWords,
                                weeklySavedHours = dashboardStats.weeklySavedHours,
                                weeklySpokenHours = dashboardStats.weeklySpokenHours,
                                dayWordCounts = dashboardStats.dayWordCounts,
                                dayLabels = dashboardStats.dayLabels
                            )
                        }
                        Spacer(modifier = Modifier.height(FluenceSpacing.Md))
                        HomeSearchBar(
                            searchQuery = searchQuery,
                            onSearchChange = { searchQuery = it },
                            onSortClick = { showSortSheet = true }
                        )
                        Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
                    }
                }

                if (groupedEntries.isEmpty()) {
                    item {
                        FluenceEmptyState(
                            icon = if (searchQuery.isNotEmpty()) Icons.Default.Search else Icons.Default.Mic,
                            title = if (searchQuery.isNotEmpty()) "No results found" else "No transcriptions yet",
                            description = if (searchQuery.isNotEmpty())
                                "Try a different word, or clear the search to see everything."
                            else
                                "Tap the mic on the Fluence keyboard and start speaking — your transcriptions will appear here.",
                            modifier = Modifier.padding(vertical = FluenceSpacing.Xxl)
                        )
                    }
                } else {
                    groupedEntries.forEachIndexed { index, (label, entries) ->
                        val isExpanded = label in expandedGroups
                        val previewEntries = if (isExpanded) entries else entries.take(PREVIEW_COUNT)
                        val hiddenCount = entries.size - PREVIEW_COUNT
                        val showExpandButton = !isExpanded && hiddenCount > 0

                        item(key = "header_$label") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = FluenceSpacing.Base)
                            ) {
                                if (index == 0) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = FluenceSpacing.Md, bottom = FluenceSpacing.Xs),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = label,
                                            color = TextTertiary,
                                            style = FluenceTypography.labelSmall
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        if (!isMultiSelect) {
                                            TextButton(
                                                onClick = { showClearAllDialog = true },
                                                contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.heightIn(min = 32.dp)
                                            ) {
                                                Text("Clear All", color = TextTertiary, style = FluenceTypography.labelSmall)
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = label,
                                        color = TextTertiary,
                                        style = FluenceTypography.labelSmall,
                                        modifier = Modifier.padding(top = FluenceSpacing.Md, bottom = FluenceSpacing.Xs)
                                    )
                                }
                                HorizontalDivider(color = OutlineSubtle, thickness = 1.dp)
                            }
                        }

                        items(previewEntries, key = { "${label}_${it.id}" }) { entry ->
                            TranscriptRow(
                                entry = entry,
                                modifier = Modifier.animateItemPlacement(),
                                isSelected = entry.id in selectedIds,
                                isMultiSelect = isMultiSelect,
                                onToggleSelect = { selectedIds = if (entry.id in selectedIds) selectedIds - entry.id else selectedIds + entry.id },
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

                        if (showExpandButton) {
                            item(key = "expand_$label") {
                                TextButton(
                                    onClick = { expandedGroups = expandedGroups + label },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = FluenceSpacing.Base),
                                    contentPadding = PaddingValues(vertical = FluenceSpacing.Sm)
                                ) {
                                    Text(
                                        "Show $hiddenCount more",
                                        color = TextTertiary,
                                        style = FluenceTypography.labelMedium
                                    )
                                }
                            }
                        }

                        if (isExpanded && entries.size > PREVIEW_COUNT) {
                            item(key = "collapse_$label") {
                                TextButton(
                                    onClick = { expandedGroups = expandedGroups - label },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = FluenceSpacing.Base),
                                    contentPadding = PaddingValues(vertical = FluenceSpacing.Sm)
                                ) {
                                    Text(
                                        "Show less",
                                        color = TextTertiary,
                                        style = FluenceTypography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                    item(key = "bottom_spacer") {
                        Spacer(modifier = Modifier.height(FluenceSpacing.Xxl))
                        Spacer(modifier = Modifier.navigationBarsPadding())
                    }
                }
            }
        }

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
                    }) { Text("Delete", color = Error) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = TextSecondary) }
                }
            )
        }
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
                    }) { Text("Clear All", color = Error) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel", color = TextSecondary) }
                }
            )
        }
        
        if (showSortSheet) {
            SortBottomSheet(
                selectedOption = currentSortOption,
                onOptionSelected = { currentSortOption = it },
                onDismiss = { showSortSheet = false }
            )
        }
    }
}

@Composable
private fun HomeHeader(
    isMultiSelect: Boolean,
    selectedCount: Int,
    onCancelSelect: () -> Unit,
    onDeleteSelect: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isMultiSelect) {
            IconButton(onClick = onCancelSelect, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Close, "Exit selection", tint = TextPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
            Text(
                text = "$selectedCount selected",
                color = TextPrimary,
                style = FluenceTypography.titleMedium
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onDeleteSelect, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Delete, "Delete selected", tint = Error, modifier = Modifier.size(18.dp))
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
            FluenceProductLockup(productName = "Transcribe", orbSize = 32.dp, wordmarkSize = 20.sp)
            Spacer(modifier = Modifier.weight(1f))
            IconButton(
                onClick = onSettings,
                modifier = Modifier.size(44.dp).pressScale(remember { MutableInteractionSource() })
            ) {
                Icon(Icons.Default.Settings, "Settings", tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun HomeStatusBanner(
    isKeyboardActive: Boolean,
    sttProvider: String,
    sttModel: String,
    context: Context
) {
    val statusColor = if (isKeyboardActive) Success else Error
    val statusText = if (isKeyboardActive) "Ready" else "Inactive"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, FluenceShapes.Medium)
            .border(1.dp, OutlineSubtle, FluenceShapes.Medium)
            .clickable(onClickLabel = "Open keyboard settings") {
                context.startActivity(android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            .padding(horizontal = FluenceSpacing.Md, vertical = FluenceSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
        Text(statusText, color = TextPrimary, style = FluenceTypography.bodySmall.copy(fontWeight = FontWeight.Medium))
        Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
        Text("\u00b7", color = TextTertiary, style = FluenceTypography.bodySmall)
        Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
        Text(
            "$sttProvider \u00b7 $sttModel",
            color = TextSecondary,
            style = FluenceTypography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DashboardHeroStats(
    totalWords: String,
    timeSaved: String,
    dictationTime: String,
    monthlySaved: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, FluenceShapes.Medium, clip = false)
            .background(Panel, FluenceShapes.Medium)
            .border(1.dp, OutlineSubtle, FluenceShapes.Medium)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DashboardStatCell(
                    title = "Words Brought to Life",
                    value = totalWords,
                    subtitle = "till today",
                    icon = Icons.AutoMirrored.Filled.ShortText,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(OutlineSubtle)
                )
                DashboardStatCell(
                    title = "Typing Time Saved",
                    value = timeSaved,
                    subtitle = "till today",
                    icon = Icons.Default.Speed,
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(color = OutlineSubtle, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DashboardStatCell(
                    title = "Dictation Time",
                    value = dictationTime,
                    subtitle = "till today",
                    icon = Icons.Default.AccessTime,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(OutlineSubtle)
                )
                DashboardStatCell(
                    title = "Typing Time Saved",
                    value = monthlySaved,
                    subtitle = "in last 30 days",
                    icon = Icons.Default.CalendarToday,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun DashboardStatCell(
    title: String,
    value: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(horizontal = FluenceSpacing.Md, vertical = FluenceSpacing.Base),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FluenceSpacing.Xs)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = title,
                color = TextTertiary,
                style = FluenceTypography.labelSmall.copy(letterSpacing = 0.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
        Text(
            text = value,
            color = TextPrimary,
            style = FluenceTypography.headlineLarge.copy(
                fontFamily = SoraFont,
                fontWeight = FontWeight.SemiBold
            )
        )
        Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
        Text(
            text = subtitle,
            color = TextTertiary,
            style = FluenceTypography.labelSmall
        )
    }
}

@Composable
private fun WeeklyActivityChart(
    weeklyWords: String,
    weeklySavedHours: Double,
    weeklySpokenHours: Double,
    dayWordCounts: List<Int>,
    dayLabels: List<String>
) {
    val maxCount = dayWordCounts.max().coerceAtLeast(1)
    val barGradient = Brush.verticalGradient(
        colors = listOf(BrandAmethyst, BrandAmethyst.copy(alpha = 0.35f))
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, FluenceShapes.Medium, clip = false)
            .background(Panel, FluenceShapes.Medium)
            .border(1.dp, OutlineSubtle, FluenceShapes.Medium)
            .padding(FluenceSpacing.Md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Weekly Activity",
                color = TextPrimary,
                style = FluenceTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            val savedText = if (weeklySavedHours < 1.0) "${(weeklySavedHours * 60).toInt()}m"
                else String.format(Locale.US, "%.1fh", weeklySavedHours)
            val spokenText = if (weeklySpokenHours < 1.0) "${(weeklySpokenHours * 60).toInt()}m"
                else String.format(Locale.US, "%.1fh", weeklySpokenHours)
            Text(
                text = "$weeklyWords words \u00b7 ${savedText} saved \u00b7 ${spokenText} spoken",
                color = TextTertiary,
                style = FluenceTypography.labelSmall
            )
        }

        Spacer(modifier = Modifier.height(FluenceSpacing.Md))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FluenceSpacing.Sm)
        ) {
            dayWordCounts.forEachIndexed { index, count ->
                val targetFraction = count.toFloat() / maxCount
                val animatedFraction by animateFloatAsState(
                    targetValue = targetFraction,
                    animationSpec = tween(durationMillis = 600, delayMillis = index * 80),
                    label = "bar_$index"
                )

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (count > 0) count.toString() else "",
                        color = TextTertiary,
                        style = FluenceTypography.labelSmall.copy(fontSize = 10.sp),
                        modifier = Modifier.heightIn(min = 14.dp)
                    )
                    Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        // Baseline lane — gives every day a visible track so the
                        // chart reads as a dashboard even on low-activity days.
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(6.dp))
                                .background(TextPrimary.copy(alpha = 0.04f))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(animatedFraction.coerceAtLeast(0.02f))
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(barGradient)
                        )
                    }
                    Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
                    Text(
                        text = dayLabels[index],
                        color = TextTertiary,
                        style = FluenceTypography.labelSmall.copy(fontSize = 10.sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSearchBar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onSortClick: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchChange,
        placeholder = {
            Text("Search transcriptions...", color = TextTertiary, style = FluenceTypography.bodySmall)
        },
        singleLine = true,
        leadingIcon = {
            Icon(Icons.Default.Search, "Search", tint = TextSecondary, modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Default.Close, "Clear", tint = TextTertiary, modifier = Modifier.size(14.dp))
                    }
                }
                IconButton(
                    onClick = onSortClick,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = "Sort",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = OutlineSubtle,
            unfocusedBorderColor = OutlineSubtle,
            focusedContainerColor = PanelElevated,
            unfocusedContainerColor = PanelElevated,
            cursorColor = TextPrimary
        ),
        shape = FluenceShapes.Small,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .focusRequester(focusRequester),
        textStyle = FluenceTypography.bodySmall
    )
}

@Composable
private fun TranscriptRow(
    entry: TranscriptionEntry,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    onToggleSelect: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val bgColor = if (isSelected) TextPrimary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    if (isSelected) stateDescription = "Selected"
                    onClick(
                        label = if (isMultiSelect || isSelected) "Toggle selection" else "Copy transcription"
                    ) {
                        if (isMultiSelect || isSelected) onToggleSelect() else onCopy()
                        true
                    }
                    onLongClick(label = "Select") {
                        onToggleSelect()
                        true
                    }
                }
                .pointerInput(isSelected, isMultiSelect) {
                    detectTapGestures(
                        onLongPress = { onToggleSelect() },
                        onTap = {
                            if (isMultiSelect || isSelected) onToggleSelect() else onCopy()
                        }
                    )
                }
                .background(bgColor)
                .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(TextPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Check, null, tint = Canvas, modifier = Modifier.size(12.dp))
                }
                Spacer(modifier = Modifier.width(FluenceSpacing.Md))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.text, color = TextPrimary, style = FluenceTypography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatTimestamp(entry.timestamp), color = TextTertiary, style = FluenceTypography.labelMedium.copy(fontFamily = GeistMonoFont))
            }
            Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Default.MoreVert, "Options", tint = TextTertiary, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy", color = TextPrimary, style = FluenceTypography.bodySmall) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null, tint = TextSecondary, modifier = Modifier.size(16.dp)) },
                        onClick = { onCopy(); showMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", color = Error, style = FluenceTypography.bodySmall) },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = Error, modifier = Modifier.size(16.dp)) },
                        onClick = { onDelete(); showMenu = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortBottomSheet(
    selectedOption: SortOption,
    onOptionSelected: (SortOption) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PanelElevated,
        contentColor = TextPrimary,
        shape = FluenceShapes.Large,
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(32.dp)
                    .height(4.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    .background(TextPrimary.copy(alpha = 0.18f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Text(
                text = "Sort by",
                color = TextPrimary,
                style = FluenceTypography.headlineMedium,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 12.dp)
            )
            
            SortOption.values().forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = option == selectedOption,
                            role = Role.RadioButton,
                            onClick = {
                                onOptionSelected(option)
                                onDismiss()
                            }
                        )
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = option.displayName,
                        color = if (option == selectedOption) TextPrimary else TextSecondary,
                        style = FluenceTypography.bodyLarge.copy(
                            fontWeight = if (option == selectedOption) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (option == selectedOption) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null, // selection announced by selectable() state
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
