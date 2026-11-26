package com.groq.voicetyper.ui

import com.groq.voicetyper.history.StatsCalculator
import com.groq.voicetyper.sync.stats.DayCounters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class KpiTrendsTest {

    private val dayMs = 86_400_000L

    private fun utcTimestamp(year: Int, monthZeroBased: Int, day: Int, hour: Int = 12): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(year, monthZeroBased, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    @Test
    fun kpiTrendsForRange_d7_returnsDistinctDeltasWhen14DaysCovered() {
        val nowMs = utcTimestamp(2026, Calendar.NOVEMBER, 26, hour = 15)
        val todayStart = dayStartUtc(nowMs)

        val daily = mapOf(
            // Prior 7-day window: [today - 13d, today - 7d]
            // Total prior: words=120, count=5, ms=30_000, savedMs=180_000
            StatsCalculator.utcDateOf(todayStart - 13 * dayMs) to
                DayCounters(words = 40L, count = 2L, chars = 200L, ms = 10_000L),
            StatsCalculator.utcDateOf(todayStart - 8 * dayMs) to
                DayCounters(words = 80L, count = 3L, chars = 400L, ms = 20_000L),
            // Current 7-day window: [today - 6d, today]
            // Total current: words=200, count=8, ms=12_000, savedMs=300_000
            StatsCalculator.utcDateOf(todayStart - 2 * dayMs) to
                DayCounters(words = 120L, count = 5L, chars = 600L, ms = 7_000L),
            StatsCalculator.utcDateOf(todayStart) to
                DayCounters(words = 80L, count = 3L, chars = 400L, ms = 5_000L)
        )

        val trends = kpiTrendsForRange(daily, ChartRange.D7, nowMs = nowMs)

        assertNotNull(trends)
        assertEquals(80L, trends!!.wordsDelta)
        assertEquals(120_000L, trends.timeSavedMsDelta)
        assertEquals(-18_000L, trends.durationMsDelta)
        assertEquals(3L, trends.sessionsDelta)
        assertEquals(7, trends.spanDays)
    }

    @Test
    fun kpiTrendsForRange_all_returnsNullEvenWhenCovered() {
        val nowMs = utcTimestamp(2026, Calendar.NOVEMBER, 26)
        val todayStart = dayStartUtc(nowMs)
        val daily = mapOf(
            StatsCalculator.utcDateOf(todayStart - 30 * dayMs) to DayCounters(words = 40L, count = 1L, ms = 5_000L),
            StatsCalculator.utcDateOf(todayStart) to DayCounters(words = 80L, count = 2L, ms = 10_000L)
        )

        assertNull(kpiTrendsForRange(daily, ChartRange.ALL, nowMs = nowMs))
    }

    @Test
    fun kpiTrendsForRange_returnsNullWhenPriorWindowNotFullyCovered() {
        val nowMs = utcTimestamp(2026, Calendar.NOVEMBER, 26)
        val todayStart = dayStartUtc(nowMs)

        // Earliest entry is 12 days ago, one day short of covering the full 14-day span for D7.
        val partialDaily = mapOf(
            StatsCalculator.utcDateOf(todayStart - 12 * dayMs) to DayCounters(words = 40L, count = 1L, ms = 5_000L),
            StatsCalculator.utcDateOf(todayStart) to DayCounters(words = 80L, count = 2L, ms = 10_000L)
        )

        assertNull(kpiTrendsForRange(partialDaily, ChartRange.D7, nowMs = nowMs))
        assertNull(kpiTrendsForRange(emptyMap(), ChartRange.D7, nowMs = nowMs))
    }
}
