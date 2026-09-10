package com.groq.voicetyper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.FluenceEmptyState
import com.groq.voicetyper.history.HistoryRepository
import com.groq.voicetyper.history.TranscriptionEntry
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.FluenceMotion
import com.groq.voicetyper.theme.LocalMotionPreferences
import com.groq.voicetyper.theme.*
import kotlinx.coroutines.launch
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

private const val PREVIEW_COUNT = 5

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
fun HistoryScreen(
    onOpenDrawer: () -> Unit,
    onOpenDetail: (Long) -> Unit = {},
    onNavigateToSttConfig: () -> Unit = {},
    onRequestPermission: () -> Unit = {},
    modifier: Modifier = Modifier
) {
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
    var selectedIds by rememberSaveable(saver = longSetSaver) { mutableStateOf(setOf<Long>()) }
    val isMultiSelect = selectedIds.isNotEmpty()
    var showClearAllDialog by remember { mutableStateOf(false) }
    var pendingDeleteIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var expandedGroups by rememberSaveable(saver = stringSetSaver) { mutableStateOf(setOf<String>()) }
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

    val displayedEntries = remember(allEntries, searchQuery, currentSortOption) {
        val filtered = if (searchQuery.isBlank()) allEntries
        else allEntries.filter { it.text.contains(searchQuery, ignoreCase = true) }

        when (currentSortOption) {
            SortOption.NEWEST -> filtered.sortedByDescending { it.timestamp }
            SortOption.OLDEST -> filtered.sortedBy { it.timestamp }
            SortOption.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
            SortOption.DURATION_ASC -> filtered.sortedBy { it.durationMs }
        }
    }

    val groupedEntries = remember(displayedEntries, currentSortOption) {
        if (currentSortOption == SortOption.DURATION_DESC || currentSortOption == SortOption.DURATION_ASC) {
            listOf("ALL TRANSCRIPTIONS" to displayedEntries)
        } else {
            groupEntries(displayedEntries, currentSortOption)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: drawer + title + selection actions. Same 44dp rhythm as Home.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = FluenceSpacing.Base),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isMultiSelect) {
                    IconButton(onClick = { selectedIds = emptySet() }, modifier = Modifier.size(44.dp)) {
                        Icon(FluenceIcons.X, "Exit selection", tint = TextPrimary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
                    Text(
                        text = "${selectedIds.size} selected",
                        color = TextPrimary,
                        style = FluenceTypography.titleMedium
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            pendingDeleteIds = selectedIds.toList()
                            showDeleteDialog = true
                        },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(Icons.Default.Delete, "Delete selected", tint = Error, modifier = Modifier.size(18.dp))
                    }
                } else {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier.size(44.dp).pressScale(remember { MutableInteractionSource() })
                    ) {
                        Icon(Icons.Default.Menu, "Open menu", tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
                    Icon(
                        imageVector = FluenceIcons.History,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
                    Text(
                        text = "History",
                        color = TextPrimary,
                        style = FluenceTypography.titleMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = rememberLazyListState()
            ) {
                item(key = "history_search") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = FluenceSpacing.Base)
                    ) {
                        HistorySearchBar(
                            searchQuery = searchQuery,
                            onSearchChange = { searchQuery = it },
                            onSortClick = { showSortSheet = true }
                        )
                        Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
                    }
                }

                if (groupedEntries.isEmpty()) {
                    item {
                        val (emptyActionLabel, emptyAction) = remember(searchQuery, isKeyboardActive, isMicGranted, isApiKeySet) {
                            when {
                                searchQuery.isNotEmpty() -> null to null
                                !isKeyboardActive -> "Enable Keyboard" to {
                                    context.startActivity(android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                }
                                !isMicGranted -> "Grant Microphone Permission" to onRequestPermission
                                !isApiKeySet -> "Configure API Key" to onNavigateToSttConfig
                                else -> null to null
                            }
                        }

                        FluenceEmptyState(
                            icon = if (searchQuery.isNotEmpty()) FluenceIcons.Search else FluenceIcons.Mic,
                            title = if (searchQuery.isNotEmpty()) "No results found" else "No transcriptions yet",
                            description = if (searchQuery.isNotEmpty())
                                "Try a different word, or clear the search to see everything."
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
                    groupedEntries.forEachIndexed { index, (label, entries) ->
                        val isExpanded = label in expandedGroups
                        val hiddenCount = entries.size - PREVIEW_COUNT
                        val showExpandButton = !isExpanded && hiddenCount > 0
                        // Group expand/collapse animates the overflow rows
                        // (fade + vertical expand over the structural tier);
                        // reduced motion toggles them instantly.
                        val rowEnter: EnterTransition = if (reducedMotion) EnterTransition.None
                        else fadeIn(tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing)) +
                            expandVertically(tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing))
                        val rowExit: ExitTransition = if (reducedMotion) ExitTransition.None
                        else fadeOut(tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing)) +
                            shrinkVertically(tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing))

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
                                                contentPadding = PaddingValues(horizontal = FluenceSpacing.Xs, vertical = 0.dp),
                                                modifier = Modifier.heightIn(min = 44.dp)
                                            ) {
                                                Text("Clear History", color = TextTertiary, style = FluenceTypography.labelSmall)
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

                        itemsIndexed(entries, key = { _, entry -> "${label}_${entry.id}" }) { rowIndex, entry ->
                            AnimatedVisibility(
                                visible = rowIndex < PREVIEW_COUNT || isExpanded,
                                enter = rowEnter,
                                exit = rowExit,
                                modifier = Modifier.animateItemPlacement(),
                            ) {
                                HistoryTranscriptRow(
                                    entry = entry,
                                    isSelected = entry.id in selectedIds,
                                    isMultiSelect = isMultiSelect,
                                    onToggleSelect = { selectedIds = if (entry.id in selectedIds) selectedIds - entry.id else selectedIds + entry.id },
                                    onOpenDetail = { onOpenDetail(entry.id) },
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

                        if (entries.size > PREVIEW_COUNT) {
                            item(key = "collapse_$label") {
                                AnimatedVisibility(
                                    visible = isExpanded,
                                    enter = rowEnter,
                                    exit = rowExit,
                                ) {
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
                    }) { Text("Delete", color = ErrorText) }
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
                title = { Text("Clear History") },
                text = { Text("This will permanently delete all ${allEntries.size} transcriptions. This action cannot be undone.") },
                confirmButton = {
                    TextButton(onClick = {
                        coroutineScope.launch { repository.clearAll() }
                        showClearAllDialog = false
                    }) { Text("Clear All", color = ErrorText) }
                },
                dismissButton = {
                    TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel", color = TextSecondary) }
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
    onSortClick: () -> Unit
) {
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchChange,
        placeholder = {
            Text("Search transcriptions…", color = TextSecondary, style = FluenceTypography.bodySmall)
        },
        singleLine = true,
        leadingIcon = {
            Icon(FluenceIcons.Search, "Search", tint = TextSecondary, modifier = Modifier.size(18.dp))
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }, modifier = Modifier.size(36.dp)) {
                        Icon(FluenceIcons.X, "Clear search", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
                IconButton(onClick = onSortClick, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Sort, "Sort", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        },
        shape = FluenceShapes.Medium,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = TextSecondary,
            unfocusedBorderColor = OutlineSubtle,
            focusedContainerColor = Panel,
            unfocusedContainerColor = Panel,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
        ),
        modifier = Modifier.fillMaxWidth().height(48.dp)
    )
}

@Composable
private fun HistoryTranscriptRow(
    entry: TranscriptionEntry,
    isSelected: Boolean,
    isMultiSelect: Boolean,
    onToggleSelect: () -> Unit,
    onOpenDetail: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val foreign = com.groq.voicetyper.sync.SyncAccounts.isForeign(entry.syncAccount)
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
                Text(
                    entry.text,
                    color = if (foreign) TextSecondary else TextPrimary,
                    style = FluenceTypography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTimestamp(entry.timestamp), color = TextTertiary, style = FluenceTypography.labelMedium.copy(fontFamily = GeistMonoFont))
                    if (foreign) {
                        Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
                        Text(
                            "Synced to another account",
                            color = TextTertiary,
                            style = FluenceTypography.labelSmall
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(44.dp)) {
                    Icon(FluenceIcons.MoreHorizontal, "Options", tint = TextTertiary, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("View", color = TextPrimary, style = FluenceTypography.bodySmall) },
                        leadingIcon = { Icon(Icons.Default.Visibility, null, tint = TextSecondary, modifier = Modifier.size(16.dp)) },
                        onClick = { onOpenDetail(); showMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Copy", color = TextPrimary, style = FluenceTypography.bodySmall) },
                        leadingIcon = { Icon(FluenceIcons.Copy, null, tint = TextSecondary, modifier = Modifier.size(16.dp)) },
                        onClick = { onCopy(); showMenu = false }
                    )
                    if (!foreign) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySortBottomSheet(
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
                    .clip(RoundedCornerShape(2.dp))
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
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(FluenceSpacing.Md))
    }
}
