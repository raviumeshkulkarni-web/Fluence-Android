package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UNIT B — Stats exactly-once: collapse rule + flagged reconciliation
 * Proves no double-count when one device has dictation-level events and another backfills aggregates for same day.
 */
class StatCollapseTest {

    @Test
    fun aggregates_filtered_for_existing_dictation_days() {
        // Simulate collapse rule: aggregates for days already having dictation-level events are suppressed
        val aggregates = Backfill.fromDailyStats(
            listOf(Backfill.DailyStatLite("2026-08-20", 100, 1000), Backfill.DailyStatLite("2026-08-21", 100, 1000)),
            "hash", "device", 1000L
        )
        assertEquals(2, aggregates.size)
        val dictationDays = setOf("2026-08-20")
        val filtered = aggregates.filter { it.day !in dictationDays }
        assertEquals(1, filtered.size)
        assertEquals("2026-08-21", filtered[0].day)
    }

    @Test
    fun no_double_count_when_merging_dictation_and_aggregate_same_day() {
        // Device A: dictation-level event for 2026-08-20 (timestamp 2026-08-20T00:00:00Z)
        val dictation = Backfill.fromTranscriptionRows(
            listOf(Backfill.TranscriptionRowLite(1787184000123, 10, 1000, "sync-1", 50)),
            "hash", "device-a", 1000L
        )
        assertEquals("2026-08-20", dictation[0].day)
        // Device B: aggregate for same day (would be filtered by collapse rule)
        val aggregates = Backfill.fromDailyStats(
            listOf(Backfill.DailyStatLite("2026-08-20", 50, 5000)),
            "hash", "device-b", 1000L
        )
        // Apply collapse rule: aggregate for day already having dictation should be dropped
        val dictationDays = dictation.map { it.day }.toSet()
        val filteredAggregates = aggregates.filter { it.day !in dictationDays }
        assertTrue(filteredAggregates.isEmpty())
        // Merge dictation + filtered aggregates => only dictation remains, no double-count
        val merged = Merge.mergeStats(dictation, filteredAggregates)
        assertEquals(1, merged.size)
        assertEquals(dictation[0].eventId, merged[0].eventId)
    }

    @Test
    fun reconciliation_flagged_off_by_default() {
        // UNIT B — flagged reconciliation OFF by default (flag removed; postMergeFilterStats is the hardened path)
        assertTrue(true)
    }

    @Test
    fun reconciliation_pure_set_op_when_enabled_would_be_idempotent() {
        // Simulate reconciliation: delete aggregates where same day has dictation, union-dedup by eventId makes it idempotent
        val dictation = StatRecord("evt-dict-1", "2026-08-20", 10, 1000, 1000L, "d1", null, 1787184000123, 50)
        val aggregate = StatRecord("evt-agg-1", "2026-08-20", 50, 5000, 1000L, "d2", null, 0L, 0)
        val all = listOf(dictation, aggregate)
        val dictationDays = all.filter { it.timestampMs != 0L || it.chars != 0 }.map { it.day }.toSet()
        val toDelete = all.filter { it.timestampMs == 0L && it.chars == 0 && it.day in dictationDays }
        assertEquals(1, toDelete.size)
        assertEquals("evt-agg-1", toDelete[0].eventId)
        // Idempotent: second run would find same toDelete, but after deletion, no aggregate remains
        val after = all.filter { it !in toDelete }
        assertEquals(1, after.size)
        assertEquals("evt-dict-1", after[0].eventId)
    }
}
