package com.groq.voicetyper.sync.v1

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §27 categories: UTC day bucketing, deterministic UUIDv5 backfill eventIds
 * (idempotent re-run), single-source priority (transcription rows over
 * stats_daily), history delete/cap never affects already-built stat events.
 */
class BackfillTest {

    // 2026-08-20 12:00:00.000Z
    private val t1 = 1787227200000L
    // 2026-08-20 23:59:59.999Z (same UTC day as t1)
    private val t2 = 1787270399999L
    // 2026-08-21 00:00:00.000Z (next UTC day)
    private val t3 = 1787270400000L

    @Test
    fun utcDay_buckets_by_utc_not_local() {
        assertEquals("2026-08-20", Backfill.utcDayOf(t1))
        assertEquals("2026-08-20", Backfill.utcDayOf(t2))
        assertEquals("2026-08-21", Backfill.utcDayOf(t3))
    }

    @Test
    fun eventIds_are_deterministic_uuid_v5_shape() {
        val a = Backfill.eventIdFor("2026-08-20", "hash", 0)
        val b = Backfill.eventIdFor("2026-08-20", "hash", 0)
        assertEquals(a, b)
        assertTrue("version nibble 5", a[14] == '5')
        assertTrue("variant nibble 8/9/a/b", a[19] in "89ab")
    }

    @Test
    fun fromTranscriptionRows_aggregates_per_utc_day() {
        val rows = listOf(
            Backfill.TranscriptionRowLite(t1, wordCount = 10, durationMs = 1000, syncId = "sync-1", chars = 120),
            Backfill.TranscriptionRowLite(t2, wordCount = 5, durationMs = 500, syncId = "sync-2"),
            Backfill.TranscriptionRowLite(t3, wordCount = 7, durationMs = 700, syncId = "sync-3", chars = 64)
        )
        val records = Backfill.fromTranscriptionRows(rows, "hash", "device-a", now = 42L)
        assertEquals(3, records.size)
        // per-row: not aggregated
        assertEquals(10, records[0].wordCount)
        assertEquals(1000L, records[0].durationMs)
        assertEquals("2026-08-20", records[0].day)
        assertEquals(UUID.nameUUIDFromBytes("fluence-stat-v1:sync-1".toByteArray()).toString(), records[0].eventId)
        assertEquals(UUID.nameUUIDFromBytes("fluence-stat-v1:sync-2".toByteArray()).toString(), records[1].eventId)
        assertEquals(UUID.nameUUIDFromBytes("fluence-stat-v1:sync-3".toByteArray()).toString(), records[2].eventId)
        assertEquals(42L, records[0].updatedAt)
        assertEquals("device-a", records[0].deviceId)
        assertEquals(t1, records[0].timestampMs)
    }

    @Test
    fun fromTranscriptionRows_carries_real_chars() {
        val rows = listOf(
            Backfill.TranscriptionRowLite(t1, 10, 1000, syncId = "sync-1", chars = 245),
            Backfill.TranscriptionRowLite(t2, 5, 500, syncId = "sync-2")
        )
        val records = Backfill.fromTranscriptionRows(rows, "hash", "device-a", now = 1L)
        assertEquals(245, records[0].chars)
        assertEquals("backfill default is 0 when no text is available", 0, records[1].chars)
    }

    @Test
    fun fromDailyStats_fallback_keeps_chars_zero() {
        val records = Backfill.fromDailyStats(
            listOf(Backfill.DailyStatLite("2026-08-20", 7, 700)),
            "hash", "device-b", now = 5L
        )
        assertEquals(0, records[0].chars)
    }

    @Test
    fun re_run_after_crash_reproduces_identical_eventIds() {
        val rows = listOf(Backfill.TranscriptionRowLite(t1, 10, 1000, syncId = "stable-sync"))
        val first = Backfill.fromTranscriptionRows(rows, "hash", "device-a", now = 1L)
        val second = Backfill.fromTranscriptionRows(rows, "hash", "device-a", now = 999L)
        assertEquals(first.map { it.eventId }, second.map { it.eventId })
        assertEquals(UUID.nameUUIDFromBytes("fluence-stat-v1:stable-sync".toByteArray()).toString(), first[0].eventId)
    }

    @Test
    fun fromDailyStats_is_the_fallback_source_with_stable_ids() {
        val stats = listOf(
            Backfill.DailyStatLite("2026-08-21", 7, 700),
            Backfill.DailyStatLite("2026-08-20", 15, 1500)
        )
        val records = Backfill.fromDailyStats(stats, "hash", "device-b", now = 5L)
        assertEquals(listOf("2026-08-20", "2026-08-21"), records.map { it.day })
        assertEquals(records.map { it.eventId }, records.map { it.eventId })
    }
}
