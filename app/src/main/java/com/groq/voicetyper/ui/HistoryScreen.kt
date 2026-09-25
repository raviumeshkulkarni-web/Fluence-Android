package com.groq.voicetyper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import com.groq.voicetyper.FeedbackBus
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.groq.voicetyper.ui.icons.FluenceIcons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.FluenceEmptyState
import com.groq.voicetyper.history.HistoryRepository
import com.groq.voicetyper.history.StatsCalculator
import com.groq.voicetyper.history.TranscriptionEntry
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.FluenceMotion
import com.groq.voicetyper.theme.LocalMotionPreferences
import com.groq.voicetyper.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class SortOption(val displayName: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    DURATION_DESC("Longest first"),
    DURATION_ASC("Shortest first"),
    WORDS_DESC("Most words"),
    WORDS_ASC("Fewest words")
}

enum class DateFilter(val tabLabel: String, val accessibilityLabel: String) {
    ALL("All time", "Show all time"),
    TODAY("Today", "Show today"),
    YESTERDAY("Yesterday", "Show yesterday")
}

private const val DAY_MS = 86_400_000L

private data class DayGroup(
    val key: String,
    val label: String,
    val items: List<TranscriptionEntry>,
)

private fun midnightOf(timestampMs: Long): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = timestampMs
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/**
 * Day-based grouping with Windows History labels: Today / Yesterday /
 * weekday name / "August 28" (year appended off-year). Device locale for
 * user-facing labels, like Windows' toLocaleDateString. Input order is
 * preserved inside each day (callers pre-sort for duration/word sorts);
 * only the day buckets themselves order chronologically (reversed for
 * oldest-first).
 */
private fun groupEntries(entries: List<TranscriptionEntry>, oldestFirst: Boolean): List<DayGroup> {
    val locale = Locale.getDefault()
    val keyFmt = SimpleDateFormat("yyyy-M-d", locale)
    val weekdayFmt = SimpleDateFormat("EEEE", locale)
    val monthDayFmt = SimpleDateFormat("MMMM d", locale)
    val monthDayYearFmt = SimpleDateFormat("MMMM d, yyyy", locale)
    val todayStart = midnightOf(System.currentTimeMillis())
    val yesterdayStart = todayStart - DAY_MS
    val yearNow = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    data class Acc(val key: String, val label: String, val dayStart: Long, val items: MutableList<TranscriptionEntry> = mutableListOf())
    val buckets = LinkedHashMap<String, Acc>()
    for (entry in entries) {
        val dayStart = midnightOf(entry.timestamp)
        val acc = buckets.getOrPut(keyFmt.format(Date(dayStart))) {
            val label = when {
                dayStart >= todayStart -> "Today"
                dayStart >= yesterdayStart -> "Yesterday"
                dayStart >= todayStart - 6 * DAY_MS -> weekdayFmt.format(Date(dayStart))
                java.util.Calendar.getInstance().apply { timeInMillis = dayStart }
                    .get(java.util.Calendar.YEAR) == yearNow -> monthDayFmt.format(Date(dayStart))
                else -> monthDayYearFmt.format(Date(dayStart))
            }
            Acc(keyFmt.format(Date(dayStart)), label, dayStart)
        }
        acc.items.add(entry)
    }
    val ordered = buckets.values.toList()
    val sorted = if (oldestFirst) ordered.sortedByDescending { it.dayStart }
    else ordered.sortedBy { it.dayStart }
    return sorted.map { DayGroup(it.key, it.label, it.items) }
}

private fun formatTimestamp(timestampMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestampMs
    return when {
        diff < 60_000L -> "Just now"
        diff < 3_600_000L -> "${diff / 60_000L}m ago"
        diff < 86_400_000L -> "${diff / 3_600_000L}h ago"
        diff < 172_800_000L -> "Yesterday, " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestampMs))
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestampMs))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    onOpenDrawer: () -> Unit,
    showDrawerButton: Boolean = true,
    onOpenDetail: (Long) -> Unit = {},
    onNavigateToSttConfig: () -> Unit = {},
    onRequestPermission: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val reducedMotion = LocalMotionPreferences.current.reducedMotion
    var isKeyboardActive by remember { mutableStateOf(false) }
    var isMicGranted by remember { mutableStateOf(false) }
    var isApiKeySet by remember { mutableStateOf(false) }
    var allEntries by remember { mutableStateOf<List<TranscriptionEntry>>(emptyList()) }
    val longSetSaver = Saver<MutableState<Set<Long>>, ArrayList<Long>>(
        save = { arrayListOf<Long>().apply { addAll(it.value) } },
        restore = { mutableStateOf(it.toSet()) }
    )
    val stringSetSaver = Saver<MutableState<Set<String>>, ArrayList<String>>(
        save = { arrayListOf<String>().apply { addAll(it.value) } },
        restore = { mutableStateOf(it.toSet()) }
    )

    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Debounced applied query (Windows 300ms parity): without this every
    // keystroke re-filters, re-sorts, re-groups the whole in-memory table
    // and relayouts the list. The field stays live; the list follows.
    var activeQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        delay(300)
        activeQuery = searchQuery
    }
    var selectedIds by rememberSaveable(saver = longSetSaver) { mutableStateOf(setOf<Long>()) }
    val isMultiSelect = selectedIds.isNotEmpty()
    var showClearAllDialog by remember { mutableStateOf(false) }
    var pendingDeleteIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var collapsedDays by rememberSaveable(saver = stringSetSaver) { mutableStateOf(setOf<String>()) }
    var dateFilter by rememberSaveable { mutableStateOf(DateFilter.ALL) }
    var expandedEntryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var currentSortOption by rememberSaveable { mutableStateOf(SortOption.NEWEST) }
    var showSortSheet by remember { mutableStateOf(false) }
    val repository = remember { HistoryRepository.init(context); HistoryRepository }

    LaunchedEffect(Unit) {
        val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        isKeyboardActive = imeManager.enabledInputMethodList.any { it.packageName == context.packageName }
        isMicGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val sttPreset = com.groq.voicetyper.SecurityUtils.getSttPreset(context)
        isApiKeySet = sttPreset == "offline" || !com.groq.voicetyper.SecurityUtils.getProviderApiKey(context, "stt", sttPreset).isNullOrBlank()
        repository.getAll().collect { allEntries = it }
    }

    // Local-midnight day bounds for the date filter (History display
    // groups are local-time, like Windows).
    val todayStart = midnightOf(System.currentTimeMillis())
    val yesterdayStart = todayStart - DAY_MS

    val displayedEntries = remember(allEntries, activeQuery, currentSortOption, dateFilter) {
        val ranged = when (dateFilter) {
            DateFilter.TODAY -> allEntries.filter { it.timestamp >= todayStart }
            DateFilter.YESTERDAY -> allEntries.filter { it.timestamp in yesterdayStart until todayStart }
            DateFilter.ALL -> allEntries
        }
        val q = activeQuery.trim()
        val filtered = if (q.isBlank()) ranged
        else ranged.filter { it.text.contains(q, ignoreCase = true) }

        // Decorate-once for the WORDS_* sorts: one O(n) scan here instead of
        // O(n log n) full-text rescans inside the comparator. Keyed by entry
        // (not id: ids are 0 until Room persists a row). Empty unless a
        // word-count sort is active so the default views pay nothing.
        val needsCounts = currentSortOption == SortOption.WORDS_DESC ||
            currentSortOption == SortOption.WORDS_ASC
        val wordCounts = if (needsCounts) {
            filtered.associateWith { StatsCalculator.wordCountOf(it.text) }
        } else {
            emptyMap()
        }

        when (currentSortOption) {
            SortOption.NEWEST -> filtered.sortedByDescending { it.timestamp }
            SortOption.OLDEST -> filtered.sortedBy { it.timestamp }
            SortOption.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
            SortOption.DURATION_ASC -> filtered.sortedBy { it.durationMs }
            // Comparators below compare precomputed Ints (see above).
            SortOption.WORDS_DESC -> filtered.sortedByDescending { wordCounts.getValue(it) }
            SortOption.WORDS_ASC -> filtered.sortedBy { wordCounts.getValue(it) }
        }
    }

    val groupedEntries = remember(displayedEntries, currentSortOption) {
        groupEntries(displayedEntries, currentSortOption == SortOption.OLDEST)
    }

    // Last visible row drives the timeline-rail end stop.
    val lastVisibleId = remember(groupedEntries, collapsedDays) {
        groupedEntries.asReversed()
            .firstNotNullOfOrNull { group ->
                if (group.key in collapsedDays) null
                else group.items.lastOrNull()?.id
            }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: drawer + title + selection actions (64dp height matching Capture top app bar).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = FluenceSpacing.Base),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isMultiSelect) {
                    IconButton(onClick = { selectedIds = emptySet() }, modifier = Modifier.size(48.dp)) {
                        Icon(FluenceIcons.X, "Exit selection", tint = colors.textPrimary, modifier = Modifier.size(24.dp))
                    }
                    Spacer(modifier = Modifier.width(FluenceSpacing.Base))
                    Text(
                        text = "${selectedIds.size} selected",
                        color = colors.textPrimary,
                        style = FluenceTypography.headlineMedium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            pendingDeleteIds = selectedIds.toList()
                            showDeleteDialog = true
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(FluenceIcons.Trash2, "Delete selected", tint = colors.error, modifier = Modifier.size(20.dp))
                    }
                } else {
                    // Permanent sidebar is already visible in expanded windows —
                    // no hamburger; the title row keeps its own padding rhythm.
                    if (showDrawerButton) {
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier.size(48.dp).pressScale(remember { MutableInteractionSource() })
                        ) {
                            Icon(FluenceIcons.Menu, "Open menu", tint = colors.textSecondary, modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(FluenceSpacing.Base))
                    }
                    Text(
                        text = "History",
                        color = colors.textPrimary,
                        style = FluenceTypography.headlineMedium
                    )
                }
            }

            if (!isMultiSelect) {
                Text(
                    text = "Browse and search every transcription on this device",
                    color = colors.textSecondary,
                    style = FluenceTypography.bodySmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = FluenceSpacing.Base,
                            end = FluenceSpacing.Base,
                            bottom = FluenceSpacing.Sm
                        )
                )
            }

            // Card fills the remaining viewport (Windows parity): search row with Clear All,
            // filters, then the list scrolling inside.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = FluenceSpacing.Base)
                    .clip(FluenceShapes.Medium)
                    .background(colors.cardSurface)
                    .border(1.dp, colors.cardBorder, FluenceShapes.Medium)
            ) {
                // Search row with Clear All button next to it (Windows parity)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HistorySearchBar(
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                    val clearInteraction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .heightIn(min = 44.dp)
                            .pressScale(clearInteraction)
                            .clip(FluenceShapes.Small)
                            .background(colors.panel)
                            .border(1.dp, colors.outlineSubtle, FluenceShapes.Small)
                            .clickable(
                                interactionSource = clearInteraction,
                                indication = androidx.compose.foundation.LocalIndication.current,
                                enabled = allEntries.isNotEmpty(),
                                onClick = { showClearAllDialog = true }
                            )
                            .padding(horizontal = FluenceSpacing.Md, vertical = FluenceSpacing.Sm),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Clear All",
                            color = if (allEntries.isNotEmpty()) colors.errorText else colors.textDisabled,
                            style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(FluenceSpacing.Xs))

                // Date filter (Windows All time / Today / Yesterday), same
                // segmented control as the Activity chart selector.
                FluenceSegmentedControl(
                    options = remember {
                        DateFilter.entries.map { SegmentChoice(it.tabLabel, it.accessibilityLabel) }
                    },
                    selectedIndex = dateFilter.ordinal,
                    onSelect = { dateFilter = DateFilter.entries[it] },
                    modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
                )
                Spacer(modifier = Modifier.height(FluenceSpacing.Xs))

                // Sort control + live count (Windows "N shown").
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FluenceSpacing.Base),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { showSortSheet = true },
                        contentPadding = PaddingValues(horizontal = FluenceSpacing.Xs),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(
                            imageVector = FluenceIcons.ArrowUpDown,
                            contentDescription = null,
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
                        Text(
                            text = currentSortOption.displayName,
                            color = colors.textSecondary,
                            style = FluenceTypography.labelMedium
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = if (displayedEntries.size == 1) "1 shown"
                        else "${displayedEntries.size} shown",
                        color = colors.textTertiary,
                        style = FluenceTypography.labelSmall.copy(fontFamily = GeistMonoFont)
                    )
                }
                Spacer(modifier = Modifier.height(FluenceSpacing.Xs))

            LazyColumn(
                modifier = Modifier.weight(1f),
                state = rememberLazyListState()
            ) {
                if (groupedEntries.isEmpty()) {
                    item {
                        val (emptyActionLabel, emptyAction) = remember(activeQuery, dateFilter, isKeyboardActive, isMicGranted, isApiKeySet) {
                            when {
                                activeQuery.isNotEmpty() -> null to null
                                dateFilter != DateFilter.ALL -> "Show all time" to {
                                    dateFilter = DateFilter.ALL
                                }
                                !isKeyboardActive -> "Enable Keyboard" to {
                                    context.startActivity(android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                }
                                !isMicGranted -> "Grant Microphone Permission" to onRequestPermission
                                !isApiKeySet -> "Configure API Key" to onNavigateToSttConfig
                                else -> null to null
                            }
                        }

                        FluenceEmptyState(
                            icon = if (activeQuery.isNotEmpty() || dateFilter != DateFilter.ALL) FluenceIcons.Search else FluenceIcons.Mic,
                            title = if (activeQuery.isNotEmpty() || dateFilter != DateFilter.ALL) "No matches" else "No transcriptions yet",
                            description = if (activeQuery.isNotEmpty())
                                "Try a different word, or clear the search to see everything."
                            else if (dateFilter != DateFilter.ALL)
                                "Nothing in this period yet."
                            else if (!isKeyboardActive)
                                "Enable the Fluence keyboard in Android Settings to begin voice typing."
                            else if (!isMicGranted)
                                "Microphone permission is required to listen and transcribe your voice."
                            else if (!isApiKeySet)
                                "Configure your Speech-to-Text provider API key to start transcribing."
                            else
                                "Tap the mic on the Fluence keyboard in any app and start speaking — your transcriptions will appear here.",
                            modifier = Modifier.padding(vertical = FluenceSpacing.Xxl),
                            actionLabel = emptyActionLabel,
                            onAction = emptyAction
                        )
                    }
                } else {
                    groupedEntries.forEach { group ->
                        // Group expand/collapse animates the overflow rows
                        // (fade + vertical expand over the structural tier);
                        // reduced motion toggles them instantly.
                        val rowEnter: EnterTransition = if (reducedMotion) EnterTransition.None
                        else fadeIn(tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing)) +
                            expandVertically(tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing))
                        val rowExit: ExitTransition = if (reducedMotion) ExitTransition.None
                        else fadeOut(tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing)) +
                            shrinkVertically(tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing))

                        stickyHeader(key = "day_${group.key}") {
                            DayGroupHeader(
                                label = group.label,
                                countText = if (group.items.size == 1) "1 transcription"
                                else "${group.items.size} transcriptions",
                                collapsed = group.key in collapsedDays,
                                onToggle = {
                                    collapsedDays = if (group.key in collapsedDays) {
                                        collapsedDays - group.key
                                    } else {
                                        collapsedDays + group.key
                                    }
                                }
                            )
                        }

                        itemsIndexed(
                            group.items,
                            key = { _, entry -> "row_${entry.id}" }
                        ) { _, entry ->
                            AnimatedVisibility(
                                visible = group.key !in collapsedDays,
                                enter = rowEnter,
                                exit = rowExit,
                                modifier = Modifier.animateItemPlacement(),
                            ) {
                                HistoryTranscriptRow(
                                    entry = entry,
                                    isSelected = entry.id in selectedIds,
                                    isMultiSelect = isMultiSelect,
                                    expanded = entry.id == expandedEntryId,
                                    isRailEnd = entry.id == lastVisibleId,
                                    highlightQuery = activeQuery,
                                    onToggleSelect = { selectedIds = if (entry.id in selectedIds) selectedIds - entry.id else selectedIds + entry.id },
                                    onToggleExpand = {
                                        expandedEntryId =
                                            if (expandedEntryId == entry.id) null else entry.id
                                    },
                                    onOpenDetail = { onOpenDetail(entry.id) },
                                    onCopy = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("transcription", entry.text))
                                        FeedbackBus.show("Copied to clipboard")
                                    },
                                    onDelete = {
                                        pendingDeleteIds = listOf(entry.id)
                                        showDeleteDialog = true
                                    }
                                )
                            }
                        }

                        // All rows render flat (no per-group paging — Windows
                        // parity; LazyColumn virtualizes off-screen rows and
                        // the timestamp index serves the source query, so the
                        // now-unbounded table stays cheap).
                    }
                    item(key = "bottom_spacer") {
                        Spacer(modifier = Modifier.height(FluenceSpacing.Md))
                    }
                }
            }
        }
        }

        if (showDeleteDialog) {
            val count = pendingDeleteIds.size
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                containerColor = colors.dialog,
                titleContentColor = colors.textPrimary,
                textContentColor = colors.textSecondary,
                title = { Text("Delete transcription${if (count > 1) "s" else ""}") },
                text = { Text("This action cannot be undone. Delete ${if (count > 1) "$count transcriptions" else "this transcription"}?") },
                confirmButton = {
                    TextButton(onClick = {
                        coroutineScope.launch {
                            repository.deleteByIds(pendingDeleteIds)
                            selectedIds = selectedIds - pendingDeleteIds.toSet()
                        }
                        showDeleteDialog = false
                    }) { Text("Delete", color = colors.errorText, style = FluenceTypography.labelLarge) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = colors.textSecondary, style = FluenceTypography.labelLarge) }
                }
            )
        }
        if (showClearAllDialog) {
            AlertDialog(
                onDismissRequest = { showClearAllDialog = false },
                containerColor = colors.dialog,
                titleContentColor = colors.textPrimary,
                textContentColor = colors.textSecondary,
                title = { Text("Clear History") },
                text = { Text("This will permanently delete all ${allEntries.size} transcriptions. This action cannot be undone. Statistics are unaffected.") },
                confirmButton = {
                    TextButton(onClick = {
                        coroutineScope.launch { repository.clearAll() }
                        showClearAllDialog = false
                    }) { Text("Clear All", color = colors.errorText, style = FluenceTypography.labelLarge) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel", color = colors.textSecondary, style = FluenceTypography.labelLarge) }
                }
            )
        }

        if (showSortSheet) {
            HistorySortBottomSheet(
                selectedOption = currentSortOption,
                onOptionSelected = { currentSortOption = it },
                onDismiss = { showSortSheet = false }
            )
        }
    }
}

@Composable
private fun HistorySearchBar(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchChange,
        placeholder = {
            Text("Search transcriptions…", color = colors.textSecondary, style = FluenceTypography.bodySmall)
        },
        singleLine = true,
        leadingIcon = {
            Icon(FluenceIcons.Search, "Search", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
            if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(FluenceSpacing.Xxl)) {
                    Icon(FluenceIcons.X, "Clear search", tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
        },
        shape = FluenceShapes.Medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = if (colors.isLight) colors.brandCyan else colors.textPrimary,
            unfocusedBorderColor = colors.inputBorder,
            focusedContainerColor = colors.panel,
            unfocusedContainerColor = colors.panel,
            focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary,
            cursorColor = colors.textPrimary
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = FluenceSpacing.Xxl)
    )
}

/** Windows historyItemMeta parity: "7m ago · 87 words · 6s". */
private fun historyRowMeta(entry: TranscriptionEntry): String {
    val parts = mutableListOf<String>()
    val words = StatsCalculator.wordCountOf(entry.text)
    if (words > 0) parts.add(if (words == 1) "1 word" else "$words words")
    if (entry.durationMs > 0) {
        val s = Math.round(entry.durationMs / 1000.0).toInt()
        parts.add(if (s < 60) "${s}s" else "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s")
    }
    return (listOf(formatTimestamp(entry.timestamp)) + parts).joinToString(" · ")
}

@Composable
private fun ModeBadge(isAgentMode: Boolean) {
    val colors = PrecisionTheme.colors
    // Windows parity: the row badge renders the raw mode string; agent is
    // neutral (amethyst is not a badge tone per DESIGN_SYSTEM.md) and
    // transcription keeps the success treatment both sides use. White mode
    // follows the light container alphas (success 10%).
    val tone = if (isAgentMode) colors.textSecondary else colors.success
    val badgeAlpha = if (colors.isLight && !isAgentMode) 0.10f else 0.15f
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(tone.copy(alpha = badgeAlpha))
            .padding(horizontal = FluenceSpacing.Sm, vertical = 3.dp)
    ) {
        Text(
            text = if (isAgentMode) "agent" else "transcription",
            color = tone,
            style = FluenceTypography.labelSmall.copy(
                fontFamily = GeistMonoFont,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

/** Case-insensitive search-match highlighting for transcript rows. Returns
 * plain text when the query is blank. The span sets only a background so the
 * row's own text color (including the synced-foreign dimming) is preserved.
 * The mark wash is caller-provided (Windows mark-search: cyan wash in dark,
 * teal 16% in white; dark keeps the existing neutral treatment, frozen). */
private fun highlightQueryMatches(
    text: String,
    query: String,
    mark: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val match = text.indexOf(q, cursor, ignoreCase = true)
            if (match < 0) {
                append(text.substring(cursor))
                break
            }
            append(text.substring(cursor, match))
                    withStyle(SpanStyle(background = mark)) {
                append(text.substring(match, match + q.length))
            }
            cursor = match + q.length
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HistoryTranscriptRow(
    entry: TranscriptionEntry,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    expanded: Boolean,
    isRailEnd: Boolean,
    highlightQuery: String = "",
    onToggleSelect: () -> Unit,
    onToggleExpand: () -> Unit,
    onOpenDetail: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    val foreign = com.groq.voicetyper.sync.SyncAccounts.isForeign(entry.syncAccount)
    val bgColor = if (isSelected) colors.textPrimary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent
    val rowInteraction = remember { MutableInteractionSource() }
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(bgColor)
                .combinedClickable(
                    interactionSource = rowInteraction,
                    indication = androidx.compose.foundation.LocalIndication.current,
                    onClickLabel = if (isMultiSelect || isSelected) "Toggle selection"
                    else if (expanded) "Collapse" else "Expand",
                    onLongClickLabel = "Select",
                    onClick = {
                        if (isMultiSelect || isSelected) onToggleSelect() else onToggleExpand()
                    },
                    onLongClick = {
                        onToggleSelect()
                    }
                )
                .semantics(mergeDescendants = true) {
                    if (isSelected) stateDescription = "Selected"
                },
            verticalAlignment = Alignment.Top
        ) {
            // Timeline rail (Windows parity): per-row segments so the rail
            // spans the scrolled height; the last visible row ends it.
            Box(modifier = Modifier.width(40.dp).fillMaxHeight()) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .then(if (isRailEnd) Modifier.height(FluenceSpacing.Lg) else Modifier.fillMaxHeight())
                        .align(Alignment.TopCenter)
                        .offset(x = FluenceSpacing.N7)
                        .background(colors.outlineSubtle)
                )
                Box(
                    modifier = Modifier
                        .size(FluenceSpacing.N7)
                        .align(Alignment.TopCenter)
                        .offset(x = FluenceSpacing.N7, y = FluenceSpacing.Base)
                        .background(colors.cardSurface, CircleShape)
                        .border(1.5.dp, colors.inputBorder, CircleShape)
                )
            }
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(colors.textPrimary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(FluenceIcons.Check, null, tint = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.canvas, modifier = Modifier.size(12.dp))
                }
                Spacer(modifier = Modifier.width(FluenceSpacing.Md))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(
                        start = FluenceSpacing.Xs,
                        end = FluenceSpacing.Base,
                        top = FluenceSpacing.Md,
                        bottom = FluenceSpacing.Md
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        historyRowMeta(entry),
                        color = colors.textSecondary,
                        style = FluenceTypography.labelMedium.copy(fontFamily = GeistMonoFont),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    ModeBadge(isAgentMode = entry.isAgentMode)
                }
                if (foreign) {
                    Text(
                        "Synced to another account",
                        color = colors.textTertiary,
                        style = FluenceTypography.labelSmall
                    )
                }
                Text(
                    highlightQueryMatches(
                        entry.text,
                        highlightQuery,
                        if (colors.isLight) colors.brandCyan.copy(alpha = 0.16f)
                        else colors.textPrimary.copy(alpha = 0.24f),
                    ),
                    color = if (foreign) colors.textSecondary else colors.textPrimary,
                    style = FluenceTypography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(FluenceSpacing.Xxl)) {
                    Icon(FluenceIcons.MoreHorizontal, "Options", tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .background(colors.dialog, FluenceShapes.Medium)
                        .border(1.dp, colors.outlineSubtle, FluenceShapes.Medium)
                ) {
                    DropdownMenuItem(
                        text = { Text("View", color = colors.textPrimary, style = FluenceTypography.bodyLarge) },
                        leadingIcon = { Icon(FluenceIcons.Eye, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp)) },
                        onClick = { onOpenDetail(); showMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Copy", color = colors.textPrimary, style = FluenceTypography.bodyLarge) },
                        leadingIcon = { Icon(FluenceIcons.Copy, null, tint = colors.textSecondary, modifier = Modifier.size(20.dp)) },
                        onClick = { onCopy(); showMenu = false }
                    )
                    if (!foreign) {
                        DropdownMenuItem(
                            text = { Text("Delete", color = colors.error, style = FluenceTypography.bodyLarge) },
                            leadingIcon = { Icon(FluenceIcons.Trash2, null, tint = colors.error, modifier = Modifier.size(20.dp)) },
                            onClick = { onDelete(); showMenu = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayGroupHeader(
    label: String,
    countText: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
) {
    val colors = PrecisionTheme.colors
    // Windows collapsible day headers: the whole header toggles, chevron
    // rotates, rows animate away. Opaque card background so sliding rows
    // never show through while stuck.
    val reducedMotion = LocalMotionPreferences.current.reducedMotion
    val chevronAngle by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(
            durationMillis = FluenceMotion.durationImmediate
        ),
        label = "group_chevron"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.cardSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (collapsed) "Expand $label" else "Collapse $label",
                    onClick = onToggle
                )
                .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FluenceIcons.ChevronDown,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(chevronAngle)
            )
            Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
            Text(
                text = label.uppercase(Locale.US),
                color = colors.textTertiary,
                style = FluenceTypography.labelSmall.copy(
                    fontFamily = GeistMonoFont,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                ),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = countText,
                color = colors.textTertiary,
                style = FluenceTypography.labelSmall.copy(fontFamily = GeistMonoFont)
            )
        }
        HorizontalDivider(color = colors.divider, thickness = 1.dp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySortBottomSheet(
    selectedOption: SortOption,
    onOptionSelected: (SortOption) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = PrecisionTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.panelElevated,
        contentColor = colors.textPrimary,
        shape = FluenceShapes.Large,
        windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(vertical = FluenceSpacing.Md)
                        .width(FluenceSpacing.Xl)
                        .height(FluenceSpacing.Xs)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.textPrimary.copy(alpha = 0.18f))
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = FluenceSpacing.Base)
        ) {
            Text(
                text = "Sort by",
                color = colors.textPrimary,
                style = FluenceTypography.headlineMedium,
                modifier = Modifier.padding(start = FluenceSpacing.Lg, end = FluenceSpacing.Lg, top = FluenceSpacing.Xs, bottom = FluenceSpacing.Md)
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
                        color = if (option == selectedOption) colors.textPrimary else colors.textSecondary,
                        style = FluenceTypography.bodyLarge.copy(
                            fontWeight = if (option == selectedOption) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    if (option == selectedOption) {
                        Icon(
                            imageVector = FluenceIcons.Check,
                            contentDescription = null,
                            tint = colors.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(FluenceSpacing.Md))
    }
}
