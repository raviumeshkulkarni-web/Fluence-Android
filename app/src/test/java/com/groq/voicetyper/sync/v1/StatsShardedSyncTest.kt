package com.groq.voicetyper.sync.v1

import com.groq.voicetyper.sync.v1.V1SyncEngine.MaxSeenRef
import com.groq.voicetyper.sync.v1.V1SyncEngine.StatLocal
import com.groq.voicetyper.sync.v1.V1SyncEngine.StatV1Store
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stats time shards: engine integration over a content-aware fake with
 * production-identical CAS. Proves the frozen-legacy + monthly-shard
 * architecture: month routing, union reads, old/new coexistence, per-file
 * failure containment, stale retry, liveness gating, convergence,
 * reinstall, rollover, and the lifetime semantic invariants.
 *
 * Mirrors the Windows suite (`frozen.rs::sharded_stats_tests`); both must
 * stay green. Deltas are deliberate: unroutable-row rescue is covered here
 * at engine level, on Windows at store level
 * (`stores.rs::save_merged_keeps_shard_unroutable_rows_dirty`), because
 * Room has no unit-test harness in this repo.
 */
class StatsShardedSyncTest {

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private data class ShardFile(var name: String, var fileId: String, var version: String?)

    private class ShardedFakeDrive(
        var legacyBytes: ByteArray? = null,
        var legacyVersion: String? = null,
    ) : V1SyncEngine.DomainGateway {
        val files = mutableListOf<ShardFile>()
        val contents = mutableMapOf<String, ByteArray>() // fileId -> bytes
        val puts = mutableListOf<Triple<String, ByteArray, String?>>() // (name, bytes, expectedVersion)
        val deletes = mutableListOf<String>()
        var legacyPuts = 0
        private var versionSeq = 100
        var staleOnce = false
        var concurrentLegacy: ByteArray? = null
        var blackhole = false

        fun withLegacy(rows: List<StatRecord>, version: String = "v1") = apply {
            legacyBytes = DomainSerializer.serializeStats(StatsDomain(entries = rows)).toByteArray()
            legacyVersion = version
        }

        fun withShard(name: String, fileId: String, rows: List<StatRecord>, version: String) = apply {
            files.add(ShardFile(name, fileId, version))
            contents[fileId] = DomainSerializer.serializeStats(StatsDomain(entries = rows)).toByteArray()
        }

        fun withShardBytes(name: String, fileId: String, bytes: ByteArray, version: String) = apply {
            files.add(ShardFile(name, fileId, version))
            contents[fileId] = bytes.copyOf()
        }

        fun contentsOf(name: String): ByteArray? {
            val meta = files.firstOrNull { it.name == name } ?: return null
            return contents[meta.fileId]?.copyOf()
        }

        fun parsedOf(name: String): List<StatRecord> {
            val bytes = contentsOf(name) ?: return emptyList()
            return DomainSerializer.parseStats(bytes)?.entries ?: emptyList()
        }

        fun putNames(): List<String> = puts.map { it.first }

        override fun getDomain(domain: DomainFile): AppDataDriveStore.DomainFetch =
            AppDataDriveStore.DomainFetch(legacyBytes?.copyOf(), legacyVersion)

        override fun putDomain(domain: DomainFile, data: ByteArray, expectedVersion: String?): String {
            check(domain != DomainFile.STATS) { "new clients must never PUT stats.json" }
            legacyPuts++
            return "v-legacy-$legacyPuts"
        }

        override fun listStatsShards(): List<StatsShardMeta> =
            files.filter { StatsShards.isStatsShardFileName(it.name) }
                .sortedWith(compareBy(
                    { StatsShards.shardSortKey(it.name)!!.first },
                    { StatsShards.shardSortKey(it.name)!!.second },
                    { it.fileId }
                ))
                .map { StatsShardMeta(it.name, it.fileId, it.version) }

        override fun getStatsShardBytes(fileId: String): ByteArray? {
            val bytes = contents[fileId]?.copyOf() ?: return null
            if (bytes.size > AppDataDriveStore.MAX_DOMAIN_BYTES) {
                throw SyncError.Rejected("stats shard payload exceeds cap")
            }
            return bytes
        }

        override fun putStatsShard(
            name: String,
            bytes: ByteArray,
            expectedVersion: String?,
            preferredFileId: String?
        ): String {
            check(StatsShards.isStatsShardFileName(name)) { "non-shard upload refused" }
            if (bytes.size > AppDataDriveStore.MAX_DOMAIN_BYTES) {
                throw SyncError.Rejected("refusing oversized shard upload")
            }
            if (staleOnce) {
                staleOnce = false
                concurrentLegacy?.let {
                    legacyBytes = it.copyOf()
                    legacyVersion = "v-concurrent"
                }
                throw SyncError.StaleVersion("v-live")
            }
            puts.add(Triple(name, bytes.copyOf(), expectedVersion))
            val existing = files.filter { it.name == name }
            val selected = if (preferredFileId != null) {
                existing.firstOrNull { it.fileId == preferredFileId }
                    ?: if (expectedVersion != null) throw SyncError.StaleVersion(null)
                    else existing.minByOrNull { it.fileId }
            } else {
                existing.minByOrNull { it.fileId }
            }
            versionSeq++
            val newVersion = "rv$versionSeq"
            if (selected != null) {
                val fresh = (expectedVersion != null && selected.version != null &&
                    expectedVersion == selected.version) ||
                    (expectedVersion == null && selected.version == null)
                if (!fresh) throw SyncError.StaleVersion(selected.version)
                if (!blackhole) {
                    selected.version = newVersion
                    contents[selected.fileId] = bytes.copyOf()
                }
                return newVersion
            }
            if (!blackhole) {
                val fileId = "id-created-$versionSeq"
                files.add(ShardFile(name, fileId, newVersion))
                contents[fileId] = bytes.copyOf()
            }
            return newVersion
        }

        override fun deleteStatsShard(fileId: String) {
            deletes.add(fileId)
            files.removeAll { it.fileId == fileId }
            contents.remove(fileId)
        }
    }

    /** Dirty-aware store mirroring RoomStatV1Store rescue (incl. unroutable). */
    private class TrackingStatStore(
        val rows: MutableList<StatLocal> = mutableListOf(),
    ) : StatV1Store {
        var applyCalls = 0
        var backfillDone = true
        override suspend fun loadByAccount(hash: String) = rows.filter { it.accountHash == hash }
        override suspend fun stampUnstamped(hash: String) {}
        override suspend fun hasDirty(hash: String) = rows.any { it.accountHash == hash && it.dirty }
        override suspend fun applyMergedAndClearDirty(hash: String, deviceId: String, merged: List<StatRecord>) {
            applyCalls++
            val toClear = mutableListOf<String>()
            for (rec in merged) {
                val idx = rows.indexOfFirst { it.eventId == rec.eventId }
                val before = rows.getOrNull(idx.takeIf { it >= 0 } ?: -1)
                if (before?.accountHash != null && before.accountHash != hash) continue
                val routable = StatsShards.monthOfDay(rec.day) != null
                if (before != null && before.dirty &&
                    (before.updatedAt > rec.updatedAt ||
                        (before.updatedAt == rec.updatedAt && before.deviceId >= rec.deviceId))
                ) continue
                val local = StatLocal(rec.eventId, rec.day, rec.wordCount, rec.durationMs, rec.updatedAt,
                    rec.deviceId, rec.deletedAt, hash, dirty = !routable, everPushed = routable,
                    timestampMs = rec.timestampMs, chars = rec.chars)
                if (idx >= 0) rows[idx] = local else rows.add(local)
                if (routable) toClear.add(rec.eventId)
            }
            for (id in toClear) {
                val i = rows.indexOfFirst { it.eventId == id }
                if (i >= 0) rows[i] = rows[i].copy(dirty = false, everPushed = true)
            }
        }

        override suspend fun isBackfillDone(hash: String) = backfillDone
        override suspend fun setBackfillDone(hash: String, done: Boolean) { backfillDone = done }
        override suspend fun backfillIfNeeded(hash: String, deviceId: String) = false
    }

    private fun localOf(
        rec: StatRecord,
        hash: String = "hash",
        dirty: Boolean = false,
    ) = StatLocal(rec.eventId, rec.day, rec.wordCount, rec.durationMs, rec.updatedAt, rec.deviceId,
        rec.deletedAt, hash, dirty = dirty, everPushed = !dirty,
        timestampMs = rec.timestampMs, chars = rec.chars)

    private fun rec(eventId: String, day: String, words: Int = 1): StatRecord =
        StatRecord(eventId, day, words, 1000L, 0L, "", null, 1_700_000_000_000L, words * 5)

    private fun uuidOf(name: String): String =
        java.util.UUID.nameUUIDFromBytes(name.toByteArray()).toString()

    private fun sortedIds(rows: List<StatRecord>) = rows.map { it.eventId }.sorted()

    private fun runSync(drive: ShardedFakeDrive, store: TrackingStatStore): V1SyncEngine.SyncResult =
        runBlocking { V1SyncEngine.syncStats(store, drive, "hash", "device-a", MaxSeenRef(0L)) }

    // ------------------------------------------------------------------
    // Cases (mirroring Windows sharded_stats_tests)
    // ------------------------------------------------------------------

    @Test
    fun read_unions_legacy_and_shards_with_no_put_when_clean() {
        val a = rec(uuidOf("a1"), "2026-08-01", 10)
        val b = rec(uuidOf("b1"), "2026-09-01", 20)
        val drive = ShardedFakeDrive().withLegacy(listOf(a))
        drive.withShard("stats-2026-09.json", "id-s09", listOf(b), "v2")
        val store = TrackingStatStore(mutableListOf(localOf(a)))
        val out = runSync(drive, store)
        assertFalse("converged content must not re-upload", out.uploaded)
        assertTrue(out.merged)
        assertTrue("no PUT when desired equals remote", drive.puts.isEmpty())
        assertEquals(sortedIds(listOf(a, b)), sortedIds(store.rows.map { it.toRecord() }))
    }

    @Test
    fun new_dirty_row_routes_to_month_shard_and_legacy_stays_frozen() {
        val a = rec(uuidOf("a1"), "2026-08-01", 10)
        val n = rec(uuidOf("n1"), "2026-08-20", 30)
        val drive = ShardedFakeDrive().withLegacy(listOf(a))
        val store = TrackingStatStore(mutableListOf(localOf(a), localOf(n, dirty = true)))
        val out = runSync(drive, store)
        assertTrue(out.uploaded)
        assertEquals(listOf("stats-2026-08.json"), drive.putNames())
        // Shard holds ONLY the non-legacy row: history is never recopied.
        assertEquals(sortedIds(listOf(n)), sortedIds(drive.parsedOf("stats-2026-08.json")))
        // Legacy untouched.
        assertEquals(sortedIds(listOf(a)), sortedIds(drive.parsedOfLegacy()))
        assertEquals(sortedIds(listOf(a, n)), sortedIds(store.rows.map { it.toRecord() }))
        // Production tie-guard: the uploaded row ties LWW (identical stamp),
        // so it stays dirty locally — exactly like the flat engine.
        assertTrue(store.rows.single { it.eventId == n.eventId }.dirty)
        // …but the diff-driven engine never re-uploads identical content:
        // the second pass converges with no PUT (no upload loop).
        val out2 = runSync(drive, store)
        assertFalse(out2.uploaded)
        assertEquals("no repeated upload of identical bytes", 1, drive.puts.size)
    }

    @Test
    fun corrupt_legacy_is_never_repaired_while_shards_sync() {
        val n = rec(uuidOf("n1"), "2026-08-20", 30)
        val before = "{ not json".toByteArray()
        val drive = ShardedFakeDrive(legacyBytes = before, legacyVersion = "v9")
        val store = TrackingStatStore(mutableListOf(localOf(n, dirty = true)))
        val out = runSync(drive, store)
        assertTrue(out.uploaded)
        assertTrue("corrupt frozen legacy degrades the pass", out.skippedCorrupt)
        assertEquals(listOf("stats-2026-08.json"), drive.putNames())
        assertEquals(0, drive.legacyPuts)
        assertTrue(drive.legacyBytes!!.contentEquals(before))
    }

    @Test
    fun old_client_legacy_write_is_visible_with_no_put() {
        val a = rec(uuidOf("a1"), "2026-08-01", 10)
        val r = rec(uuidOf("r1"), "2026-08-02", 99) // written by an old client into stats.json
        val drive = ShardedFakeDrive().withLegacy(listOf(a, r), "v5")
        val store = TrackingStatStore(mutableListOf(localOf(a)))
        val out = runSync(drive, store)
        assertFalse("legacy-owned rows need no shard rewrite", out.uploaded)
        assertTrue(drive.puts.isEmpty())
        assertEquals(sortedIds(listOf(a, r)), sortedIds(store.rows.map { it.toRecord() }))
    }

    @Test
    fun corrupt_shard_with_content_is_repaired_via_cas() {
        val n = rec(uuidOf("n1"), "2026-08-05", 30)
        val drive = ShardedFakeDrive()
            .withShardBytes("stats-2026-08.json", "id-c1", "{ corrupt".toByteArray(), "vC1")
        val store = TrackingStatStore(mutableListOf(localOf(n, dirty = true)))
        val out = runSync(drive, store)
        assertTrue(out.uploaded)
        assertEquals(1, drive.puts.size)
        assertEquals("stats-2026-08.json", drive.puts.single().first)
        assertEquals("repair CAS-targets the corrupt revision", "vC1", drive.puts.single().third)
        assertEquals(sortedIds(listOf(n)), sortedIds(drive.parsedOf("stats-2026-08.json")))
    }

    @Test
    fun corrupt_shard_with_no_content_is_left_alone() {
        val drive = ShardedFakeDrive()
            .withShardBytes("stats-2026-08.json", "id-c1", "{ corrupt".toByteArray(), "vC1")
        val store = TrackingStatStore()
        val out = runSync(drive, store)
        assertFalse("never fabricate a repair with no usable state", out.uploaded)
        assertTrue(drive.puts.isEmpty())
        assertEquals("{ corrupt", drive.contentsOf("stats-2026-08.json")!!.toString(Charsets.UTF_8))
    }

    @Test
    fun oversize_shard_is_skipped_while_healthy_months_sync() {
        val l = rec(uuidOf("l1"), "2026-08-01", 10)
        val n = rec(uuidOf("n1"), "2026-09-10", 20)
        val big = ByteArray(AppDataDriveStore.MAX_DOMAIN_BYTES + 1)
        val drive = ShardedFakeDrive().withLegacy(listOf(l))
        drive.withShardBytes("stats-2026-08.json", "id-big", big, "vB")
        val store = TrackingStatStore(mutableListOf(localOf(l), localOf(n, dirty = true)))
        val out = runSync(drive, store)
        assertTrue(out.uploaded)
        assertTrue("oversize shard degrades the pass", out.skippedCorrupt)
        assertEquals(listOf("stats-2026-09.json"), drive.putNames())
        assertTrue(drive.contentsOf("stats-2026-08.json")!!.contentEquals(big))
        assertEquals(sortedIds(listOf(l, n)), sortedIds(store.rows.map { it.toRecord() }))
    }

    @Test
    fun stale_version_retries_with_full_remerge() {
        val local = rec(uuidOf("l1"), "2026-08-01", 10)
        val remote = rec(uuidOf("r1"), "2026-08-02", 20)
        val concurrent = rec(uuidOf("c1"), "2026-08-03", 30)
        val drive = ShardedFakeDrive().withLegacy(listOf(remote))
        drive.staleOnce = true
        val both = listOf(remote, concurrent).sortedWith(compareBy({ it.day }, { it.eventId }))
        drive.concurrentLegacy =
            DomainSerializer.serializeStats(StatsDomain(entries = both)).toByteArray()
        val store = TrackingStatStore(mutableListOf(localOf(local, dirty = true)))
        val out = runSync(drive, store)
        assertTrue(out.uploaded)
        // Re-merged: local + remote + concurrent, minus legacy-identical rows.
        assertEquals(sortedIds(listOf(local)), sortedIds(drive.parsedOf("stats-2026-08.json")))
        assertEquals(
            sortedIds(listOf(local, remote, concurrent)),
            sortedIds(store.rows.map { it.toRecord() })
        )
    }

    @Test
    fun liveness_miss_never_marks_pushed() {
        val n = rec(uuidOf("n1"), "2026-08-20", 30)
        val drive = ShardedFakeDrive()
        drive.blackhole = true
        val store = TrackingStatStore(mutableListOf(localOf(n, dirty = true)))
        val out = runSync(drive, store)
        assertFalse(out.uploaded)
        assertEquals("never mark pushed unless live", 0, store.applyCalls)
        assertTrue("rows stay dirty for the next pass", store.rows.single().dirty)
    }

    @Test
    fun two_devices_converge_through_shards() {
        val a = rec(uuidOf("a1"), "2026-08-01", 10)
        val b = rec(uuidOf("b1"), "2026-08-02", 20)
        val drive = ShardedFakeDrive()
        // Device A writes.
        val da = TrackingStatStore(mutableListOf(localOf(a, dirty = true)))
        runBlocking { V1SyncEngine.syncStats(da, drive, "hash", "device-a", MaxSeenRef(0L)) }
        // Device B pulls (no PUT: desired equals remote).
        val db = TrackingStatStore()
        val outB = runBlocking { V1SyncEngine.syncStats(db, drive, "hash", "device-b", MaxSeenRef(0L)) }
        assertFalse(outB.uploaded)
        assertEquals(sortedIds(listOf(a)), sortedIds(db.rows.map { it.toRecord() }))
        // Device B writes; A pulls.
        db.rows.add(localOf(b, dirty = true))
        runBlocking { V1SyncEngine.syncStats(db, drive, "hash", "device-b", MaxSeenRef(0L)) }
        val da2 = TrackingStatStore(mutableListOf(localOf(a)))
        runBlocking { V1SyncEngine.syncStats(da2, drive, "hash", "device-a", MaxSeenRef(0L)) }
        assertEquals(sortedIds(listOf(a, b)), sortedIds(da2.rows.map { it.toRecord() }))
        assertEquals(sortedIds(listOf(a, b)), sortedIds(db.rows.map { it.toRecord() }))
        assertEquals("sessions identical on both", 2, da2.rows.size)
        assertEquals(sortedIds(listOf(a, b)), sortedIds(drive.parsedOf("stats-2026-08.json")))
    }

    @Test
    fun reinstall_materializes_legacy_and_shards_with_no_put() {
        val a = rec(uuidOf("a1"), "2026-08-01", 10)
        val b = rec(uuidOf("b1"), "2026-08-02", 20)
        val c = rec(uuidOf("c1"), "2026-09-01", 30)
        val drive = ShardedFakeDrive().withLegacy(listOf(a, b))
        drive.withShard("stats-2026-09.json", "id-s09", listOf(c), "v2")
        val store = TrackingStatStore()
        val out = runSync(drive, store)
        assertFalse(out.uploaded)
        assertTrue("complete remote needs no rewrite", drive.puts.isEmpty())
        assertEquals(sortedIds(listOf(a, b, c)), sortedIds(store.rows.map { it.toRecord() }))
    }

    @Test
    fun rollover_writes_each_month_shard_and_never_legacy() {
        val x = rec(uuidOf("x1"), "2026-08-31", 10)
        val y = rec(uuidOf("y1"), "2026-09-01", 20)
        val drive = ShardedFakeDrive()
        val store = TrackingStatStore(mutableListOf(localOf(x, dirty = true), localOf(y, dirty = true)))
        val out = runSync(drive, store)
        assertTrue(out.uploaded)
        assertEquals(
            listOf("stats-2026-08.json", "stats-2026-09.json"),
            drive.putNames().sorted()
        )
        assertEquals(sortedIds(listOf(x)), sortedIds(drive.parsedOf("stats-2026-08.json")))
        assertEquals(sortedIds(listOf(y)), sortedIds(drive.parsedOf("stats-2026-09.json")))
        assertEquals("legacy never created by new clients", 0, drive.legacyPuts)
    }

    @Test
    fun lifetime_invariants_match_single_ledger_baseline() {
        val ledger = (0 until 40).map { i ->
            rec(uuidOf("row$i"), "2026-%02d-%02d".format(8 + (i % 3), (i % 28) + 1), (i % 17) + 1)
        }.toMutableList()
        val dupOld = rec(uuidOf("dup"), "2026-08-15", 111).copy(updatedAt = 100L, deviceId = "a")
        val dupNew = dupOld.copy(wordCount = 222, updatedAt = 200L)
        val agg = rec(uuidOf("agg"), "2026-09-10", 0).copy(timestampMs = 0L, wordCount = 500, chars = 0)
        ledger += dupOld
        ledger += dupNew
        ledger += agg
        // Baseline: single-ledger union, canonical order.
        val baseline = StatsShards.unionStatsMerge(listOf(ledger))
            .sortedWith(compareBy({ it.day }, { it.eventId }))

        // Sharded world: first 15 rows live in legacy, the rest sync up.
        val legacyRows = baseline.take(15)
        val drive = ShardedFakeDrive().withLegacy(legacyRows)
        val store = TrackingStatStore(baseline.map { localOf(it, dirty = true) }.toMutableList())
        val out = runSync(drive, store)
        assertTrue(out.uploaded)

        // Invariant 1: store materializes exactly the baseline set.
        assertEquals(sortedIds(baseline), sortedIds(store.rows.map { it.toRecord() }))
        // Invariant 2: remote union (legacy + all shards) equals baseline.
        val remoteAll = legacyRows.toMutableList()
        for (f in drive.files.filter { StatsShards.isStatsShardFileName(it.name) }) {
            remoteAll += DomainSerializer.parseStats(drive.contents.getValue(f.fileId))!!.entries
        }
        val remoteMerged = StatsShards.unionStatsMerge(listOf(remoteAll))
        assertEquals(sortedIds(baseline), sortedIds(remoteMerged))
        assertEquals(baseline.size, remoteMerged.size)
        // Invariant 3: sessions = record count; sums identical.
        fun sums(rows: List<StatRecord>) = Triple(
            rows.size,
            rows.sumOf { it.wordCount },
            rows.sumOf { it.chars } to rows.sumOf { it.durationMs }
        )
        assertEquals(sums(baseline), sums(store.rows.map { it.toRecord() }))
        // Invariant 4: per-day session counts and word sums identical.
        fun perDay(rows: List<StatRecord>) = rows.groupBy({ it.day }, { it.wordCount })
            .mapValues { (_, ws) -> ws.size to ws.sum() }.toSortedMap()
        assertEquals(perDay(baseline), perDay(store.rows.map { it.toRecord() }))
        // Invariant 5: timestamp multiset (weekly/activity charts) identical.
        assertEquals(
            baseline.map { it.timestampMs }.sorted(),
            store.rows.map { it.timestampMs }.sorted()
        )
    }

    @Test
    fun legacy_created_later_is_absorbed_and_shards_shed_duplicates() {
        val a = rec(uuidOf("a1"), "2026-08-01", 10)
        val b = rec(uuidOf("b1"), "2026-08-02", 20)
        val c = rec(uuidOf("c1"), "2026-08-03", 30) // old-client row, legacy-only
        // World starts new-client-only: shards hold everything, no legacy file.
        val drive = ShardedFakeDrive()
            .withShard("stats-2026-08.json", "id-s08", listOf(a, b), "v1")
        val store = TrackingStatStore(mutableListOf(localOf(a), localOf(b)))
        // An old client creates stats.json with its view ([b] plus its own [c]).
        drive.legacyBytes =
            DomainSerializer.serializeStats(StatsDomain(entries = listOf(b, c))).toByteArray()
        drive.legacyVersion = "v9"
        val out = runSync(drive, store)
        assertTrue("shard must shed the now-legacy-identical row", out.uploaded)
        // Shard keeps only non-legacy rows; legacy untouched.
        assertEquals(sortedIds(listOf(a)), sortedIds(drive.parsedOf("stats-2026-08.json")))
        assertEquals(
            sortedIds(listOf(b, c)),
            sortedIds(drive.parsedOfLegacy())
        )
        assertEquals(sortedIds(listOf(a, b, c)), sortedIds(store.rows.map { it.toRecord() }))
        assertEquals(0, drive.legacyPuts)
        assertEquals("repair-style CAS against the existing shard", "v1", drive.puts.single().third)
    }

    @Test
    fun no_put_targets_the_legacy_file() {
        val n = rec(uuidOf("n1"), "2026-10-05", 44)
        val drive = ShardedFakeDrive(legacyBytes = "garbage".toByteArray(), legacyVersion = "v3")
        val store = TrackingStatStore(mutableListOf(localOf(n, dirty = true)))
        runSync(drive, store)
        assertEquals(0, drive.legacyPuts)
        assertEquals(listOf("stats-2026-10.json"), drive.putNames())
    }

    @Test
    fun unroutable_rows_stay_dirty_but_never_block_shards() {
        val bad = rec(uuidOf("bad1"), "not-a-day!", 5)
        val good = rec(uuidOf("good1"), "2026-08-05", 7)
        val drive = ShardedFakeDrive()
        val store = TrackingStatStore(mutableListOf(localOf(bad, dirty = true), localOf(good, dirty = true)))
        val out = runSync(drive, store)
        assertTrue("routable month still syncs", out.uploaded)
        assertEquals(listOf("stats-2026-08.json"), drive.putNames())
        assertEquals(sortedIds(listOf(good)), sortedIds(drive.parsedOf("stats-2026-08.json")))
        val badRow = store.rows.single { it.eventId == uuidOf("bad1") }
        assertTrue("unroutable row never stamped clean", badRow.dirty)
        // The good row stays dirty via the production tie-guard (identical
        // stamp), yet the second pass needs no PUT: diff-converged.
        assertTrue(store.rows.single { it.eventId == uuidOf("good1") }.dirty)
        val out2 = runSync(drive, store)
        assertFalse(out2.uploaded)
        assertEquals(1, drive.puts.size)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun ShardedFakeDrive.parsedOfLegacy(): List<StatRecord> =
        legacyBytes?.let { DomainSerializer.parseStats(it)?.entries } ?: emptyList()
}
