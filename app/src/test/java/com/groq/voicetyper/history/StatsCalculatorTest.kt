package com.groq.voicetyper.history

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class StatsCalculatorTest {

    private fun instantMs(utcString: String): Long = Instant.parse(utcString).toEpochMilli()

    private fun row(text: String, durationMs: Long, timestampMs: Long): TranscriptionEntry =
        TranscriptionEntry(
            text = text,
            provider = "groq",
            durationMs = durationMs,
            isAgentMode = false,
            timestamp = timestampMs
        )

    @Test
    fun word_count_empty_or_whitespace_only_is_zero() {
        assertEquals(0, StatsCalculator.wordCountOf(""))
        assertEquals(0, StatsCalculator.wordCountOf("   "))
        assertEquals(0, StatsCalculator.wordCountOf("\u00A0\u2003\u3000"))
    }

    @Test
    fun word_count_splits_on_ascii_whitespace() {
        assertEquals(1, StatsCalculator.wordCountOf("hello"))
        assertEquals(2, StatsCalculator.wordCountOf("  hello   world  "))
        assertEquals(3, StatsCalculator.wordCountOf("hello\nworld\tagain"))
    }

    @Test
    fun word_count_splits_on_unicode_whitespace() {
        assertEquals(2, StatsCalculator.wordCountOf("caf\u00E9\u00A0menu"))
        assertEquals(2, StatsCalculator.wordCountOf("hello\u2003world"))
        assertEquals(2, StatsCalculator.wordCountOf("hello\u3000world"))
        assertEquals(2, StatsCalculator.wordCountOf("a\u2028b"))
    }

    @Test
    fun utc_date_of_uses_utc_day_boundary() {
        assertEquals("2026-08-14", StatsCalculator.utcDateOf(instantMs("2026-08-14T00:00:00Z")))
        assertEquals("2026-08-14", StatsCalculator.utcDateOf(instantMs("2026-08-14T23:59:59.999Z")))
        assertEquals("2026-08-15", StatsCalculator.utcDateOf(instantMs("2026-08-15T00:00:00Z")))
    }

    @Test
    fun utc_week_start_is_utc_monday_midnight() {
        assertEquals(
            instantMs("2026-08-10T00:00:00Z"),
            StatsCalculator.utcWeekStartMs(instantMs("2026-08-14T12:34:56Z"))
        )
        assertEquals(
            instantMs("2026-08-10T00:00:00Z"),
            StatsCalculator.utcWeekStartMs(instantMs("2026-08-10T00:00:00Z"))
        )
        assertEquals(
            instantMs("2026-08-10T00:00:00Z"),
            StatsCalculator.utcWeekStartMs(instantMs("2026-08-16T23:59:59Z"))
        )
    }

    @Test
    fun utc_month_start_is_first_of_month_utc_midnight() {
        assertEquals(
            instantMs("2026-08-01T00:00:00Z"),
            StatsCalculator.utcMonthStartMs(instantMs("2026-08-14T12:00:00Z"))
        )
        assertEquals(
            instantMs("2026-01-01T00:00:00Z"),
            StatsCalculator.utcMonthStartMs(instantMs("2026-01-31T23:59:59Z"))
        )
    }

    @Test
    fun daily_aggregates_bucket_by_utc_day_and_sum_raw_duration() {
        val rows = listOf(
            row("hello world", 5_000L, instantMs("2026-08-10T01:00:00Z")),
            row("another entry", 2_000L, instantMs("2026-08-10T23:00:00Z")),
            row("next day", 7_000L, instantMs("2026-08-11T01:00:00Z"))
        )
        val stats = StatsCalculator.dailyAggregates(rows)
        assertEquals(2, stats.size)
        assertEquals("2026-08-10", stats[0].day)
        assertEquals(4L, stats[0].wordCount)
        assertEquals(7_000L, stats[0].dictationMs)
        assertEquals("2026-08-11", stats[1].day)
        assertEquals(2L, stats[1].wordCount)
        assertEquals(7_000L, stats[1].dictationMs)
    }

    @Test
    fun daily_aggregates_tracks_count_and_chars() {
        val rows = listOf(
            row("hello world", 5_000L, instantMs("2026-08-10T01:00:00Z")),
            row("another entry", 2_000L, instantMs("2026-08-10T23:00:00Z")),
            row("next day", 7_000L, instantMs("2026-08-11T01:00:00Z"))
        )
        val stats = StatsCalculator.dailyAggregates(rows)
        assertEquals(2, stats.size)
        assertEquals(2L, stats[0].count)
        assertEquals(24L, stats[0].chars)
        assertEquals(1L, stats[1].count)
        assertEquals(8L, stats[1].chars)
    }

    @Test
    fun daily_aggregates_after_delete_excludes_removed_rows() {
        val rowA = row("keep me", 3_000L, instantMs("2026-08-10T01:00:00Z"))
        val rowB = row("delete me now", 9_000L, instantMs("2026-08-10T02:00:00Z"))
        val before = StatsCalculator.dailyAggregates(listOf(rowA, rowB))
        assertEquals(5L, before[0].wordCount)
        assertEquals(12_000L, before[0].dictationMs)
        val after = StatsCalculator.dailyAggregates(listOf(rowA))
        assertEquals(2L, after[0].wordCount)
        assertEquals(3_000L, after[0].dictationMs)
    }

    @Test
    fun daily_aggregates_keeps_zero_and_negative_duration_raw() {
        val stats = StatsCalculator.dailyAggregates(
            listOf(
                row("zero", 0L, instantMs("2026-08-10T01:00:00Z")),
                row("negative", -100L, instantMs("2026-08-10T02:00:00Z"))
            )
        )
        assertEquals(2L, stats[0].wordCount)
        assertEquals(-100L, stats[0].dictationMs)
    }
}
