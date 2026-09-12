package com.groq.voicetyper.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.history.StatsCalculator
import com.groq.voicetyper.sync.stats.DayCounters
import com.groq.voicetyper.theme.BrandCyan
import com.groq.voicetyper.theme.CardBorder
import com.groq.voicetyper.theme.CardSurface
import com.groq.voicetyper.theme.ChartDuoEnd
import com.groq.voicetyper.theme.ChartDuoStart
import com.groq.voicetyper.theme.ChartDuoEnd
import com.groq.voicetyper.theme.ChartDuoMid
import com.groq.voicetyper.theme.ChartDuoStart
import com.groq.voicetyper.theme.DialogSurface
import com.groq.voicetyper.theme.FluenceMotion
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.GeistMonoFont
import com.groq.voicetyper.theme.LocalMotionPreferences
import com.groq.voicetyper.theme.OutlineSubtle
import com.groq.voicetyper.theme.TextPrimary
import com.groq.voicetyper.theme.TextSecondary
import com.groq.voicetyper.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.roundToInt

// ── Fluence activity chart (Home) ────────────────────────────────────────────
// Windows-parity line chart (sessions metric, trailing windows, Monday-week
// and calendar-month ALL aggregation) drawn as a small Fluence-specific
// Canvas primitive — no chart dependency. Consumes the existing
// observeUnifiedStats map only; aggregation below is UI-layer math.
// ────────────────────────────────────────────────────────────────────────────

enum class ChartRange(val tabLabel: String, val days: Int?) {
    D7("Last 7 days", 7),
    D30("Last 30 days", 30),
    D90("Last 90 days", 90),
    ALL("All time", null);

    val accessibilityLabel: String
        get() = when (this) {
            D7 -> "Show last 7 days"
            D30 -> "Show last 30 days"
            D90 -> "Show last 90 days"
            ALL -> "Show all time"
        }

    val rangeName: String
        get() = when (this) {
            D7 -> "last 7 days"
            D30 -> "last 30 days"
            D90 -> "last 90 days"
            ALL -> "all time"
        }
}

data class ActivityPoint(
    val label: String,
    val fullLabel: String,
    val count: Int,
    val words: Int,
)

// Chart metric: the area reads the sessions trend, bars read discrete words
// per bucket. One toggle switches both type and metric together (Windows
// parity) — never a type-times-metric matrix. Words come from the same
// synced ledger counters as sessions; no transcript sync involved.
enum class ChartMetric(val tabLabel: String) {
    SESSIONS("Sessions"),
    WORDS("Words"),
}

data class ActivitySeries(
    val range: ChartRange,
    val points: List<ActivityPoint>,
    val totalSessions: Long,
    val totalWords: Long,
    val totalMs: Long,
)

/**
 * Prior-period momentum for the active range (Windows TrendBadge parity).
 * Null for ALL and for uncovered prior windows — never a fabricated delta.
 */
data class TrendInfo(val delta: Long, val spanDays: Int)

fun trendForRange(
    daily: Map<String, DayCounters>,
    range: ChartRange,
    nowMs: Long = System.currentTimeMillis(),
): TrendInfo? {
    val span = when (range) {
        ChartRange.D7 -> 7
        ChartRange.D30 -> 30
        ChartRange.D90 -> 90
        ChartRange.ALL -> return null
    }
    val todayStart = dayStartUtc(nowMs)
    // The prior window must be fully covered, or the delta would mislead.
    val firstMs = daily.keys.mapNotNull(::dayMsOfKey).minOrNull() ?: return null
    if (firstMs > todayStart - (2 * span - 1) * DAY_MS) return null
    fun sumWindow(startMs: Long, endMs: Long): Long {
        var total = 0L
        var dayMs = startMs
        while (dayMs < endMs) {
            total += daily[StatsCalculator.utcDateOf(dayMs)]?.count ?: 0L
            dayMs += DAY_MS
        }
        return total
    }
    val current = sumWindow(todayStart - (span - 1) * DAY_MS, todayStart + DAY_MS)
    val prior = sumWindow(todayStart - (2 * span - 1) * DAY_MS, todayStart - (span - 1) * DAY_MS)
    return TrendInfo(current - prior, span)
}

private const val DAY_MS = 86_400_000L
private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

private fun dayStartUtc(nowMs: Long): Long = nowMs - (nowMs % DAY_MS)

private fun dayMsOfKey(key: String): Long? = try {
    val cal = Calendar.getInstance(UTC)
    cal.set(
        key.substring(0, 4).toInt(),
        key.substring(5, 7).toInt() - 1,
        key.substring(8, 10).toInt(),
        0, 0, 0,
    )
    cal.set(Calendar.MILLISECOND, 0)
    cal.timeInMillis
} catch (_: Exception) {
    null
}

private fun mondayOf(dayStartMs: Long): Long {
    val cal = Calendar.getInstance(UTC)
    cal.timeInMillis = dayStartMs
    val shiftDays = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon=0 … Sun=6
    return dayStartMs - shiftDays * DAY_MS
}

/**
 * Integer Y ticks for a session-count axis: 0 plus nice round lines. When the
 * nice max is divisible by 4 we add quarter lines, so e.g. 100 yields
 * 0/25/50/75/100 — the ~5-line grid Windows produces with allowDecimals=false.
 */
fun niceSessionTicks(rawMax: Int): List<Int> {
    if (rawMax <= 0) return listOf(0)
    val exp = Math.pow(10.0, Math.floor(Math.log10(rawMax.toDouble()))).toInt()
    val frac = rawMax.toDouble() / exp
    val niceFrac = when {
        frac <= 1 -> 1
        frac <= 2 -> 2
        frac <= 5 -> 5
        else -> 10
    }
    val nice = niceFrac * exp
    val ticks = mutableListOf(0)
    if (nice % 4 == 0) {
        ticks.add(nice / 4)
        ticks.add(nice / 2)
        ticks.add(3 * nice / 4)
    } else if (nice % 2 == 0) {
        ticks.add(nice / 2)
    }
    ticks.add(nice)
    return ticks.sorted().distinct()
}

/**
 * Builds the plottable series for [range] from the full UTC-day ledger map.
 * Pure UI-layer transform — no backend, database, or aggregation changes.
 */
fun buildActivitySeries(
    daily: Map<String, DayCounters>,
    range: ChartRange,
    nowMs: Long = System.currentTimeMillis(),
): ActivitySeries {
    val weekdayFmt = SimpleDateFormat("EEE", Locale.US).apply { timeZone = UTC }
    // Windows daily/weekly axis order is month-then-day ("Sep 9"), months are
    // "Sep 2026". Locale stays US-pinned (existing Android behavior) rather
    // than following the system locale.
    val dayMonthFmt = SimpleDateFormat("MMM d", Locale.US).apply { timeZone = UTC }
    val dayMonthYearFmt = SimpleDateFormat("MMM d yyyy", Locale.US).apply { timeZone = UTC }
    val monthAxisFmt = SimpleDateFormat("MMM yyyy", Locale.US).apply { timeZone = UTC }
    val monthFullFmt = SimpleDateFormat("MMM yyyy", Locale.US).apply { timeZone = UTC }
    val todayStart = dayStartUtc(nowMs)
    val currentYear = Calendar.getInstance(UTC).apply { timeInMillis = nowMs }.get(Calendar.YEAR)

    fun mondayChip(mondayMs: Long): String {
        val label = dayMonthFmt.format(java.util.Date(mondayMs))
        val year = Calendar.getInstance(UTC).apply { timeInMillis = mondayMs }.get(Calendar.YEAR)
        return if (year == currentYear) label else dayMonthYearFmt.format(java.util.Date(mondayMs))
    }

    if (range.days != null) {
        // Trailing-N-days window ending today, zero-filled (Windows parity).
        val n = range.days
        var sessions = 0L
        var wordsTotal = 0L
        var ms = 0L
        val points = (0 until n).map { i ->
            val dayMs = todayStart - (n - 1 - i) * DAY_MS
            val counters = daily[StatsCalculator.utcDateOf(dayMs)]
            val count = counters?.count?.toInt() ?: 0
            val words = counters?.words?.toInt() ?: 0
            sessions += count
            wordsTotal += words
            ms += counters?.ms ?: 0L
            val label = if (range == ChartRange.D7) {
                weekdayFmt.format(java.util.Date(dayMs))
            } else {
                dayMonthFmt.format(java.util.Date(dayMs))
            }
            ActivityPoint(label = label, fullLabel = label, count = count, words = words)
        }
        return ActivitySeries(range, points, sessions, wordsTotal, ms)
    }

    // ALL: adaptive resolution mirroring Windows (daily ≤92d, Monday weeks
    // ≤730d, calendar months beyond). Present buckets only, ascending.
    val dayEntries = daily.mapNotNull { (key, counters) ->
        val dayMs = dayMsOfKey(key) ?: return@mapNotNull null
        Triple(dayMs, key, counters)
    }.sortedBy { it.first }
    if (dayEntries.isEmpty()) {
        return ActivitySeries(range, emptyList(), 0L, 0L, 0L)
    }
    val spanDays = ((todayStart - dayEntries.first().first) / DAY_MS + 1).toInt().coerceAtLeast(1)
    val sessions = dayEntries.sumOf { it.third.count }
    val words = dayEntries.sumOf { it.third.words }
    val ms = dayEntries.sumOf { it.third.ms }

    val points = when {
        spanDays <= 92 -> {
            (0 until spanDays).map { i ->
                val dayMs = todayStart - (spanDays - 1 - i) * DAY_MS
                val counters = daily[StatsCalculator.utcDateOf(dayMs)]
                val label = dayMonthFmt.format(java.util.Date(dayMs))
                ActivityPoint(
                    label = label,
                    fullLabel = label,
                    count = counters?.count?.toInt() ?: 0,
                    words = counters?.words?.toInt() ?: 0,
                )
            }
        }
        spanDays <= 730 -> {
            val weeks = java.util.TreeMap<Long, Pair<Long, Long>>()
            dayEntries.forEach { (dayMs, _, counters) ->
                val monday = mondayOf(dayMs)
                val prev = weeks[monday] ?: (0L to 0L)
                weeks[monday] = (prev.first + counters.count) to (prev.second + counters.words)
            }
            weeks.map { (monday, totals) ->
                ActivityPoint(
                    label = dayMonthFmt.format(java.util.Date(monday)),
                    fullLabel = mondayChip(monday),
                    count = totals.first.toInt(),
                    words = totals.second.toInt(),
                )
            }
        }
        else -> {
            val months = java.util.TreeMap<String, Pair<Long, Long>>()
            dayEntries.forEach { (_, key, counters) ->
                val monthKey = key.substring(0, 7)
                val prev = months[monthKey] ?: (0L to 0L)
                months[monthKey] = (prev.first + counters.count) to (prev.second + counters.words)
            }
            months.map { (monthKey, totals) ->
                val cal = Calendar.getInstance(UTC)
                cal.set(monthKey.substring(0, 4).toInt(), monthKey.substring(5, 7).toInt() - 1, 1, 0, 0, 0)
                ActivityPoint(
                    label = monthAxisFmt.format(cal.time),
                    fullLabel = monthFullFmt.format(cal.time),
                    count = totals.first.toInt(),
                    words = totals.second.toInt(),
                )
            }
        }
    }
    return ActivitySeries(range, points, sessions, words, ms)
}

/** Fritsch–Carlson monotone cubic — same curve family as the Windows chart. */
private fun monotonePath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size < 3) {
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        return path
    }
    val n = points.size
    val slope = FloatArray(n - 1) { i ->
        val dx = points[i + 1].x - points[i].x
        if (dx == 0f) 0f else (points[i + 1].y - points[i].y) / dx
    }
    val m = FloatArray(n)
    m[0] = slope[0]
    m[n - 1] = slope[n - 2]
    for (i in 1 until n - 1) {
        m[i] = if (slope[i - 1] == 0f || slope[i] == 0f ||
            (slope[i - 1] < 0f) != (slope[i] < 0f)
        ) {
            0f
        } else {
            (slope[i - 1] + slope[i]) / 2f
        }
    }
    for (i in 0 until n - 1) {
        if (slope[i] == 0f) {
            m[i] = 0f
            m[i + 1] = 0f
        } else {
            val a = m[i] / slope[i]
            val b = m[i + 1] / slope[i]
            val h = Math.hypot(a.toDouble(), b.toDouble())
            if (h > 9.0) {
                val t = 3.0 / h
                m[i] = (t * a * slope[i]).toFloat()
                m[i + 1] = (t * b * slope[i]).toFloat()
            }
        }
    }
    for (i in 0 until n - 1) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val dx = p1.x - p0.x
        path.cubicTo(
            p0.x + dx / 3f, p0.y + m[i] * dx / 3f,
            p1.x - dx / 3f, p1.y - m[i + 1] * dx / 3f,
            p1.x, p1.y,
        )
    }
    return path
}

/**
 * Plot-height bounds for the fill-remaining Home layout. The plot never
 * shrinks below the approved compact size (small screens scroll instead)
 * and never grows past the cap (tablets keep breathing room below).
 */
val ChartPlotMinHeight = 224.dp
val ChartPlotMaxHeight = 360.dp
// The card pads itself top + bottom with Lg; Home must subtract this from
// the plot budget or the card bottom (x-axis labels) lands below the fold.
val ActivityChartCardVerticalPadding = FluenceSpacing.Lg * 2
private val PlotLabelHeight = 24.dp
private val PlotPadEnd = 10.dp
private val PlotPadTop = 8.dp
// Minimum horizontal room per X label — Windows thins ticks by pixel gap
// (minTickGap), not by fixed stride; this is the density-aware equivalent.
private val MinLabelGap = 48.dp

@Composable
fun ActivityRangeSelector(
    selected: ChartRange,
    onSelect: (ChartRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Shared segmented control (same implementation as the History date
    // filter) — range mapping only, no visual fork.
    val options = remember {
        ChartRange.entries.map { SegmentChoice(it.tabLabel, it.accessibilityLabel) }
    }
    FluenceSegmentedControl(
        options = options,
        selectedIndex = selected.ordinal,
        onSelect = { onSelect(ChartRange.entries[it]) },
        modifier = modifier,
    )
}

@Composable
fun ActivityMetricSelector(
    selected: ChartMetric,
    onSelect: (ChartMetric) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Same control as the range selector — metric mapping only, no visual fork.
    val options = remember {
        ChartMetric.entries.map { SegmentChoice(it.tabLabel, "Show ${it.tabLabel.lowercase()}") }
    }
    FluenceSegmentedControl(
        options = options,
        selectedIndex = selected.ordinal,
        onSelect = { onSelect(ChartMetric.entries[it]) },
        modifier = modifier,
    )
}

@Composable
fun ActivityChartCard(
    range: ChartRange,
    onRangeChange: (ChartRange) -> Unit,
    series: ActivitySeries,
    modifier: Modifier = Modifier,
    plotHeight: Dp = ChartPlotMinHeight,
    onChromeHeight: (Dp) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(CardSurface, FluenceShapes.Medium)
            .border(1.dp, CardBorder, FluenceShapes.Medium)
            .padding(FluenceSpacing.Lg),
    ) {
        val context = LocalContext.current
        // Metric choice persists across restarts (Windows localStorage parity).
        val prefs = remember {
            context.getSharedPreferences("fluence_prefs", android.content.Context.MODE_PRIVATE)
        }
        var metricName by remember {
            mutableStateOf(
                prefs.getString("chart_metric", ChartMetric.SESSIONS.name)
                    ?: ChartMetric.SESSIONS.name
            )
        }
        val metric = ChartMetric.valueOf(metricName)
        val density = LocalDensity.current
        // Card chrome above the plot (header + selector). Measured so Home can
        // size the plot to fill the remaining viewport. Heights here never
        // depend on the plot, so this converges after layout without looping.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { onChromeHeight(with(density) { it.height.toDp() }) },
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Windows CardTitle treatment: 14px semibold uppercase secondary.
            // The source string stays semantic ("Activity"); only the rendered
            // text is uppercased so accessibility services keep the real word.
            Text(
                text = "Activity".uppercase(Locale.US),
                color = TextSecondary,
                style = FluenceTypography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.56.sp,
                ),
            )
        }
        Spacer(modifier = Modifier.height(FluenceSpacing.Md))
        ActivityRangeSelector(
            selected = range,
            onSelect = onRangeChange,
        )
        Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
        ActivityMetricSelector(
            selected = metric,
            onSelect = {
                prefs.edit().putString("chart_metric", it.name).apply()
                metricName = it.name
            },
        )
        Spacer(modifier = Modifier.height(FluenceSpacing.Lg))
        }
        if (series.totalSessions == 0L) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = FluenceSpacing.Lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "No activity in this range yet",
                    color = TextPrimary,
                    style = FluenceTypography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
                Text(
                    text = "Start dictating to see it here.",
                    color = TextSecondary,
                    style = FluenceTypography.bodySmall,
                )
            }
        } else {
            // Range switching crossfades the old plot into the new one over
            // the structural tier (Material fade-through). Reduced motion
            // renders the new plot directly — no slide, no fade.
            val reducedMotion = LocalMotionPreferences.current.reducedMotion
            if (reducedMotion) {
                FluenceActivityChart(
                    series = series,
                    range = range,
                    metric = metric,
                    modifier = Modifier.fillMaxWidth(),
                    plotHeight = plotHeight,
                )
            } else {
                Crossfade(
                    targetState = series,
                    animationSpec = tween(
                        durationMillis = FluenceMotion.durationStructural,
                        easing = FastOutSlowInEasing
                    ),
                    label = "range_switch",
                ) { fading ->
                    FluenceActivityChart(
                        series = fading,
                        range = fading.range,
                        metric = metric,
                        modifier = Modifier.fillMaxWidth(),
                        plotHeight = plotHeight,
                    )
                }
            }
        }
    }
}

@Composable
fun FluenceActivityChart(
    series: ActivitySeries,
    range: ChartRange,
    metric: ChartMetric = ChartMetric.SESSIONS,
    modifier: Modifier = Modifier,
    plotHeight: Dp = ChartPlotMinHeight,
) {
    val points = series.points
    if (points.isEmpty()) return
    fun valueOf(p: ActivityPoint): Int = if (metric == ChartMetric.WORDS) p.words else p.count
    val noun = if (metric == ChartMetric.WORDS) "word" else "session"
    val reducedMotion = LocalMotionPreferences.current.reducedMotion
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    var selectedIndex by remember(series) { mutableStateOf(-1) }

    // Reused native paint/path so the tooltip chip can cast the Windows
    // shadow-md soft drop shadow without allocating per frame.
    val chipShadowPaint = remember { android.graphics.Paint() }
    val chipShadowPath = remember { android.graphics.Path() }

    // Draw-on replay: every new series draws its line 0→1 over the
    // structural tier while the crossfade blends the containers. Animatable (not
    // animateFloatAsState) is required — it starts at 0 by construction,
    // whereas animateFloatAsState starts at its target and never travels
    // on a fresh composition. Reduced motion snaps to the finished line.
    val revealAnim = remember(series) { Animatable(0f) }
    LaunchedEffect(series) {
        if (reducedMotion) revealAnim.snapTo(1f)
        else revealAnim.animateTo(
            1f,
            tween(durationMillis = FluenceMotion.durationStructural, easing = FastOutSlowInEasing)
        )
    }

    var canvasWidthPx by remember { mutableFloatStateOf(0f) }

    val labelStyle = remember {
        TextStyle(
            fontFamily = GeistMonoFont,
            fontSize = 12.sp,
            color = TextSecondary,
        )
    }
    val maxCount = remember(points, metric) { points.maxOf { valueOf(it) }.coerceAtLeast(1) }
    val ticks = remember(maxCount) { niceSessionTicks(maxCount) }
    val niceMax = remember(ticks) { ticks.last().coerceAtLeast(1) }

    // Precompute text layouts for Y-axis ticks and X-axis points so text is not measured per animation frame
    val tickLayouts = remember(ticks, labelStyle, textMeasurer) {
        ticks.map { tick -> tick to textMeasurer.measure(tick.toString(), labelStyle) }
    }
    val pointLayouts = remember(points, labelStyle, textMeasurer) {
        points.map { point -> textMeasurer.measure(point.label, labelStyle) }
    }

    // Dynamic Y gutter: base 32dp grows to fit the widest tick label so
    // four-digit counts never clip (Windows widens its Y gutter 32→44px when
    // labels reach four digits). Shared by the draw pass and nearestIndex so
    // touch mapping stays aligned with the drawn plot origin. Words view adds
    // half a bar width: bars center on the plot edge, so without it the
    // leftmost bar hangs over the tick labels.
    val yGutterPx = remember(tickLayouts, density, metric) {
        val labels = tickLayouts.maxOfOrNull { (_, layout) ->
            layout.size.width
        }?.plus(with(density) { 14.dp.toPx() })?.coerceAtLeast(with(density) { 32.dp.toPx() })
            ?: with(density) { 32.dp.toPx() }
        labels + if (metric == ChartMetric.WORDS) with(density) { 16.dp.toPx() } else 0f
    }

    fun nearestIndex(x: Float): Int {
        if (canvasWidthPx <= 0f) return -1
        val plotLeft = yGutterPx
        val plotRight = canvasWidthPx - with(density) { PlotPadEnd.toPx() }
        val gap = if (points.size < 2) 0f else (plotRight - plotLeft) / (points.size - 1)
        if (gap <= 0f) return 0
        return ((x - plotLeft) / gap).roundToInt().coerceIn(0, points.size - 1)
    }

    val peak = points.maxByOrNull { valueOf(it) }
    val total = if (metric == ChartMetric.WORDS) series.totalWords else series.totalSessions
    val unit = if (metric == ChartMetric.WORDS) "words" else "sessions"
    var description = "Activity, ${range.rangeName}: " +
        "$total $unit" +
        (if (peak != null && valueOf(peak) > 0) ", peak ${valueOf(peak)} $unit on ${peak.fullLabel}" else "")
    val sel = selectedIndex
    if (sel in points.indices) {
        val p = points[sel]
        description += "; selected ${p.fullLabel}: ${valueOf(p)} $unit"
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(plotHeight + PlotLabelHeight)
            .onSizeChanged { canvasWidthPx = it.width.toFloat() }
            .pointerInput(series, canvasWidthPx) {
                detectTapGestures(onTap = { offset ->
                    val index = nearestIndex(offset.x)
                    if (index >= 0) selectedIndex = index
                })
            }
            .pointerInput(series, canvasWidthPx) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val index = nearestIndex(offset.x)
                        if (index >= 0) selectedIndex = index
                    },
                    onHorizontalDrag = { change, _ ->
                        val index = nearestIndex(change.position.x)
                        if (index >= 0) selectedIndex = index
                        change.consume()
                    },
                    onDragEnd = {},
                    onDragCancel = {},
                )
            }
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Image
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        val reveal = revealAnim.value
        val n = points.size
        val plotLeft = yGutterPx
        val plotRight = size.width - PlotPadEnd.toPx()
        val plotTop = PlotPadTop.toPx()
        val plotBottom = size.height - PlotLabelHeight.toPx()
        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)

        // Restrained horizontal grid + integer Y labels.
        tickLayouts.forEach { (tick, layout) ->
            val y = plotBottom - (tick.toFloat() / niceMax) * plotH
            drawLine(
                color = TextPrimary.copy(alpha = 0.06f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
            )
            drawText(
                layout,
                topLeft = Offset(
                    plotLeft - 6.dp.toPx() - layout.size.width,
                    y - layout.size.height / 2f,
                ),
            )
        }

        fun xAt(i: Int): Float =
            if (n < 2) plotLeft + plotW / 2f else plotLeft + (i.toFloat() / (n - 1)) * plotW

        fun yAt(value: Int): Float = plotBottom - (value.toFloat() / niceMax) * plotH

        // X labels, Windows preserveEnd parity: the oldest and newest labels
        // are always drawn, and intermediates step no closer than MinLabelGap
        // (~recharts minTickGap) — no more forced-last crowding. When the
        // second-to-last stride label would violate the gap, it is dropped so
        // the newest date survives cleanly.
        val pointGapPx = if (n < 2) 0f else plotW / (n - 1)
        val minGapPx = with(density) { MinLabelGap.toPx() }
        val step = if (pointGapPx <= 0f) 1 else maxOf(1, ceil(minGapPx / pointGapPx).toInt())
        val labelIdx: List<Int> = if (n < 2) {
            listOf(0)
        } else {
            val idx = ArrayList<Int>(8)
            idx.add(0)
            var i = step
            while (i < n - 1) {
                idx.add(i)
                i += step
            }
            val penultimate: Int = idx[idx.size - 1]
            if (penultimate != 0 && (n - 1) - penultimate < step) {
                idx.removeAt(idx.size - 1)
            }
            idx.add(n - 1)
            idx
        }
        labelIdx.forEach { i ->
            val layout = pointLayouts.getOrNull(i) ?: textMeasurer.measure(points[i].label, labelStyle)
            drawText(
                layout,
                topLeft = Offset(
                    (xAt(i) - layout.size.width / 2f).coerceIn(plotLeft - layout.size.width / 2f, plotRight - layout.size.width / 2f),
                    plotBottom + 6.dp.toPx(),
                ),
            )
        }

        val pts = points.mapIndexed { i, p -> Offset(xAt(i), yAt(valueOf(p))) }
        // Words read as discrete bars (Windows parity); sessions keep the
        // monotone area. Bars fade in on the same reveal — no grow motion.
        fun drawBar(i: Int, value: Int) {
            if (value <= 0) return
            val gap = if (n < 2) 28.dp.toPx() else plotW / (n - 1)
            val barW = (gap * 0.6f).coerceAtMost(28.dp.toPx())
            val cx = xAt(i)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0f to ChartDuoStart.copy(alpha = 0.9f),
                    1f to ChartDuoEnd.copy(alpha = 0.9f),
                    startY = yAt(value),
                    endY = plotBottom,
                ),
                topLeft = Offset(cx - barW / 2f, yAt(value)),
                size = Size(barW, (plotBottom - yAt(value)).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(3.dp.toPx()),
                alpha = reveal,
            )
        }
        if (n == 1) {
            if (metric == ChartMetric.WORDS) drawBar(0, valueOf(points[0]))
            else drawCircle(
                color = BrandCyan,
                radius = 4.dp.toPx(),
                center = pts[0],
                alpha = reveal,
            )
        } else if (metric == ChartMetric.WORDS) {
            points.forEachIndexed { i, p -> drawBar(i, valueOf(p)) }
        } else if (reveal > 0f) {
            val full = monotonePath(pts)
            val measure = PathMeasure()
            measure.setPath(full, false)
            val dist = measure.length * reveal
            val seg = Path()
            measure.getSegment(0f, dist, seg, true)
            val end = measure.getPosition(dist)
            val fill = Path().apply {
                addPath(seg)
                lineTo(end.x, plotBottom)
                lineTo(plotLeft, plotBottom)
                close()
            }
            drawPath(
                fill,
                brush = Brush.verticalGradient(
                    0f to ChartDuoStart.copy(alpha = 0.22f),
                    0.5f to ChartDuoMid.copy(alpha = 0.10f),
                    1f to ChartDuoEnd.copy(alpha = 0.03f),
                    startY = plotTop,
                    endY = plotBottom,
                ),
                alpha = reveal,
            )
            drawPath(
                seg,
                brush = Brush.horizontalGradient(
                    0f to ChartDuoStart,
                    0.55f to ChartDuoMid,
                    1f to ChartDuoEnd,
                ),
                style = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }

        // Touch selection: crosshair + dot + clamped value chip.
        if (sel in points.indices) {
            val point = points[sel]
            val sx = xAt(sel)
            val sy = yAt(valueOf(point))
            drawLine(
                color = TextPrimary.copy(alpha = 0.12f),
                start = Offset(sx, plotTop),
                end = Offset(sx, plotBottom),
                strokeWidth = 1.dp.toPx(),
            )
            drawCircle(
                color = BrandCyan.copy(alpha = 0.25f),
                radius = 7.dp.toPx(),
                center = Offset(sx, sy),
            )
            drawCircle(
                color = BrandCyan,
                radius = 4.dp.toPx(),
                center = Offset(sx, sy),
            )
            val chipText = buildAnnotatedString {
                val v = valueOf(point)
                withStyle(SpanStyle(color = TextPrimary)) {
                    append(v.toString())
                    append(if (v == 1) " $noun" else " ${noun}s")
                }
                withStyle(SpanStyle(color = TextTertiary)) {
                    append(" · " + point.fullLabel)
                }
            }
            val chipStyle = TextStyle(fontFamily = GeistMonoFont, fontSize = 11.sp)
            val chipLayout = textMeasurer.measure(chipText, chipStyle)
            val chipW = chipLayout.size.width + 20.dp.toPx()
            val chipH = 30.dp.toPx()
            var chipX = sx - chipW / 2f
            chipX = if (chipW >= plotW) {
                plotLeft
            } else {
                chipX.coerceIn(plotLeft, plotRight - chipW)
            }
            var chipY = sy - 7.dp.toPx() - 8.dp.toPx() - chipH
            if (chipY < plotTop - 4.dp.toPx()) {
                chipY = sy + 7.dp.toPx() + 8.dp.toPx()
            }
            // Windows .chart-tooltip box-shadow: --shadow-md (0 4px 24px
            // rgba(0,0,0,.45), 0 1px 4px rgba(0,0,0,.25)), cast with a single
            // soft shadow layer under the chip body.
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val radius = 8.dp.toPx()
                chipShadowPath.reset()
                chipShadowPath.addRoundRect(
                    android.graphics.RectF(chipX, chipY, chipX + chipW, chipY + chipH),
                    radius,
                    radius,
                    android.graphics.Path.Direction.CW,
                )
                chipShadowPaint.apply {
                    isAntiAlias = true
                    setShadowLayer(12.dp.toPx(), 0f, 4.dp.toPx(), Color.Black.copy(alpha = 0.45f).toArgb())
                    color = DialogSurface.toArgb()
                }
                native.drawPath(chipShadowPath, chipShadowPaint)
                chipShadowPaint.setShadowLayer(0f, 0f, 0f, 0)
            }
            drawRoundRect(
                color = DialogSurface,
                topLeft = Offset(chipX, chipY),
                size = Size(chipW, chipH),
                cornerRadius = CornerRadius(8.dp.toPx()),
            )
            drawRoundRect(
                color = OutlineSubtle,
                topLeft = Offset(chipX, chipY),
                size = Size(chipW, chipH),
                cornerRadius = CornerRadius(8.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
            drawText(
                chipLayout,
                topLeft = Offset(
                    chipX + 10.dp.toPx(),
                    chipY + (chipH - chipLayout.size.height) / 2f,
                ),
            )
        }
    }
}
