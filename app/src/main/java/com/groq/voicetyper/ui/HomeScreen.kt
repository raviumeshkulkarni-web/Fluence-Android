package com.groq.voicetyper.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import com.groq.voicetyper.FeedbackBus
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.SecurityUtils
import com.groq.voicetyper.history.HistoryRepository
import com.groq.voicetyper.offline.ModelAssetManager
import com.groq.voicetyper.offline.OfflineEngineType
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.offline.v2.MoonshineV2ModelManager
import com.groq.voicetyper.offline.v2.MoonshineV2ModelType
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.sync.stats.DayCounters
import com.groq.voicetyper.theme.*
import com.groq.voicetyper.ui.icons.FluenceIcons
import java.util.Locale

private const val AVG_WPM = 40.0

private fun abbreviate(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

// Windows formatDurationMs parity: rounded to whole minutes — "0m", "45m",
// "1h", "1h 30m". Never the compact "1.5h" form.
private fun formatSavedShort(words: Long): String {
    if (words <= 0) return "0m"
    val totalMinutes = Math.round(words / AVG_WPM).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
    } else {
        "${minutes}m"
    }
}

private fun formatSpokenShort(ms: Long): String {
    val hours = ms / 3_600_000.0
    return if (hours < 1.0) "${Math.round(hours * 60)}m"
    else String.format(Locale.US, "%.1fh", hours)
}

// Windows spokenLabel parity for the Dictation Time foot context:
// "1.5h spoken" / "90m spoken".
private fun formatSpokenFull(ms: Long): String {
    val hours = ms / 3_600_000.0
    return if (hours >= 1.0) String.format(Locale.US, "%.1fh spoken", hours)
    else "${Math.round(hours * 60)}m spoken"
}

private fun formatDictationShort(ms: Long): String {
    if (ms <= 0) return "0m"
    val totalMinutes = Math.round(ms / 60_000.0).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
    } else {
        "${minutes}m"
    }
}

private fun formatSessions(n: Long): String = String.format(Locale.US, "%,d", n)

@Composable
fun HomeScreen(
    onOpenDrawer: () -> Unit,
    showDrawerButton: Boolean = true,
    onNavigateToSettings: () -> Unit = {},
    onOpenDetail: (Long) -> Unit = {},
    onNavigateToSttConfig: () -> Unit = {},
    onNavigateToAgentConfig: () -> Unit = {},
    onNavigateToOfflineConfig: () -> Unit = {},
    onRequestPermission: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
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
    // One-tap theme toggle (top-right). Same `theme_mode` pref MainActivity
    // and Settings observe. Tapping writes explicit light/dark and the whole
    // app follows with no restart.
    val themePrefs = remember {
        context.getSharedPreferences(FluencePrefsName, Context.MODE_PRIVATE)
    }
    var themeMode by remember { mutableStateOf(getThemeMode(themePrefs)) }
    val effectiveDark = resolveDarkTheme(themeMode)
    // Online/offline quick switch. The banner below always shows the active
    // side: provider and model when online, engine when offline. Recording
    // reads the same prefs at record time, so the switch takes effect now.
    var isOffline by remember {
        mutableStateOf(OfflinePreferences.isOfflineModeEnabled(context))
    }

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
        themeMode = getThemeMode(themePrefs)
        isOffline = OfflinePreferences.isOfflineModeEnabled(context)
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
        // Existence check only — never collect the full table here or Home
        // pays the cost of the (now unbounded) history on every keystroke.
        repository.getCount().collect { hasTranscriptions = it > 0 }
    }

    var chartRangeName by rememberSaveable { mutableStateOf(ChartRange.D7.name) }
    val chartRange = ChartRange.valueOf(chartRangeName)
    // Model quick-switcher sheet (local overlay, not a nav destination, so
    // the back stack is untouched). Selection writes the same `stt_model_*`
    // pref SttConfig edits; the banner refreshes underneath on dismiss.
    var showModelSheet by remember { mutableStateOf(false) }
    val activitySeries = remember(unifiedDailyStats, chartRange) {
        buildActivitySeries(unifiedDailyStats, chartRange)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        // Fixed header: hamburger + product lockup never scroll. No surface,
        // divider, or elevation change here, so it reads as one continuous
        // canvas with the content below.
        HomeHeader(
            onOpenDrawer = onOpenDrawer,
            showDrawerButton = showDrawerButton,
            effectiveDark = effectiveDark,
            onToggleTheme = {
                val next = if (effectiveDark) ThemeModeLight else ThemeModeDark
                themeMode = next
                setThemeMode(themePrefs, next)
            }
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val viewportHeight = maxHeight
            val density = LocalDensity.current
            var aboveHeight by remember { mutableStateOf<Dp?>(null) }
            var chromeHeight by remember { mutableStateOf<Dp?>(null) }
            val measuredAbove = aboveHeight
            val measuredChrome = chromeHeight
            // Fill the remaining viewport with the plot, clamped to sane
            // bounds. The card's own vertical padding is subtracted too —
            // without it the card bottom (x-axis labels) always sits 48dp
            // below the fold. Below the minimum the existing scroll takes
            // over; above the maximum (tablets) the card keeps its composure.
            val chartPlotHeight = if (measuredAbove != null && measuredChrome != null) {
                (viewportHeight - measuredAbove - measuredChrome - ActivityChartCardVerticalPadding)
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
            HomeStatusBanner(
                isKeyboardActive = isKeyboardActive,
                sttProvider = sttProvider,
                sttModel = sttModel,
                context = context,
                onOpenKeyboardSettings = {
                    context.startActivity(android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
                onOpenModelSwitcher = { showModelSheet = true }
            )
            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
            // Online/offline quick switch, right below the status bar. The
            // banner above always shows the active side. Offline is guarded
            // the same way as the Offline settings screen: without a
            // downloaded engine model the switch stays put and explains why.
            val modeOptions = remember {
                listOf(
                    SegmentChoice(label = "Online", accessibilityLabel = "Use online transcription"),
                    SegmentChoice(label = "Offline", accessibilityLabel = "Use offline transcription")
                )
            }
            FluenceSegmentedControl(
                options = modeOptions,
                selectedIndex = if (isOffline) 1 else 0,
                onSelect = { index ->
                    if (index == 1) {
                        if (isOfflineEngineReady(context)) {
                            OfflinePreferences.setOfflineModeEnabled(context, true)
                            isOffline = true
                        } else {
                            FeedbackBus.show("Download the offline model first.")
                        }
                    } else {
                        OfflinePreferences.setOfflineModeEnabled(context, false)
                        isOffline = false
                    }
                }
            )
            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

            val allStepsDone = isKeyboardActive && isMicGranted && isApiKeySet && hasTranscriptions
            val reducedMotion = LocalMotionPreferences.current.reducedMotion
            AnimatedVisibility(
                visible = !onboardingDismissed && !allStepsDone,
                enter = if (reducedMotion) EnterTransition.None
                else fadeIn(tween(FluenceMotion.durationStructural)) +
                    expandVertically(tween(FluenceMotion.durationStructural)),
                exit = if (reducedMotion) ExitTransition.None
                else fadeOut(tween(FluenceMotion.durationStructural)) +
                    shrinkVertically(tween(FluenceMotion.durationStructural)),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                    Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
                }
            }

            // Dashboard body: stat cards, then the chart card fills the rest.
            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
                val trend = remember(unifiedDailyStats, chartRange) {
                    trendForRange(unifiedDailyStats, chartRange)
                }
                DashboardHeroStats(
                    series = activitySeries,
                    scopeLabel = when (chartRange) {
                        ChartRange.D7 -> "in last 7 days"
                        ChartRange.D30 -> "in last 30 days"
                        ChartRange.D90 -> "in last 90 days"
                        ChartRange.ALL -> "all time"
                    },
                    trend = trend,
                )
                Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
            }
                // Ledger-gated, never History-gated: synced contributions must
                // not show an empty dashboard (Windows shows the same ledger).
                if (unifiedDailyStats.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.cardSurface, FluenceShapes.Medium)
                            .border(1.dp, colors.cardBorder, FluenceShapes.Medium)
                            .padding(FluenceSpacing.Xl),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            FluenceIcons.Mic,
                            contentDescription = null,
                            tint = colors.textTertiary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
                        Text(
                            "Your dashboard will come alive here",
                            color = colors.textSecondary,
                            style = FluenceTypography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
                        Text(
                            "Start dictating to see your weekly activity",
                            color = colors.textTertiary,
                            style = FluenceTypography.labelSmall
                        )
                    }
                } else {
                    ActivityChartCard(
                        range = chartRange,
                        onRangeChange = { chartRangeName = it.name },
                        series = activitySeries,
                        plotHeight = chartPlotHeight,
                        onChromeHeight = { chromeHeight = it },
                    )
                }
            }
        }
        }
        // Model quick-switcher: local overlay, dismissed before any
        // navigation so the sheet never lingers over the next screen.
        if (showModelSheet) {
            ModelSwitcherSheet(
                activeProvider = sttProvider,
                activeModel = sttModel,
                isOffline = OfflinePreferences.isOfflineModeEnabled(context),
                offlineLabel = offlineModelLabel(context),
                onSelectModel = { provider, model ->
                    SecurityUtils.saveSttPreset(context, provider)
                    SecurityUtils.saveSttModel(context, provider, model)
                    sttProvider = provider
                    sttModel = model
                },
                onOpenDetailed = {
                    showModelSheet = false
                    onNavigateToSttConfig()
                },
                onOpenOfflineConfig = {
                    showModelSheet = false
                    onNavigateToOfflineConfig()
                },
                onDismiss = {
                    showModelSheet = false
                    refreshStatus()
                }
            )
        }
    }
}

@Composable
private fun HomeHeader(
    onOpenDrawer: () -> Unit,
    showDrawerButton: Boolean = true,
    effectiveDark: Boolean,
    onToggleTheme: () -> Unit
) {
    val colors = PrecisionTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showDrawerButton) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.size(48.dp).pressScale(remember { MutableInteractionSource() })
            ) {
                Icon(FluenceIcons.Menu, "Open menu", tint = colors.textSecondary, modifier = Modifier.size(24.dp))
            }
        } else {
            // Permanent sidebar is already visible — keep the balance spacer
            // so the lockup stays centered.
            Spacer(modifier = Modifier.size(48.dp))
        }
        Spacer(modifier = Modifier.weight(1f))
        FluenceProductLockup(productName = "Transcribe", orbSize = 32.dp, wordmarkSize = 22.sp)
        Spacer(modifier = Modifier.weight(1f))
        // One-tap theme toggle: same 48dp touch target as the hamburger so
        // the lockup stays centered. Windows parity: the icon shows the
        // action, not the state. Sun in dark mode, moon in light mode.
        // Tapping pins explicit light/dark.
        val toggleInteraction = remember { MutableInteractionSource() }
        IconButton(
            onClick = onToggleTheme,
            modifier = Modifier.size(48.dp).pressScale(toggleInteraction),
            interactionSource = toggleInteraction
        ) {
            Icon(
                imageVector = if (effectiveDark) Icons.Default.LightMode else Icons.Default.DarkMode,
                contentDescription = if (effectiveDark) "Switch to light mode" else "Switch to dark mode",
                tint = colors.textSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun HomeStatusBanner(
    isKeyboardActive: Boolean,
    sttProvider: String,
    sttModel: String,
    context: Context,
    onOpenKeyboardSettings: () -> Unit,
    onOpenModelSwitcher: () -> Unit
) {
    val colors = PrecisionTheme.colors
    val statusColor = if (isKeyboardActive) colors.success else colors.error
    val statusText = if (isKeyboardActive) "Ready" else "Inactive"
    // Split click targets (was one row opening keyboard settings): the status
    // group still opens keyboard settings, the model label opens the model
    // quick-switcher. Tapping the model no longer hijacks into the IME.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.cardSurface, FluenceShapes.Medium)
            .border(1.dp, colors.cardBorder, FluenceShapes.Medium)
            .padding(horizontal = FluenceSpacing.Md, vertical = FluenceSpacing.Xs)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.clickable(
                    onClickLabel = "Open keyboard settings",
                    role = Role.Button,
                    onClick = onOpenKeyboardSettings
                ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                Text(statusText, color = colors.textPrimary, style = FluenceTypography.labelLarge)
            }
            Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
            Text("·", color = colors.textTertiary, style = FluenceTypography.bodySmall)
            Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
            Text(
                text = if (OfflinePreferences.isOfflineModeEnabled(context)) {
                    "${offlineModelLabel(context)} (Offline)"
                } else {
                    "$sttProvider · $sttModel"
                },
                color = colors.textSecondary,
                style = FluenceTypography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        onClickLabel = "Switch transcription model",
                        role = Role.Button,
                        onClick = onOpenModelSwitcher
                    )
                    .padding(vertical = 8.dp)
            )
        }
    }
}

private fun offlineModelLabel(context: Context): String {
    return OfflinePreferences.getEngineType(context).displayName
}

// Same readiness guard as the Offline settings screen: the quick switch
// must not enable offline mode without a downloaded engine model.
private fun isOfflineEngineReady(context: Context): Boolean {
    return when (OfflinePreferences.getEngineType(context)) {
        OfflineEngineType.SENSEVOICE -> ModelAssetManager.isModelReadySync(context)
        OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING ->
            MoonshineV2ModelManager.isModelReadySync(
                context, MoonshineV2ModelType.SMALL)
        OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING ->
            MoonshineV2ModelManager.isModelReadySync(
                context, MoonshineV2ModelType.MEDIUM)
    }
}

@Composable
private fun DashboardHeroStats(
    series: ActivitySeries,
    scopeLabel: String,
    trend: TrendInfo?,
) {
    val colors = PrecisionTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.cardSurface, FluenceShapes.Medium)
            .border(1.dp, colors.cardBorder, FluenceShapes.Medium)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DashboardStatCell(
                    title = "Words Transcribed",
                    value = abbreviate(series.totalWords),
                    foot = "${formatSessions(series.totalSessions)} sessions · $scopeLabel",
                    trend = trend,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(colors.outlineSubtle)
                )
                DashboardStatCell(
                    title = "Typing Time Saved",
                    value = formatSavedShort(series.totalWords),
                    foot = "at ~40 WPM · $scopeLabel",
                    trend = trend,
                    modifier = Modifier.weight(1f)
                )
            }
            HorizontalDivider(color = colors.outlineSubtle, thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DashboardStatCell(
                    title = "Dictation Time",
                    value = formatDictationShort(series.totalMs),
                    foot = "${formatSpokenFull(series.totalMs)} · $scopeLabel",
                    trend = trend,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(colors.outlineSubtle)
                )
                DashboardStatCell(
                    title = "Sessions",
                    value = formatSessions(series.totalSessions),
                    foot = scopeLabel,
                    trend = trend,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DashboardStatCell(
    title: String,
    value: String,
    foot: String,
    trend: TrendInfo?,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    Column(
        modifier = modifier
            .padding(horizontal = FluenceSpacing.Md, vertical = FluenceSpacing.Base)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
                onLongClickLabel = "Copy value",
                onLongClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Fluence statistic", value))
                    FeedbackBus.show("Copied to clipboard")
                },
            ),
        verticalArrangement = Arrangement.Center
    ) {
        // Windows KPI titles render uppercase via styling; the semantic
        // string stays title-case for accessibility services.
        Text(
            text = title.uppercase(Locale.US),
            color = colors.textSecondary,
            style = FluenceTypography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.56.sp,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
        // Windows parity is no KPI icon — long-press copies the value
        // (touch-natural equivalent of the desktop Copy value menu item).
        Text(
            text = value,
            color = colors.textPrimary,
            style = FluenceTypography.headlineLarge.copy(
                fontFamily = SoraFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                fontFeatureSettings = "tnum",
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (trend != null) {
            Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
            TrendBadge(trend = trend)
        }
        Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
        Text(
            text = foot,
            color = colors.textTertiary,
            style = FluenceTypography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TrendBadge(trend: TrendInfo) {
    val colors = PrecisionTheme.colors
    val container: Color
    val content: Color
    val icon: androidx.compose.ui.graphics.vector.ImageVector
    // Windows container alphas: 15% in dark; white mode follows the light
    // tokens (success 10%, error 8%).
    val light = colors.isLight
    when {
        trend.delta > 0 -> {
            container = colors.success.copy(alpha = if (light) 0.10f else 0.15f)
            content = colors.success
            icon = FluenceIcons.TrendingUp
        }
        trend.delta < 0 -> {
            container = colors.error.copy(alpha = if (light) 0.08f else 0.15f)
            content = colors.error
            icon = FluenceIcons.TrendingDown
        }
        else -> {
            // Windows .badge-secondary: surface-secondary fill (#1E1E1E),
            // on-surface-variant text, and the 1px border token.
            container = colors.panel
            content = colors.textSecondary
            icon = FluenceIcons.Minus
        }
    }
    val sign = if (trend.delta > 0) "+" else if (trend.delta < 0) "-" else "±"
    val magnitude = if (trend.delta != 0L) Math.abs(trend.delta).toString() else "0"
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(container)
            .then(
                if (trend.delta == 0L) {
                    Modifier.border(1.dp, colors.cardBorder, CircleShape)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = FluenceSpacing.Sm, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(FluenceSpacing.Xxs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(12.dp)
        )
        Text(
            text = "$sign$magnitude vs prior ${trend.spanDays}d",
            color = content,
            // Windows .badge uses the label font (Geist Mono) at 11px 500.
            style = FluenceTypography.labelSmall.copy(
                fontFamily = GeistMonoFont,
                letterSpacing = 0.sp,
            ),
            maxLines = 1,
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
    val colors = PrecisionTheme.colors
    val completedCount = listOf(isKeyboardActive, isMicGranted, isApiKeySet, hasTranscriptions).count { it }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.cardSurface, FluenceShapes.Medium)
            .border(1.dp, colors.cardBorder, FluenceShapes.Medium)
            .padding(FluenceSpacing.Md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Welcome to Fluence Transcribe",
                    color = colors.textPrimary,
                    style = FluenceTypography.titleMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$completedCount of 4 setup steps completed",
                    color = colors.textSecondary,
                    style = FluenceTypography.bodySmall
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(FluenceSpacing.Xxl)
            ) {
                Icon(
                    imageVector = FluenceIcons.X,
                    contentDescription = "Dismiss checklist",
                    tint = colors.textTertiary,
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

        HorizontalDivider(color = colors.outlineSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = FluenceSpacing.Xs))

        OnboardingStepRow(
            stepNumber = 2,
            title = "Grant Microphone Permission",
            isDone = isMicGranted,
            actionLabel = "Grant",
            onAction = onRequestPermission
        )

        HorizontalDivider(color = colors.outlineSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = FluenceSpacing.Xs))

        OnboardingStepRow(
            stepNumber = 3,
            title = "Configure STT API Key",
            isDone = isApiKeySet,
            actionLabel = "Configure",
            onAction = onNavigateToSttConfig
        )

        HorizontalDivider(color = colors.outlineSubtle, thickness = 1.dp, modifier = Modifier.padding(vertical = FluenceSpacing.Xs))

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
    val colors = PrecisionTheme.colors
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
                .background(if (isDone) colors.success.copy(alpha = if (colors.isLight) 0.10f else 0.2f) else colors.panelElevated),
            contentAlignment = Alignment.Center
        ) {
            if (isDone) {
                Icon(
                    imageVector = FluenceIcons.Check,
                    contentDescription = "Step $stepNumber completed",
                    tint = colors.success,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    text = stepNumber.toString(),
                    color = colors.textSecondary,
                    style = FluenceTypography.labelSmall
                )
            }
        }

        Spacer(modifier = Modifier.width(FluenceSpacing.Sm))

        Text(
            text = title,
            color = if (isDone) colors.textSecondary else colors.textPrimary,
            style = FluenceTypography.bodyMedium,
            modifier = Modifier.weight(1f)
        )

        if (!isDone && actionLabel != null && onAction != null) {
            // Same outline button language as section headers so setup
            // actions read as buttons. Tokens only.
            val stepActionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .pressScale(stepActionSource)
                    .clip(FluenceShapes.Small)
                    .background(colors.panel)
                    .border(1.dp, colors.outlineSubtle, FluenceShapes.Small)
                    .clickable(
                        interactionSource = stepActionSource,
                        indication = LocalIndication.current,
                        onClickLabel = actionLabel,
                        role = Role.Button,
                        onClick = onAction
                    )
                    .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Xs),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = actionLabel,
                    color = colors.textPrimary,
                    style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}
