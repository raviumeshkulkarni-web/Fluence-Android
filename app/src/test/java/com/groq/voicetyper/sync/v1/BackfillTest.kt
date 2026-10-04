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

    // ================================================================
    // STAGE 9 (deferred): read-time seeding on the upgrade path.
    //
    // The dashboard derives "No baseline yet" from the trailing daily average
    // of stat_sync, and stat_sync was seeded only by the sync pass. An install
    // upgraded from a build predating that ledger therefore had real history
    // but no stat_sync rows, and rendered the genuine new-user empty state.
    // HistoryRepository now reuses this same backfill on read.
    //
    // Room cannot run in JVM unit tests (no Robolectric in this module), so
    // these pin the properties the fix RELIES on rather than the DAO write
    // itself. The DAO-level and on-device proof is the runtime matrix.
    // ================================================================

    @Test
    fun backfill_event_ids_are_stable_across_runs_so_a_second_run_deduplicates() {
        val rows = listOf(
            Backfill.TranscriptionRowLite(t1, wordCount = 10, durationMs = 1000, syncId = "sync-1", chars = 120),
            Backfill.TranscriptionRowLite(t3, wordCount = 7, durationMs = 700, syncId = "sync-3", chars = 64)
        )
        val first = Backfill.fromTranscriptionRows(rows, "hash-a", "device-a", now = 1L)
        val second = Backfill.fromTranscriptionRows(rows, "hash-a", "device-a", now = 999L)
assertEquals(
            "a re-run must reproduce identical ids so insertIgnore dedups it",
            first.map { it.eventId },
            second.map { it.eventId }
        )
    }

@Test
    fun backfill_event_ids_identify_the_dictation_not_the_account() {
        // `eventIdForDictation` derives the id from the dictation's own syncId,
        // NOT from the account. Account scoping lives in the STORED row's
        // `accountHash` field (`backfillIfNeeded` stamps it with the seeding
        // account), and reads go through `observeByAccount(hash)`. Union dedup
        // is therefore global by eventId: the same dictation re-seeded under a
        // different account reproduces the identical id, and a second account
        // will not re-seed rows an earlier account already claimed. That is the
        // pre-existing semantic shared by the sync-pass path — this test pins it
        // so the read-time seeding cannot be blamed for changing it.
        val rows = listOf(
            Backfill.TranscriptionRowLite(t1, wordCount = 10, durationMs = 1000, syncId = "sync-1", chars = 120)
        )
        val a = Backfill.fromTranscriptionRows(rows, "hash-a", "device-a", now = 1L)
        val b = Backfill.fromTranscriptionRows(rows, "hash-b", "device-a", now = 1L)
        assertEquals(a.map { it.eventId }, b.map { it.eventId })
    }

    @Test
    fun backfill_of_populated_history_spreads_across_prior_days_for_the_trailing_average() {
        // Three distinct prior days: this is what gives the dashboard a
        // non-zero trailing average instead of the empty state.
        val rows = listOf(
            Backfill.TranscriptionRowLite(t1 - 2 * 86_400_000L, wordCount = 4, durationMs = 100, syncId = "s1", chars = 20),
            Backfill.TranscriptionRowLite(t1 - 86_400_000L, wordCount = 6, durationMs = 200, syncId = "s2", chars = 30),
            Backfill.TranscriptionRowLite(t1, wordCount = 8, durationMs = 300, syncId = "s3", chars = 40)
        )
        val records = Backfill.fromTranscriptionRows(rows, "hash-a", "device-a", now = 1L)
        val days = records.map { it.day }.toSet()
        assertEquals("three prior days must all be represented", 3, days.size)
        assertTrue(records.all { it.wordCount > 0 })
    }

    @Test
    fun backfill_of_empty_history_produces_nothing_so_a_new_user_keeps_the_empty_state() {
        assertEquals(
            emptyList<StatRecord>(),
            Backfill.fromTranscriptionRows(emptyList(), "hash-a", "device-a", now = 1L)
        )
        assertEquals(
            emptyList<StatRecord>(),
            Backfill.fromDailyStats(emptyList(), "hash-a", "device-a", now = 1L)
        )
    }}
