package com.groq.voicetyper.ui

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.SecurityUtils
import com.groq.voicetyper.history.HistoryRepository
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.sync.stats.DayCounters
import com.groq.voicetyper.theme.*
import java.util.Locale

private const val AVG_WPM = 40.0

private fun abbreviate(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

private fun formatSavedShort(words: Long): String {
    val hours = words / AVG_WPM / 60.0
    return if (hours < 1.0) "${(hours * 60).toInt()}m"
    else String.format(Locale.US, "%.1fh", hours)
}

private fun formatSpokenShort(ms: Long): String {
    val hours = ms / 3_600_000.0
    return if (hours < 1.0) "${(hours * 60).toInt()}m"
    else String.format(Locale.US, "%.1fh", hours)
}

private fun formatDictationShort(ms: Long): String {
    val totalMinutes = (ms / 60_000.0).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun formatSessions(n: Long): String = String.format(Locale.US, "%,d", n)

@Composable
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onOpenDetail: (Long) -> Unit = {},
    onNavigateToSttConfig: () -> Unit = {},
    onNavigateToAgentConfig: () -> Unit = {},
    onNavigateToOfflineConfig: () -> Unit = {},
    onRequestPermission: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isKeyboardActive by remember { mutableStateOf(false) }
    var isMicGranted by remember { mutableStateOf(false) }
    var sttProvider by remember { mutableStateOf("groq") }
    var sttModel by remember { mutableStateOf("whisper-large-v3") }
    var isApiKeySet by remember { mutableStateOf(false) }
    var onboardingDismissed by remember {
        mutableStateOf(context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE).getBoolean("onboarding_dismissed", false))
    }
    var hasTranscriptions by remember { mutableStateOf(false) }
    val repository = remember { HistoryRepository.init(context); HistoryRepository }
    val lifecycleOwner = LocalLifecycleOwner.current

    fun refreshStatus() {
        val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        isKeyboardActive = imeManager.enabledInputMethodList.any { it.packageName == context.packageName }

        isMicGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        sttProvider = SecurityUtils.getSttPreset(context)
        sttModel = SecurityUtils.getSttModel(context, sttProvider)
        isApiKeySet = sttProvider == "offline" || !SecurityUtils.getProviderApiKey(context, "stt", sttProvider).isNullOrBlank()
    }

    LaunchedEffect(Unit) {
        refreshStatus()
    }

    var unifiedDailyStats by remember { mutableStateOf<Map<String, DayCounters>>(emptyMap()) }
    var syncAccountEmail by remember { mutableStateOf<String?>(null) }

    fun refreshSyncAccount() {
        val auth = com.groq.voicetyper.sync.auth.SyncAuthSession(context)
        syncAccountEmail = if (auth.isSignedIn()) auth.accountEmail else null
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshStatus()
                refreshSyncAccount()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        refreshSyncAccount()
    }

    LaunchedEffect(syncAccountEmail) {
        repository.observeUnifiedStats(syncAccountEmail).collect {
            unifiedDailyStats = it
        }
    }

    LaunchedEffect(Unit) {
        repository.getAll().collect { hasTranscriptions = it.isNotEmpty() }
    }

    var chartRangeName by rememberSaveable { mutableStateOf(ChartRange.D7.name) }
    val chartRange = ChartRange.valueOf(chartRangeName)
    val activitySeries = remember(unifiedDailyStats, chartRange) {
        buildActivitySeries(unifiedDailyStats, chartRange)
    }
    val activitySummary = remember(activitySeries) {
        "${abbreviate(activitySeries.totalWords)} words · " +
            "${formatSavedShort(activitySeries.totalWords)} saved · " +
            "${formatSpokenShort(activitySeries.totalMs)} spoken"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize()
        ) {
            val viewportHeight = maxHeight
            val density = LocalDensity.current
            var aboveHeight by remember { mutableStateOf<Dp?>(null) }
            var chromeHeight by remember { mutableStateOf<Dp?>(null) }
            val measuredAbove = aboveHeight
            val measuredChrome = chromeHeight
            // Fill the remaining viewport with the plot, clamped to sane
            // bounds. Below the minimum the existing scroll takes over;
            // above the maximum (tablets) the card keeps its composure.
            val chartPlotHeight = if (measuredAbove != null && measuredChrome != null) {
                (viewportHeight - measuredAbove - measuredChrome)
                    .coerceIn(ChartPlotMinHeight, ChartPlotMaxHeight)
            } else {
                ChartPlotMinHeight
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = FluenceSpacing.Base)
            ) {
            // Fixed content above the chart card. Measured so the plot can
            // consume exactly the remaining viewport height.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { aboveHeight = with(density) { it.height.toDp() } }
            ) {
            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
            HomeHeader(
                onOpenDrawer = onOpenDrawer
            )
            Spacer(modifier = Modifier.height(FluenceSpacing.Md))
            HomeStatusBanner(
                isKeyboardActive = isKeyboardActive,
                sttProvider = sttProvider,
                sttModel = sttModel,
                context = context
            )
            Spacer(modifier = Modifier.height(FluenceSpacing.Md))

            val allStepsDone = isKeyboardActive && isMicGranted && isApiKeySet && hasTranscriptions
            if (!onboardingDismissed && !allStepsDone) {
                FirstRunOnboardingCard(
                    isKeyboardActive = isKeyboardActive,
                    isMicGranted = isMicGranted,
                    isApiKeySet = isApiKeySet,
                    hasTranscriptions = hasTranscriptions,
                    onRequestPermission = onRequestPermission,
                    onNavigateToSettings = { context.startActivity(android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onNavigateToSttConfig = onNavigateToSttConfig,
                    onDismiss = {
                        context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("onboarding_dismissed", true).apply()
                        onboardingDismissed = true
                    }
                )
                Spacer(modifier = Modifier.height(FluenceSpacing.Md))
            }

            // Dashboard body: stat cards, then the chart card fills the rest.
            Spacer(modifier = Modifier.height(FluenceSpacing.Md))
                DashboardHeroStats(
                    totalWords = abbreviate(activitySeries.totalWords),
                    timeSaved = formatSavedShort(activitySeries.totalWords),
                    dictationTime = formatDictationShort(activitySeries.totalMs),
                    sessions = formatSessions(activitySeries.totalSessions),
                    scopeLabel = when (chartRange) {
                        ChartRange.D7 -> "in last 7 days"
                        ChartRange.D30 -> "in last 30 days"
                        ChartRange.D90 -> "in last 90 days"
                        ChartRange.ALL -> "till today"
                    },
                )
                Spacer(modifier = Modifier.height(FluenceSpacing.Md))
            }
                if (!hasTranscriptions && unifiedDailyStats.isEmpty()) {
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
                    ActivityChartCard(
                        range = chartRange,
                        onRangeChange = { chartRangeName = it.name },
                        series = activitySeries,
                        summaryText = activitySummary,
                        plotHeight = chartPlotHeight,
                        onChromeHeight = { chromeHeight = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(
    onOpenDrawer: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onOpenDrawer,
            modifier = Modifier.size(44.dp).pressScale(remember { MutableInteractionSource() })
        ) {
            Icon(Icons.Default.Menu, "Open menu", tint = TextSecondary, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.weight(1f))
        FluenceProductLockup(productName = "Transcribe", orbSize = 32.dp, wordmarkSize = 20.sp)
        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.size(44.dp))
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
        Text("·", color = TextTertiary, style = FluenceTypography.bodySmall)
        Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
        Text(
            text = if (OfflinePreferences.isOfflineModeEnabled(context)) {
                "${offlineModelLabel(context)} (Offline)"
            } else {
                "$sttProvider · $sttModel"
            },
            color = TextSecondary,
            style = FluenceTypography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun offlineModelLabel(context: Context): String {
    return OfflinePreferences.getEngineType(context).displayName
}

@Composable
private fun DashboardHeroStats(
    totalWords: String,
    timeSaved: String,
    dictationTime: String,
    sessions: String,
    scopeLabel: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
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
                    subtitle = scopeLabel,
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
                    subtitle = scopeLabel,
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
                    subtitle = scopeLabel,
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
                    title = "Sessions",
                    value = sessions,
                    subtitle = scopeLabel,
                    icon = Icons.Default.BarChart,
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
private fun FirstRunOnboardingCard(
    isKeyboardActive: Boolean,
    isMicGranted: Boolean,
    isApiKeySet: Boolean,
    hasTranscriptions: Boolean,
    onRequestPermission: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSttConfig: () -> Unit,
    onDismiss: () -> Unit
) {
    val completedCount = listOf(isKeyboardActive, isMicGranted, isApiKeySet, hasTranscriptions).count { it }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, FluenceShapes.Medium)
            .border(1.dp, OutlineSubtle, FluenceShapes.Medium)
            .padding(FluenceSpacing.Md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Welcome to Fluence Transcribe",
                    color = TextPrimary,
                    style = FluenceTypography.titleMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$completedCount of 4 setup steps completed",
                    color = TextSecondary,
                    style = FluenceTypography.bodySmall
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss checklist",
                    tint = TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

        OnboardingStepRow(
            stepNumber = 1,
            title = "Enable Voice Typing Keyboard",
            isDone = isKeyboardActive,
            actionLabel = "Enable",
            onAction = onNavigateToSettings
        )

        HorizontalDivider(color = OutlineSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = FluenceSpacing.Xs))

        OnboardingStepRow(
            stepNumber = 2,
            title = "Grant Microphone Permission",
            isDone = isMicGranted,
            actionLabel = "Grant",
            onAction = onRequestPermission
        )

        HorizontalDivider(color = OutlineSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = FluenceSpacing.Xs))

        OnboardingStepRow(
            stepNumber = 3,
            title = "Configure STT API Key",
            isDone = isApiKeySet,
            actionLabel = "Configure",
            onAction = onNavigateToSttConfig
        )

        HorizontalDivider(color = OutlineSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = FluenceSpacing.Xs))

        OnboardingStepRow(
            stepNumber = 4,
            title = "Dictate Your First Text",
            isDone = hasTranscriptions,
            actionLabel = null,
            onAction = null
        )
    }
}

@Composable
private fun OnboardingStepRow(
    stepNumber: Int,
    title: String,
    isDone: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FluenceSpacing.Xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (isDone) Success.copy(alpha = 0.2f) else PanelElevated),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Step $stepNumber completed",
                    tint = Success,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    text = stepNumber.toString(),
                    color = TextSecondary,
                    style = FluenceTypography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        Spacer(modifier = Modifier.width(FluenceSpacing.Sm))

        Text(
            text = title,
            color = if (isDone) TextSecondary else TextPrimary,
            style = FluenceTypography.bodyMedium,
            modifier = Modifier.weight(1f)
        )

        if (!isDone && actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = FluenceSpacing.Sm, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 44.dp)
            ) {
                Text(
                    text = actionLabel,
                    color = BrandAmethyst,
                    style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}
