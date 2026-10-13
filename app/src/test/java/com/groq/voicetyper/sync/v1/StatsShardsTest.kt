package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Stats time shards: naming vectors are byte-identical with the Windows
 * implementation (`stats_shards.rs`). Any divergence splits every shard on
 * every device, so the literals below must match on both sides.
 */
class StatsShardsTest {

    private fun rec(eventId: String, day: String, words: Int = 1): StatRecord =
        StatRecord(eventId, day, words, 1000L, 0L, "", null, 1_700_000_000_000L, words * 5)

    private fun uuid(n: Int): String =
        java.util.UUID.nameUUIDFromBytes("shard-test-$n".toByteArray()).toString()

    // ---- Naming vectors (cross-platform contract) ----

    @Test
    fun monthOfDay_vectors() {
        assertEquals("2026-08", StatsShards.monthOfDay("2026-08-20"))
        assertEquals("2024-02", StatsShards.monthOfDay("2024-02-29")) // leap day
        assertEquals("2025-12", StatsShards.monthOfDay("2025-12-31")) // year edge
        assertEquals("2026-01", StatsShards.monthOfDay("2026-01-01")) // year edge
        assertEquals("1970-01", StatsShards.monthOfDay("1970-01-01"))
        // Malformed -> unroutable.
        assertNull(StatsShards.monthOfDay(""))
        assertNull(StatsShards.monthOfDay("2026-8-5"))
        assertNull(StatsShards.monthOfDay("2026-13-01"))
        assertNull(StatsShards.monthOfDay("2026-00-10"))
        assertNull(StatsShards.monthOfDay("2023-02-29")) // not a leap year
        assertNull(StatsShards.monthOfDay("not-a-day!"))
        assertNull(StatsShards.monthOfDay("2026-08-20T00:00:00"))
    }

    @Test
    fun shardFileForMonth_vectors() {
        assertEquals("stats-2026-08.json", StatsShards.shardFileForMonth("2026-08"))
        assertEquals("stats-2024-02.json", StatsShards.shardFileForMonth("2024-02"))
        assertNull(StatsShards.shardFileForMonth("2026-13"))
        assertNull(StatsShards.shardFileForMonth("2026-00"))
        assertNull(StatsShards.shardFileForMonth("26-08"))
        assertNull(StatsShards.shardFileForMonth(""))
    }

    @Test
    fun chunkFileNames_overflow() {
        assertEquals(2, StatsShards.FIRST_OVERFLOW_PART)
        assertEquals("stats-2026-08.json", StatsShards.chunkFileName("2026-08", 0))
        assertEquals("stats-2026-08-p02.json", StatsShards.chunkFileName("2026-08", 1))
        assertEquals("stats-2026-08-p03.json", StatsShards.chunkFileName("2026-08", 2))
        assertEquals("stats-2026-08-p99.json", StatsShards.chunkFileName("2026-08", 98))
        assertNull(StatsShards.chunkFileName("2026-13", 0))
    }

    @Test
    fun shardName_accept_reject() {
        assertTrue(StatsShards.isStatsShardFileName("stats-2026-08.json"))
        assertTrue(StatsShards.isStatsShardFileName("stats-2024-02.json"))
        assertTrue(StatsShards.isStatsShardFileName("stats-2026-08-p02.json"))
        assertTrue(StatsShards.isStatsShardFileName("stats-2026-08-p99.json"))
        // Reject: legacy, other domains, backups, bad shapes, case variants.
        assertFalse(StatsShards.isStatsShardFileName("stats.json"))
        assertFalse(StatsShards.isStatsShardFileName("dictionary.json"))
        assertFalse(StatsShards.isStatsShardFileName("snippets.json"))
        assertFalse(StatsShards.isStatsShardFileName("settings.json"))
        assertFalse(StatsShards.isStatsShardFileName("stats.json.bak"))
        assertFalse(StatsShards.isStatsShardFileName("stats-2026-8.json"))
        assertFalse(StatsShards.isStatsShardFileName("stats-2026-13.json"))
        assertFalse(StatsShards.isStatsShardFileName("stats-2026-00.json"))
        assertFalse(StatsShards.isStatsShardFileName("stats-26-08.json"))
        assertFalse(StatsShards.isStatsShardFileName("STATS-2026-08.json"))
        assertFalse(StatsShards.isStatsShardFileName("stats-2026-08-p1.json"))
        assertFalse(StatsShards.isStatsShardFileName("stats-2026-08-p00.json"))
        assertFalse(StatsShards.isStatsShardFileName("stats-2026-08-p02.json.bak"))
        assertFalse(StatsShards.isStatsShardFileName("agents.json"))
    }

    @Test
    fun shardSortKeys_order_parts() {
        assertEquals("2026-08" to 1, StatsShards.shardSortKey("stats-2026-08.json"))
        assertEquals("2026-08" to 2, StatsShards.shardSortKey("stats-2026-08-p02.json"))
        assertNull(StatsShards.shardSortKey("stats.json"))
        val names = listOf("stats-2026-09.json", "stats-2026-08-p02.json", "stats-2026-08.json")
        assertEquals(
            listOf("stats-2026-08.json", "stats-2026-08-p02.json", "stats-2026-09.json"),
            names.sortedWith(compareBy({ StatsShards.shardSortKey(it)!!.first }, { StatsShards.shardSortKey(it)!!.second }))
        )
    }

    // ---- Chunking ----

    @Test
    fun over999Chunks_isAnExplicitError_notACrash() {
        // F4 Windows parity (stats_shards.rs assign_chunk_names): more than
        // 999 chunks for one month must be an explicit classified error, never
        // a mid-pass crash. Tested with pre-split chunks (1000 chunks need
        // tens of millions of rows to materialize).
        val chunks = List(1001) { emptyList<StatRecord>() }
        try {
            StatsShards.assignChunkNames("2026-08", chunks)
            fail("1001 chunks must not name successfully")
        } catch (e: SyncError.Fatal) {
            assertTrue(
                "error must name the part-number ceiling, got: ${e.message}",
                (e.message ?: "").contains("999")
            )
        }
    }

    @Test
    fun empty_month_yields_no_chunks() {
        assertTrue(StatsShards.chunkMonthRecords("2026-08", emptyList()).isEmpty())
    }

    @Test
    fun small_month_is_one_base_chunk_sorted() {
        val rows = listOf(
            rec(uuid(3), "2026-08-20", 3),
            rec(uuid(1), "2026-08-02", 1),
            rec(uuid(2), "2026-08-02", 2)
        )
        val chunks = StatsShards.chunkMonthRecords("2026-08", rows)
        assertEquals(1, chunks.size)
        assertEquals("stats-2026-08.json", chunks[0].first)
        // Canonical order: day first, then eventId (the two 08-02 rows order by UUID).
        val sameDay = listOf(uuid(1), uuid(2)).sorted()
        assertEquals(sameDay + uuid(3), chunks[0].second.map { it.eventId })
        // Envelope round-trips through the real parser.
        val bytes = DomainSerializer.serializeStats(StatsDomain(entries = chunks[0].second)).toByteArray()
        val parsed = DomainSerializer.parseStats(bytes)!!
        assertEquals(3, parsed.entries.size)
        assertEquals('\n', bytes.last().toInt().toChar())
    }

    @Test
    fun chunking_is_deterministic_regardless_of_input_order() {
        val mk = { (0 until 50).map { i -> rec(uuid(i), "2026-08-%02d".format((i % 28) + 1), i) } }
        val a = StatsShards.chunkMonthRecords("2026-08", mk())
        val b = StatsShards.chunkMonthRecords("2026-08", mk().reversed())
        assertEquals(a, b)
    }

    @Test
    fun count_overflow_spills_into_p02() {
        val n = AppDataDriveStore.MAX_ENVELOPE_ITEMS + 1
        val rowAt = { i: Int ->
            rec(
                "%08x-0000-4000-8000-%012x".format(i, (i.toLong() * 2654435761L) and 0xffffffffffffL),
                "2026-08-15", 1
            )
        }
        val rows = (0 until n).map(rowAt)
        assertEquals(n, rows.map { it.eventId }.toSet().size)
        val chunks = StatsShards.chunkMonthRecords("2026-08", rows)
        assertEquals("50_001 rows must spill into p02", 2, chunks.size)
        assertEquals("stats-2026-08.json", chunks[0].first)
        assertEquals("stats-2026-08-p02.json", chunks[1].first)
        assertEquals(AppDataDriveStore.MAX_ENVELOPE_ITEMS, chunks[0].second.size)
        assertEquals(1, chunks[1].second.size)
        for ((name, entries) in chunks) {
            assertTrue("$name count cap", entries.size <= AppDataDriveStore.MAX_ENVELOPE_ITEMS)
            val bytes = DomainSerializer.serializeStats(StatsDomain(entries = entries)).toByteArray()
            assertTrue("$name byte cap", bytes.size <= AppDataDriveStore.MAX_DOMAIN_BYTES)
            assertEquals(entries.size, DomainSerializer.parseStats(bytes)!!.entries.size)
        }
        val all = chunks.flatMap { it.second }
        val expect = (0 until n).map(rowAt).sortedWith(compareBy({ it.day }, { it.eventId }))
        assertEquals(expect, all)
    }

    // ---- Union + diff ----

    @Test
    fun unionMerge_dedups_and_applies_LWW() {
        val a = rec(uuid(1), "2026-08-01", 10)
        val b = rec(uuid(2), "2026-09-01", 20)
        val bNewer = b.copy(wordCount = 25, updatedAt = 999L, deviceId = "d")
        val merged = StatsShards.unionStatsMerge(listOf(listOf(bNewer), listOf(a), listOf(b)))
        assertEquals(2, merged.size)
        val byId = merged.associateBy { it.eventId }
        assertEquals(a, byId[uuid(1)])
        assertEquals(bNewer, byId[uuid(2)])
        assertEquals("sessions preserved", 2, byId.size)
        assertEquals(10 + 25, byId.values.sumOf { it.wordCount })
    }

    @Test
    fun desiredShardRows_excludes_legacy_identical() {
        val legacyRow = rec(uuid(1), "2026-08-01", 10)
        val newRow = rec(uuid(2), "2026-08-02", 20)
        val updated = legacyRow.copy(wordCount = 11, updatedAt = 500L)
        val merged = listOf(updated, newRow)
        val legacyById = StatsShards.indexByEventId(listOf(legacyRow))
        assertEquals(listOf(updated, newRow), StatsShards.desiredShardRows("2026-08", merged, legacyById))
        // Fully legacy-identical input yields no shard rows (no rewrite).
        assertTrue(StatsShards.desiredShardRows("2026-08", listOf(legacyRow), legacyById).isEmpty())
    }

    @Test
    fun desiredShardRows_keeps_LWW_updated_legacy_row() {
        val legacyRow = rec(uuid(1), "2026-08-01", 10).copy(updatedAt = 100L, deviceId = "a")
        val newer = legacyRow.copy(wordCount = 12, updatedAt = 200L)
        val merged = StatsShards.unionStatsMerge(listOf(listOf(newer), listOf(legacyRow)))
        assertEquals(1, merged.size)
        assertEquals(12, merged[0].wordCount)
        val desired = StatsShards.desiredShardRows("2026-08", merged, StatsShards.indexByEventId(listOf(legacyRow)))
        assertEquals(listOf(newer), desired)
    }

    @Test
    fun desiredShardRows_scoped_to_month() {
        val aug = rec(uuid(1), "2026-08-01", 10)
        val sep = rec(uuid(2), "2026-09-01", 20)
        val merged = listOf(aug, sep)
        assertEquals(listOf(aug), StatsShards.desiredShardRows("2026-08", merged, emptyMap()))
        assertEquals(listOf(sep), StatsShards.desiredShardRows("2026-09", merged, emptyMap()))
        assertTrue(StatsShards.desiredShardRows("2026-10", merged, emptyMap()).isEmpty())
    }

    @Test
    fun monthsIn_lists_valid_months_sorted() {
        val rows = listOf(
            rec(uuid(2), "2026-09-01"),
            rec(uuid(1), "2026-08-01"),
            rec(uuid(3), "2026-08-15")
        )
        assertEquals(listOf("2026-08", "2026-09"), StatsShards.monthsIn(rows))
    }

    @Test
    fun legacy_file_name_is_never_a_shard() {
        assertFalse(StatsShards.isStatsShardFileName(StatsShards.LEGACY_STATS_FILE))
        assertEquals("stats.json", StatsShards.LEGACY_STATS_FILE)
    }
}
