package com.groq.voicetyper.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.history.StatsCalculator
import com.groq.voicetyper.sync.stats.DayCounters
import com.groq.voicetyper.theme.FluenceMotion
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.GeistMonoFont
import com.groq.voicetyper.theme.LocalMotionPreferences
import com.groq.voicetyper.theme.PrecisionTheme
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.format.TextStyle as JavaTextStyle
import java.util.Calendar
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

// ── Shared-scene insight cards (Home) ────────────────────────────────────────
// Windows DashboardPage parity: a weekday-share donut and a "today vs daily
// average" momentum ring, fed by the same observeUnifiedStats ledger map the
// activity chart already consumes. No chart dependency, no new tokens, no
// sync/schema change — same posture as ActivityChart.kt.
//
// Mobile adaptation, not a port of the Windows layout: Windows places these two
// cards side by side under the hero. A phone is one column, so here they stack
// and scroll beneath the activity card, and the donut's legend sits under the
// ring rather than beside it. Nothing is squeezed to fit a width, and the
// existing fill-remaining-viewport plot maths is untouched because these cards
// live below the measured block.
// ────────────────────────────────────────────────────────────────────────────

/** One Monday-first weekday bucket. Order is fixed; never sorted by value. */data class WeekdayPoint(
    val short: String,
    val full: String,
    val sessions: Long,
    val words: Long,
)

// Mirrors the file-private DAY_MS in ActivityChart.kt. That one cannot be
// widened to internal because HistoryScreen.kt declares the same name in this
// package, so the constant is repeated here; the two must stay identical or
// the weekday buckets drift from the activity chart's UTC days.
private const val DAY_MS = 86_400_000L

private val intFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)

private fun formatInt(v: Long): String = intFormat.format(v)

private fun format1(v: Double): String = "%.1f".format(v)

/** Metric value for a weekday bucket — same rule as the activity chart. */
fun weekdayValueOf(point: WeekdayPoint, metric: ChartMetric): Long =
    if (metric == ChartMetric.WORDS) point.words else point.sessions

fun weekdayValueUnit(metric: ChartMetric, value: Long): String =
    if (metric == ChartMetric.WORDS) {
        if (value == 1L) "word" else "words"
    } else {
        if (value == 1L) "session" else "sessions"
    }

/**
 * Folds the UTC-day ledger map into exactly seven Monday-first buckets over the
 * active range. Windows parity: same UTC window, same fixed Mon -> Sun order,
 * O(days) over the map (never O(events)). Reads the raw day map rather than
 * the activity series, because all-time weekly/monthly aggregation there would
 * discard exactly the weekday signal this card is for.
 */
fun buildWeekdaySeries(
    daily: Map<String, DayCounters>,
    range: ChartRange,
    nowMs: Long = System.currentTimeMillis(),
): List<WeekdayPoint> {
    // Names come from DayOfWeek, whose ISO order is already Monday -> Sunday.
    // An earlier version anchored on a hand-picked epoch constant that was one
    // day off, which rotated the whole ring; deriving from the enum makes an
    // off-by-one anchor impossible.
    val labels = DayOfWeek.entries.map { day ->
        day.getDisplayName(JavaTextStyle.SHORT, Locale.US) to
            day.getDisplayName(JavaTextStyle.FULL, Locale.US)
    }
    val sessions = LongArray(7)
    val words = LongArray(7)

    val todayStart = dayStartUtc(nowMs)
    val n = range.days
    val startMs = if (n != null) todayStart - (n - 1) * DAY_MS else 0L
    val endMs = todayStart + DAY_MS

    daily.forEach { (key, counters) ->
        val dayMs = dayMsOfKey(key) ?: return@forEach
        if (dayMs < startMs || dayMs >= endMs) return@forEach
        // Calendar.DAY_OF_WEEK is Sun=1 … Sat=7. Shift to Mon=0 … Sun=6.
        val cal = Calendar.getInstance(UTC)
        cal.timeInMillis = dayMs
        val idx = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        sessions[idx] += counters.count
        words[idx] += counters.words
    }

    return labels.mapIndexed { i, (short, full) ->
        WeekdayPoint(short = short, full = full, sessions = sessions[i], words = words[i])
    }
}

/**
 * Today against the trailing daily average, from the same ledger map.
 *
 * The average covers up to 30 prior UTC days, shortened to the first day the
 * account actually has data for, so a new account reports a true day count
 * instead of being diluted by days that never existed. A null [ratio] means
 * "no baseline yet" and renders as an empty state, never as 0x.
 */
data class TodayRing(
    val todayValue: Long,
    val trailAvg: Double,
    val trailDays: Int,
    val ratio: Double?,
) {
    val sweep: Float get() = ratio?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f
}

fun buildTodayRing(
    daily: Map<String, DayCounters>,
    metric: ChartMetric,
    nowMs: Long = System.currentTimeMillis(),
): TodayRing {
    val todayStart = dayStartUtc(nowMs)
    val today = daily[StatsCalculator.utcDateOf(nowMs)]
    val todayValue =
        if (metric == ChartMetric.WORDS) today?.words ?: 0L else today?.count ?: 0L

    val firstMs = daily.keys.mapNotNull(::dayMsOfKey).minOrNull() ?: todayStart
    val coverageStart = maxOf(todayStart - 30 * DAY_MS, firstMs)
    var trailSum = 0L
    daily.forEach { (key, counters) ->
        val dayMs = dayMsOfKey(key) ?: return@forEach
        if (dayMs < coverageStart || dayMs >= todayStart) return@forEach
        trailSum += if (metric == ChartMetric.WORDS) counters.words else counters.count
    }
    // Integer division: the span is whole UTC days by construction.
    val trailDays = maxOf(1L, (todayStart - coverageStart) / DAY_MS).toInt()
    val trailAvg = trailSum.toDouble() / trailDays
    return TodayRing(
        todayValue = todayValue,
        trailAvg = trailAvg,
        trailDays = trailDays,
        ratio = if (trailAvg > 0.0) todayValue / trailAvg else null,
    )
}

private fun lerpColor(from: Color, to: Color, t: Float): Color {
    val c = t.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * c,
        green = from.green + (to.green - from.green) * c,
        blue = from.blue + (to.blue - from.blue) * c,
        alpha = from.alpha + (to.alpha - from.alpha) * c,
    )
}

/**
 * Interpolates the chart duo (start -> mid -> end) into n slice fills so the
 * donut speaks the activity chart's visual language.
 */
internal fun chartDuoRamp(start: Color, mid: Color, end: Color, n: Int): List<Color> =
    List(n) { i ->
        val t = if (n < 2) 0f else i.toFloat() / (n - 1)
        if (t < 0.5f) lerpColor(start, mid, t * 2f) else lerpColor(mid, end, (t - 0.5f) * 2f)
    }

private fun weekdayPeakLine(
    points: List<WeekdayPoint>,
    metric: ChartMetric,
    range: ChartRange,
): String {
    val total = points.sumOf { weekdayValueOf(it, metric) }
    if (total <= 0L) return "No activity in this range yet"
    val peak = points.maxByOrNull { weekdayValueOf(it, metric) } ?: return ""
    val value = weekdayValueOf(peak, metric)
    val share = Math.round(value * 100.0 / total)
    return "Peak ${peak.full} $share% · ${formatInt(value)} " +
        "${weekdayValueUnit(metric, value)} · ${range.rangeName}"
}

/** Card chrome identical to ActivityChartCard so the dashboard reads as one set. */
@Composable
private fun InsightCard(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PrecisionTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.cardSurface, FluenceShapes.Medium)
            .border(1.dp, colors.cardBorder, FluenceShapes.Medium)
            .padding(FluenceSpacing.Lg),
    ) {
        // Windows CardTitle treatment. The source string stays semantic; only
        // the rendered text is uppercased so accessibility keeps the real word.
        Text(
            text = title.uppercase(Locale.US),
            color = colors.textSecondary,
            style = FluenceTypography.labelLarge.copy(
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.56.sp,
            ),
        )
        Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
        Text(
            text = description,
            color = colors.textTertiary,
            style = FluenceTypography.bodySmall,
        )
        Spacer(modifier = Modifier.height(FluenceSpacing.Lg))
        content()
    }
}

@Composable
private fun InsightEmptyState() {
    val colors = PrecisionTheme.colors
    Text(
        text = "No activity in this range yet",
        color = colors.textPrimary,
        style = FluenceTypography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
    Text(
        text = "Start dictating to see it here.",
        color = colors.textSecondary,
        style = FluenceTypography.bodySmall,
    )
}

private const val DonutStrokeDp = 22
private const val DonutGrowDp = 6
private const val RingStrokeDp = 12

/**
 * Weekday share donut. Tap or drag a slice to expand it and read its exact
 * value in the centre; the legend below carries every value as real text, so
 * the chart is never the only way to read the data.
 */
@Composable
fun WeekdayDonutCard(
    series: List<WeekdayPoint>,
    metric: ChartMetric,
    range: ChartRange,
    modifier: Modifier = Modifier,
) {
    val colors = PrecisionTheme.colors
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val total = remember(series, metric) { series.sumOf { weekdayValueOf(it, metric) } }
    val totalNoun = weekdayValueUnit(metric, total)
    val metricNoun = if (metric == ChartMetric.WORDS) "Words share by weekday" else "Sessions share by weekday"

    InsightCard(
        title = "By weekday",
        description = "$metricNoun · ${weekdayPeakLine(series, metric, range)}",
        modifier = modifier,
    ) {
        if (total <= 0L) {
            InsightEmptyState()
            return@InsightCard
        }

        var selected by remember(series, metric) { mutableStateOf(-1) }
        val fills = remember(colors, series.size) {
            chartDuoRamp(colors.chartDuoStart, colors.chartDuoMid, colors.chartDuoEnd, series.size)
        }
        val reducedMotion = LocalMotionPreferences.current.reducedMotion
        val reveal = remember(series, metric) { Animatable(0f) }
        LaunchedEffect(series, metric) {
            if (reducedMotion) {
                reveal.snapTo(1f)
            } else {
                // Staggered behind the hero chart on purpose: both drawing at
                // once in 225ms reads as a blink. The hero leads, the donut
                // follows 80ms later over 300ms, so a range switch reads as a
                // sequence instead of a flash. Same easing family, same
                // structural tier language — only pacing differs.
                reveal.animateTo(
                    1f,
                    tween(
                        durationMillis = FluenceMotion.durationStructural + 75,
                        delayMillis = 80,
                        easing = FastOutSlowInEasing,
                    ),
                )
            }
        }

        val trackStrokePx = with(density) { DonutStrokeDp.dp.toPx() }
        val growPx = with(density) { DonutGrowDp.dp.toPx() }

        fun indexAt(x: Float, y: Float, w: Float, h: Float): Int {
            val radius = min(w, h) / 2f - trackStrokePx / 2f
            val cx = w / 2f
            val cy = h / 2f
            val dist = hypot(x - cx, y - cy)
            // Generous inner dead zone so a centre tap clears instead of
            // grabbing whichever slice happens to start at 12 o'clock.
            if (dist < radius * 0.45f || dist > radius + trackStrokePx) return -1
            // Compose angles start at 3 o'clock; the ring starts at 12.
            var deg = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat() + 90f
            if (deg < 0f) deg += 360f
            var acc = 0f
            series.forEachIndexed { i, point ->
                val sweep = weekdayValueOf(point, metric) * 360f / total
                if (sweep > 0f && deg >= acc && deg < acc + sweep) return i
                acc += sweep
            }
            return -1
        }

        val focus = series.getOrNull(selected)
        val focusValue = focus?.let { weekdayValueOf(it, metric) } ?: 0L
        val focusShare = if (focus != null) Math.round(focusValue * 100.0 / total) else 0
        // Short summary only. The legend below is the per-day detail, so this
        // must not restate all seven values or a screen reader hears them twice.
        val chartDescription = if (focus != null) {
            "$metricNoun. ${focus.full}: ${formatInt(focusValue)} " +
                "${weekdayValueUnit(metric, focusValue)}, " +
                "$focusShare% of ${range.rangeName}"
        } else {
            "$metricNoun. Total ${formatInt(total)} $totalNoun, peak " +
                "${series.maxByOrNull { weekdayValueOf(it, metric) }?.full}. Tap a day for detail."
        }
        val valueStyle = TextStyle(
            fontFamily = GeistMonoFont,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        val labelStyle = TextStyle(
            fontFamily = GeistMonoFont,
            fontSize = 11.sp,
            color = colors.textSecondary,
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                // Same rule as the activity chart: taps select, drags turn the
                // page. Slice scrubbing on drag would fight the pager for the
                // same gesture, so it is deliberately not here; tapping a slice
                // or a legend row selects it instead.
                .pointerInput(series, total) {
                    detectTapGestures { offset ->
                        val w = size.width.toFloat()
                        selected = indexAt(offset.x, offset.y, w, size.height.toFloat())
                    }
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = chartDescription
                    role = Role.Image
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            val revealNow = reveal.value
            val diameter = min(size.width, size.height) - trackStrokePx
            var startAngle = -90f
            series.forEachIndexed { i, point ->
                val value = weekdayValueOf(point, metric)
                if (value > 0L) {
                    val sweep = (value * 360f / total) * revealNow
                    if (sweep > 0.5f) {
                        val active = i == selected
                        // Outward-only growth (Windows parity): radius and
                        // stroke grow together so the inner edge never moves.
                        val extra = if (active) growPx else 0f
                        val d = diameter + extra * 2f
                        drawArc(
                            color = fills.getOrElse(i) { colors.chartDuoMid },
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = Offset((size.width - d) / 2f, (size.height - d) / 2f),
                            size = Size(d, d),
                            style = Stroke(width = trackStrokePx + extra, cap = StrokeCap.Butt),
                        )
                    }
                }
                startAngle += value * 360f / total
            }

            // Centre readout, drawn on the canvas so it is announced once via
            // the single contentDescription above rather than twice.
            val centre = Offset(size.width / 2f, size.height / 2f)
            if (focus != null) {
                val nameLayout = textMeasurer.measure(focus.full, labelStyle)
                val valueLayout = textMeasurer.measure(formatInt(focusValue), valueStyle)
                val shareLayout = textMeasurer.measure("$focusShare%", labelStyle)
                drawText(
                    nameLayout,
                    topLeft = Offset(
                        centre.x - nameLayout.size.width / 2f,
                        centre.y - valueLayout.size.height / 2f - nameLayout.size.height,
                    ),
                )
                drawText(
                    valueLayout,
                    topLeft = Offset(
                        centre.x - valueLayout.size.width / 2f,
                        centre.y - valueLayout.size.height / 2f,
                    ),
                )
                drawText(
                    shareLayout,
                    topLeft = Offset(
                        centre.x - shareLayout.size.width / 2f,
                        centre.y + valueLayout.size.height / 2f,
                    ),
                )
            } else {
                val totalLayout = textMeasurer.measure(formatInt(total), valueStyle)
                val nounLayout = textMeasurer.measure("$totalNoun total", labelStyle)
                drawText(
                    totalLayout,
                    topLeft = Offset(
                        centre.x - totalLayout.size.width / 2f,
                        centre.y - totalLayout.size.height / 2f - nounLayout.size.height / 2f,
                    ),
                )
                drawText(
                    nounLayout,
                    topLeft = Offset(
                        centre.x - nounLayout.size.width / 2f,
                        centre.y + totalLayout.size.height / 2f - nounLayout.size.height / 2f,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(FluenceSpacing.Md))
        WeekdayLegend(
            series = series,
            metric = metric,
            fills = fills,
            total = total,
            selected = selected,
            onSelect = { selected = if (it == selected) -1 else it },
        )
    }
}

/**
 * The donut's readable twin: every day's exact value and share as real text,
 * each row a 44dp tap target that expands its slice. This is what makes the
 * chart informative rather than decorative, and it is the accessible path —
 * nothing here is colour-only.
 */
@Composable
private fun WeekdayLegend(
    series: List<WeekdayPoint>,
    metric: ChartMetric,
    fills: List<Color>,
    total: Long,
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = PrecisionTheme.colors
    Column(modifier = Modifier.fillMaxWidth()) {
        series.forEachIndexed { i, point ->
            val value = weekdayValueOf(point, metric)
            val share = if (total > 0L) Math.round(value * 100.0 / total) else 0
            val active = i == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .background(
                        if (active) colors.panelElevated else Color.Transparent,
                        FluenceShapes.ExtraSmall,
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = FluenceSpacing.Sm)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${point.full}, ${formatInt(value)} " +
                            "${weekdayValueUnit(metric, value)}, $share percent"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(fills.getOrElse(i) { colors.chartDuoMid }, CircleShape),
                )
                Spacer(modifier = Modifier.width(FluenceSpacing.Md))
                Text(
                    text = point.short,
                    color = colors.textSecondary,
                    style = FluenceTypography.bodySmall,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatInt(value),
                    color = colors.textPrimary,
                    style = FluenceTypography.bodySmall.copy(fontFamily = GeistMonoFont),
                )
                Spacer(modifier = Modifier.width(FluenceSpacing.Base))
                Text(
                    text = "$share%",
                    color = colors.textSecondary,
                    style = FluenceTypography.bodySmall.copy(fontFamily = GeistMonoFont),
                )
            }
        }
    }
}

/**
 * Today against the trailing daily average. A static arc with no animation, so
 * reduced motion is satisfied by construction rather than by a guard — the
 * same reasoning as the Windows ring.
 */
@Composable
fun TodayRingCard(
    ring: TodayRing,
    metric: ChartMetric,
    modifier: Modifier = Modifier,
) {
    val colors = PrecisionTheme.colors
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val metricNoun =
        if (metric == ChartMetric.WORDS) "Words vs daily average" else "Sessions vs daily average"
    val ratio = ring.ratio
    // Units follow the value: a 6-session day must read "6 sessions today",
    // not the singular hardcoded above.
    val todayNoun = weekdayValueUnit(metric, ring.todayValue)
    val avgNoun = weekdayValueUnit(metric, Math.round(ring.trailAvg).toLong())

    InsightCard(
        title = "Today",
        description = metricNoun + " · " + if (ratio == null) {
            "No baseline yet"
        } else {
            "${format1(ratio)}× your daily average"
        },
        modifier = modifier,
    ) {
        if (ratio == null) {
            InsightEmptyState()
            return@InsightCard
        }

        val strokePx = with(density) { RingStrokeDp.dp.toPx() }
        // The visible caption below is the detail; this is the summary, so the
        // two must not read identically or a screen user hears it twice.
        val chartDescription = "Today versus your daily average. ${format1(ratio)} times, " +
            "${formatInt(ring.todayValue)} $todayNoun against a ${format1(ring.trailAvg)} $avgNoun average."
        val valueStyle = TextStyle(
            fontFamily = GeistMonoFont,
            fontSize = 25.sp,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )
        val labelStyle = TextStyle(
            fontFamily = GeistMonoFont,
            fontSize = 11.sp,
            color = colors.textSecondary,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(
                modifier = Modifier
                    .size(128.dp)
                    .semantics(mergeDescendants = true) {
                        contentDescription = chartDescription
                        role = Role.Image
                    },
            ) {
                val d = min(size.width, size.height) - strokePx
                val tl = Offset((size.width - d) / 2f, (size.height - d) / 2f)
                val arcSize = Size(d, d)
                val centre = Offset(size.width / 2f, size.height / 2f)

                drawArc(
                    color = if (colors.isLight) Color.Black.copy(alpha = 0.08f)
                    else colors.textPrimary.copy(alpha = 0.08f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = tl,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Butt),
                )
                val sweep = ring.sweep * 360f
                if (sweep > 0.5f) {
                    drawArc(
                        color = colors.chartDuoMid,
                        startAngle = -90f,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = tl,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }

                val valueLayout = textMeasurer.measure("${format1(ratio)}×", valueStyle)
                val labelLayout = textMeasurer.measure("of avg", labelStyle)
                drawText(
                    valueLayout,
                    topLeft = Offset(
                        centre.x - valueLayout.size.width / 2f,
                        centre.y - valueLayout.size.height / 2f - labelLayout.size.height / 2f,
                    ),
                )
                drawText(
                    labelLayout,
                    topLeft = Offset(
                        centre.x - labelLayout.size.width / 2f,
                        centre.y + valueLayout.size.height / 2f - labelLayout.size.height / 2f,
                    ),
                )
            }
            Spacer(modifier = Modifier.width(FluenceSpacing.Base))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "${formatInt(ring.todayValue)} $todayNoun today",
                    color = colors.textPrimary,
                    style = FluenceTypography.bodySmall.copy(fontFamily = GeistMonoFont),
                )
                Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
                Text(
                    text = "${format1(ring.trailAvg)} $avgNoun avg",
                    color = colors.textSecondary,
                    style = FluenceTypography.bodySmall.copy(fontFamily = GeistMonoFont),
                )
                Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
                Text(
                    text = "last ${ring.trailDays}d",
                    color = colors.textTertiary,
                    style = FluenceTypography.labelSmall,
                )
            }
        }
    }
}
