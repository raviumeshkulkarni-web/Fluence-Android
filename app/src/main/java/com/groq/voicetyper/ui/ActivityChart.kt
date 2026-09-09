package com.groq.voicetyper.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.sync.stats.DayCounters
import com.groq.voicetyper.theme.BrandAmethyst
import com.groq.voicetyper.theme.BrandCyan
import com.groq.voicetyper.theme.DialogSurface
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.GeistMonoFont
import com.groq.voicetyper.theme.LocalMotionPreferences
import com.groq.voicetyper.theme.OutlineSubtle
import com.groq.voicetyper.theme.Panel
import com.groq.voicetyper.theme.PanelElevated
import com.groq.voicetyper.theme.TextPrimary
import com.groq.voicetyper.theme.TextSecondary
import com.groq.voicetyper.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

// ── Fluence activity chart (Home) ────────────────────────────────────────────
// Windows-parity line chart (sessions metric, trailing windows, Monday-week
// and calendar-month ALL aggregation) drawn as a small Fluence-specific
// Canvas primitive — no chart dependency. Consumes the existing
// observeUnifiedStats map only; aggregation below is UI-layer math.
// ────────────────────────────────────────────────────────────────────────────

enum class ChartRange(val tabLabel: String, val days: Int?) {
    D7("7D", 7),
    D30("30D", 30),
    D90("90D", 90),
    ALL("ALL", null);

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
)

data class ActivitySeries(
    val range: ChartRange,
    val points: List<ActivityPoint>,
    val totalSessions: Long,
    val totalWords: Long,
    val totalMs: Long,
)

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

/** Integer Y ticks for a session-count axis: 0 plus 1–2 nice round lines. */
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
    if (nice % 2 == 0) ticks.add(nice / 2)
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
    val dayMonthFmt = SimpleDateFormat("d MMM", Locale.US).apply { timeZone = UTC }
    val dayMonthYearFmt = SimpleDateFormat("d MMM yyyy", Locale.US).apply { timeZone = UTC }
    val monthAxisFmt = SimpleDateFormat("MMM yy", Locale.US).apply { timeZone = UTC }
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
        var words = 0L
        var ms = 0L
        val points = (0 until n).map { i ->
            val dayMs = todayStart - (n - 1 - i) * DAY_MS
            val counters = daily[StatsCalculator.utcDateOf(dayMs)]
            val count = counters?.count?.toInt() ?: 0
            sessions += count
            words += counters?.words ?: 0L
            ms += counters?.ms ?: 0L
            val label = if (range == ChartRange.D7) {
                weekdayFmt.format(java.util.Date(dayMs))
            } else {
                dayMonthFmt.format(java.util.Date(dayMs))
            }
            ActivityPoint(label = label, fullLabel = label, count = count)
        }
        return ActivitySeries(range, points, sessions, words, ms)
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
                val count = daily[StatsCalculator.utcDateOf(dayMs)]?.count?.toInt() ?: 0
                val label = dayMonthFmt.format(java.util.Date(dayMs))
                ActivityPoint(label = label, fullLabel = label, count = count)
            }
        }
        spanDays <= 730 -> {
            val weeks = java.util.TreeMap<Long, Long>()
            dayEntries.forEach { (dayMs, _, counters) ->
                val monday = mondayOf(dayMs)
                weeks[monday] = (weeks[monday] ?: 0L) + counters.count
            }
            weeks.map { (monday, count) ->
                ActivityPoint(
                    label = dayMonthFmt.format(java.util.Date(monday)),
                    fullLabel = mondayChip(monday),
                    count = count.toInt(),
                )
            }
        }
        else -> {
            val months = java.util.TreeMap<String, Long>()
            dayEntries.forEach { (_, key, counters) ->
                val monthKey = key.substring(0, 7)
                months[monthKey] = (months[monthKey] ?: 0L) + counters.count
            }
            months.map { (monthKey, count) ->
                val cal = Calendar.getInstance(UTC)
                cal.set(monthKey.substring(0, 4).toInt(), monthKey.substring(5, 7).toInt() - 1, 1, 0, 0, 0)
                ActivityPoint(
                    label = monthAxisFmt.format(cal.time),
                    fullLabel = monthFullFmt.format(cal.time),
                    count = count.toInt(),
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
private val PlotLabelHeight = 24.dp
private val YGutter = 30.dp
private val PlotPadEnd = 10.dp
private val PlotPadTop = 8.dp

@Composable
fun ActivityRangeSelector(
    selected: ChartRange,
    onSelect: (ChartRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .border(1.dp, OutlineSubtle, FluenceShapes.Small)
            .clip(FluenceShapes.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChartRange.entries.forEach { entry ->
            val isSelected = entry == selected
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isSelected) PanelElevated else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        onClick = { onSelect(entry) },
                        role = Role.Tab,
                        interactionSource = interactionSource,
                        indication = null,
                    )
                    .pressScale(interactionSource)
                    .semantics { contentDescription = entry.accessibilityLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.tabLabel,
                    color = if (isSelected) BrandAmethyst else TextTertiary,
                    style = FluenceTypography.labelSmall.copy(
                        fontFamily = GeistMonoFont,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

@Composable
fun ActivityChartCard(
    range: ChartRange,
    onRangeChange: (ChartRange) -> Unit,
    series: ActivitySeries,
    summaryText: String,
    modifier: Modifier = Modifier,
    plotHeight: Dp = ChartPlotMinHeight,
    onChromeHeight: (Dp) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Panel, FluenceShapes.Medium)
            .border(1.dp, OutlineSubtle, FluenceShapes.Medium)
            .padding(FluenceSpacing.Lg),
    ) {
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
            Text(
                text = "ACTIVITY",
                color = TextTertiary,
                style = FluenceTypography.labelSmall.copy(
                    fontFamily = GeistMonoFont,
                    letterSpacing = 0.8.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Text(
                text = summaryText,
                color = TextTertiary,
                style = FluenceTypography.labelSmall.copy(
                    fontFamily = GeistMonoFont,
                    fontSize = 10.sp,
                ),
            )
        }
        Spacer(modifier = Modifier.height(FluenceSpacing.Md))
        ActivityRangeSelector(
            selected = range,
            onSelect = onRangeChange,
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
                    text = "No activity in this range",
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
            FluenceActivityChart(
                series = series,
                range = range,
                modifier = Modifier.fillMaxWidth(),
                plotHeight = plotHeight,
            )
        }
    }
}

@Composable
fun FluenceActivityChart(
    series: ActivitySeries,
    range: ChartRange,
    modifier: Modifier = Modifier,
    plotHeight: Dp = ChartPlotMinHeight,
) {
    val points = series.points
    if (points.isEmpty()) return
    val reducedMotion = LocalMotionPreferences.current.reducedMotion
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    var selectedIndex by remember(series) { mutableStateOf(-1) }

    // Initial reveal draws the line on (~350ms); later data changes snap.
    // Range switching crossfades separately below (~225ms).
    var revealArmed by remember { mutableStateOf(true) }
    val hasPoints = points.isNotEmpty()
    val revealSpec = if (reducedMotion || !revealArmed) {
        snap<Float>()
    } else {
        tween<Float>(durationMillis = 350, easing = FastOutSlowInEasing)
    }
    val reveal by animateFloatAsState(
        targetValue = if (hasPoints) 1f else 0f,
        animationSpec = revealSpec,
        label = "chart_reveal",
    )
    LaunchedEffect(hasPoints) {
        if (hasPoints) revealArmed = false
    }

    var switchVisible by remember(series.range) { mutableStateOf(reducedMotion) }
    LaunchedEffect(series.range) { switchVisible = true }
    val switchAlpha by animateFloatAsState(
        targetValue = if (switchVisible) 1f else 0f,
        animationSpec = if (reducedMotion) {
            snap()
        } else {
            tween(durationMillis = 225, easing = FastOutSlowInEasing)
        },
        label = "range_switch",
    )

    var canvasWidthPx by remember { mutableFloatStateOf(0f) }
    fun nearestIndex(x: Float): Int {
        if (canvasWidthPx <= 0f) return -1
        val plotLeft = with(density) { YGutter.toPx() }
        val plotRight = canvasWidthPx - with(density) { PlotPadEnd.toPx() }
        val gap = if (points.size < 2) 0f else (plotRight - plotLeft) / (points.size - 1)
        if (gap <= 0f) return 0
        return ((x - plotLeft) / gap).roundToInt().coerceIn(0, points.size - 1)
    }

    val peak = points.maxByOrNull { it.count }
    var description = "Activity, ${range.rangeName}: " +
        "${series.totalSessions} transcriptions" +
        (if (peak != null && peak.count > 0) ", peak ${peak.count} on ${peak.fullLabel}" else "")
    val sel = selectedIndex
    if (sel in points.indices) {
        val p = points[sel]
        description += "; selected ${p.fullLabel}: ${p.count} sessions"
    }

    val labelStyle = TextStyle(
        fontFamily = GeistMonoFont,
        fontSize = 10.sp,
        color = TextTertiary,
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(plotHeight + PlotLabelHeight)
            .onSizeChanged { canvasWidthPx = it.width.toFloat() }
            .alpha(switchAlpha)
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
        val n = points.size
        val plotLeft = YGutter.toPx()
        val plotRight = size.width - PlotPadEnd.toPx()
        val plotTop = PlotPadTop.toPx()
        val plotBottom = size.height - PlotLabelHeight.toPx()
        val plotW = (plotRight - plotLeft).coerceAtLeast(1f)
        val plotH = (plotBottom - plotTop).coerceAtLeast(1f)
        val maxCount = points.maxOf { it.count }.coerceAtLeast(1)
        val ticks = niceSessionTicks(maxCount)
        val niceMax = ticks.last().coerceAtLeast(1)

        // Restrained horizontal grid + integer Y labels.
        ticks.forEach { tick ->
            val y = plotBottom - (tick.toFloat() / niceMax) * plotH
            drawLine(
                color = Color.White.copy(alpha = 0.06f),
                start = Offset(plotLeft, y),
                end = Offset(plotRight, y),
                strokeWidth = 1.dp.toPx(),
            )
            val layout = textMeasurer.measure(tick.toString(), labelStyle)
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

        fun yAt(count: Int): Float = plotBottom - (count.toFloat() / niceMax) * plotH

        // X labels: stride keeps ~6 ticks at any density, always incl. last.
        val stride = maxOf(1, (n - 1) / 5)
        points.forEachIndexed { i, point ->
            if (i % stride == 0 || i == n - 1) {
                val layout = textMeasurer.measure(point.label, labelStyle)
                drawText(
                    layout,
                    topLeft = Offset(
                        (xAt(i) - layout.size.width / 2f).coerceIn(plotLeft - layout.size.width / 2f, plotRight - layout.size.width / 2f),
                        plotBottom + 6.dp.toPx(),
                    ),
                )
            }
        }

        val pts = points.mapIndexed { i, p -> Offset(xAt(i), yAt(p.count)) }
        if (n == 1) {
            drawCircle(
                color = BrandCyan,
                radius = 4.dp.toPx(),
                center = pts[0],
                alpha = reveal,
            )
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
                    0f to BrandCyan.copy(alpha = 0.45f),
                    0.5f to BrandAmethyst.copy(alpha = 0.30f),
                    1f to BrandAmethyst.copy(alpha = 0.14f),
                    startY = plotTop,
                    endY = plotBottom,
                ),
                alpha = reveal,
            )
            drawPath(
                seg,
                color = BrandCyan,
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
            val sy = yAt(point.count)
            drawLine(
                color = Color.White.copy(alpha = 0.12f),
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
                withStyle(SpanStyle(color = TextPrimary)) {
                    append(point.count.toString())
                    append(if (point.count == 1) " session" else " sessions")
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
