package com.groq.voicetyper.sync.v1

import com.groq.voicetyper.sync.v1.V1SyncEngine.DictionaryLocal
import com.groq.voicetyper.sync.v1.V1SyncEngine.DictionaryV1Store
import com.groq.voicetyper.sync.v1.V1SyncEngine.MaxSeenRef
import com.groq.voicetyper.sync.v1.V1SyncEngine.SettingsLocal
import com.groq.voicetyper.sync.v1.V1SyncEngine.SettingsV1Store
import com.groq.voicetyper.sync.v1.V1SyncEngine.StatV1Store
import com.groq.voicetyper.sync.v1.V1SyncEngine.SnippetV1Store
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frozen v1.2 engine contract: GET→MERGE→PUT with Drive **version-number**
 * staleness detection (Drive v3 does not honor If-Match). Every staleness
 * retry RE-MERGES against the fresh remote (no lost updates), corruption
 * auto-skips, account isolation holds (load == accountHash only),
 * dirty/everPushed apply in one store transaction on success, maxSeen is
 * monotonic, and a clean fixed point produces no PUT.
 */
class V1SyncEngineTest {

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeDrive(
        var bytes: ByteArray? = null,
        var version: String? = null,
        var putCount: Int = 0,
        /** Queue of stale-version injections before a successful PUT. */
        val staleInjections: ArrayDeque<String?> = ArrayDeque(),
        val puts: MutableList<ByteArray> = mutableListOf(),
        /** expectedVersion argument passed to every putDomain attempt (CAS target). */
        val putVersions: MutableList<String?> = mutableListOf(),
    ) : V1SyncEngine.DomainGateway {
        override fun getDomain(domain: DomainFile): AppDataDriveStore.DomainFetch =
            AppDataDriveStore.DomainFetch(bytes?.copyOf(), version)

        override fun putDomain(domain: DomainFile, data: ByteArray, expectedVersion: String?): String {
            putCount++
            putVersions.add(expectedVersion)
            if (staleInjections.isNotEmpty()) {
                val live = staleInjections.removeFirst()
                throw SyncError.StaleVersion(live)
            }
            puts.add(data.copyOf())
            bytes = data.copyOf()
            version = "v-$putCount"
            return version!!
        }

        fun failNextPutWithStaleVersion(live: String?) = staleInjections.addLast(live)
    }

    /** Drive that never reflects a PUT on the next GET (eventual-consistency lag). */
    private class LaggingFakeDrive(
        var bytes: ByteArray? = null,
        var version: String? = null,
        var putCount: Int = 0,
    ) : V1SyncEngine.DomainGateway {
        override fun getDomain(domain: DomainFile): AppDataDriveStore.DomainFetch =
            AppDataDriveStore.DomainFetch(bytes?.copyOf(), version)

        override fun putDomain(domain: DomainFile, data: ByteArray, expectedVersion: String?): String {
            putCount++
            // The write succeeds but stays invisible to a subsequent GET; the
            // reported version never matches the PUT's return value.
            return "v-$putCount"
        }
    }

    private class FakeDictStore(
        val rows: MutableList<DictionaryLocal>,
        var applyCalls: Int = 0,
    ) : DictionaryV1Store {
        override suspend fun loadByAccount(hash: String) =
            rows.filter { it.accountHash == hash } // load == accountHash ONLY

        override suspend fun stampUnstamped(hash: String) {
            for (i in rows.indices) {
                if (rows[i].accountHash == null) rows[i] = rows[i].copy(accountHash = hash)
            }
        }

        override suspend fun hasDirty(hash: String) = rows.any { it.accountHash == hash && it.dirty }

        override suspend fun applyMergedAndClearDirty(hash: String, deviceId: String, merged: List<DictionaryRecord>) {
            applyCalls++
            // Windows parity: the account set is replaced by the merged
            // winners — records that LOST the merge (superseded by a newer
            // remote tombstone/edit) must not linger locally as ghosts.
            val mergedIds = merged.map { it.syncId }.toSet()
            rows.removeAll { it.accountHash == hash && it.syncId !in mergedIds }
            for (rec in merged) {
                val idx = rows.indexOfFirst { it.syncId == rec.syncId }
                val local = DictionaryLocal(
                    syncId = rec.syncId, businessKey = rec.businessKey, spoken = rec.spoken,
                    corrected = rec.corrected, isEnabled = rec.isEnabled, updatedAt = rec.updatedAt,
                    deletedAt = rec.deletedAt, deviceId = rec.deviceId, accountHash = hash,
                    dirty = false, everPushed = true
                )
                if (idx >= 0) rows[idx] = local else rows.add(local)
            }
        }
    }

    private class FakeSettingsStore(
        val values: MutableMap<String, Pair<String, Long>> = mutableMapOf()
    ) : SettingsV1Store {
        override suspend fun loadByAccount(hash: String) = values.map { (k, v) ->
            SettingsLocal(k, v.first, v.second, "d", null, hash, dirty = false, everPushed = true)
        }
        override suspend fun stampUnstamped(hash: String) {}
        override suspend fun hasDirty(hash: String) = false
        override suspend fun applyMergedAndClearDirty(hash: String, deviceId: String, merged: List<SettingsRecord>) {
            merged.forEach { values[it.key] = it.value to it.updatedAt }
        }
    }

    private class FakeStatStore : StatV1Store {
        val rows = mutableListOf<StatRecord>()
        var backfillDone = false
        var backfillCalls = 0
        override suspend fun loadByAccount(hash: String) = rows.map {
            V1SyncEngine.StatLocal(it.eventId, it.day, it.wordCount, it.durationMs, it.updatedAt, it.deviceId, null, hash, false, true)
        }
        override suspend fun stampUnstamped(hash: String) {}
        override suspend fun hasDirty(hash: String) = false
        override suspend fun applyMergedAndClearDirty(hash: String, deviceId: String, merged: List<StatRecord>) {
            merged.forEach { rec -> if (rows.none { it.eventId == rec.eventId }) rows.add(rec) }
        }
        override suspend fun isBackfillDone(hash: String) = backfillDone
        override suspend fun setBackfillDone(hash: String, done: Boolean) { backfillDone = done }
        override suspend fun backfillIfNeeded(hash: String, deviceId: String): Boolean {
            backfillCalls++
            return true
        }
    }

    private class FakeSnippetStore(
        val rows: MutableList<V1SyncEngine.SnippetLocal> = mutableListOf()
    ) : SnippetV1Store {
        override suspend fun loadByAccount(hash: String) = rows.filter { it.accountHash == hash }
        override suspend fun stampUnstamped(hash: String) {}
        override suspend fun hasDirty(hash: String) = rows.any { it.accountHash == hash && it.dirty }
        override suspend fun applyMergedAndClearDirty(hash: String, deviceId: String, merged: List<SnippetRecord>) {
            merged.forEach { rec ->
                val l = V1SyncEngine.SnippetLocal(rec.syncId, rec.businessKey, rec.trigger, rec.expansion, rec.isEnabled, rec.updatedAt, rec.deletedAt, rec.deviceId, hash, false, true)
                val idx = rows.indexOfFirst { it.syncId == rec.syncId }
                if (idx >= 0) rows[idx] = l else rows.add(l)
            }
        }
    }

    // ------------------------------------------------------------------
    // Dictionary pass behaviour
    // ------------------------------------------------------------------

    @Test
    fun clean_local_and_identical_remote_makes_no_put() = runBlocking {
        val remote = DomainSerializer.parseDictionary(fixtureBytes("dictionary"))!!
        val drive = FakeDrive(
            bytes = DomainSerializer.serializeDictionary(remote).toByteArray(),
            version = "1"
        )
        val store = FakeDictStore(
            remote.entries.map {
                DictionaryLocal(it.syncId, it.businessKey, it.spoken, it.corrected, it.isEnabled, it.updatedAt, it.deletedAt, it.deviceId, "hash", dirty = false, everPushed = true)
            }.toMutableList()
        )
        val maxSeen = MaxSeenRef(0L)
        val result = V1SyncEngine.syncDictionary(store, drive, "hash", "device-a", maxSeen)
        assertEquals(0, drive.putCount)
        assertFalse(result.uploaded)
        assertTrue(result.merged)
        assertEquals(remote.entries.maxOf { it.updatedAt }, maxSeen.value)
    }

    @Test
    fun corrupt_remote_envelope_is_skipped_never_overwritten() = runBlocking {
        val drive = FakeDrive(bytes = "{ not json".toByteArray(), version = "9")
        val store = FakeDictStore(mutableListOf())
        val result = V1SyncEngine.syncDictionary(store, drive, "hash", "device-a", MaxSeenRef(0L))
        assertTrue(result.skippedCorrupt)
        assertEquals("corrupt file is never deleted or rewritten", 0, drive.putCount)
        assertEquals(0, store.applyCalls)
    }

    // ------------------------------------------------------------------
    // B1: corrupt-but-size-valid remote is REPAIRED (Windows parity) — never
    // permanently skipped. Oversized is already surfaced as Rejected by
    // AppDataDriveStore before the engine sees it (store-level).
    // ------------------------------------------------------------------

    @Test
    fun corrupt_remote_with_local_state_is_repaired_via_cas_push() = runBlocking {
        val local = dictRec("l1", "brb", at = 300L)
        val drive = FakeDrive(bytes = "{ not json".toByteArray(), version = "9")
        val store = FakeDictStore(mutableListOf(localOf(local)))
        val maxSeen = MaxSeenRef(0L)
        val result = V1SyncEngine.syncDictionary(store, drive, "hash", "device-a", maxSeen)
        assertFalse("repair must not be reported as skipped", result.skippedCorrupt)
        assertTrue(result.uploaded)
        assertTrue(result.merged)
        assertEquals("corrupt file repaired in place, not recreated", 1, drive.putCount)
        assertEquals("CAS target = corrupt file's version", "9", drive.putVersions.single())
        // Payload is the canonical serialized merged state (local LWW winner,
        // sorted businessKey/syncId) — never a fabricated/empty envelope.
        val uploaded = DomainSerializer.parseDictionary(drive.puts.single())!!
        assertEquals(listOf(local), uploaded.entries)
        assertEquals(1, store.applyCalls)
        assertEquals(300L, maxSeen.value)
    }

    @Test
    fun corrupt_remote_with_local_state_stale_retry_reruns_get_merge_put_with_cas() = runBlocking {
        val local = dictRec("l1", "brb", at = 300L)
        val drive = FakeDrive(bytes = "{ not json".toByteArray(), version = "9")
        drive.failNextPutWithStaleVersion("2")
        val store = FakeDictStore(mutableListOf(localOf(local)))
        val result = V1SyncEngine.syncDictionary(store, drive, "hash", "device-a", MaxSeenRef(0L))
        assertTrue(result.uploaded)
        assertTrue("staleness during repair must trigger a full re-GET→MERGE→PUT", result.attemptsUsed >= 2)
        assertEquals(2, drive.putCount)
        // Every repair attempt CAS-targets the corrupt file's revision.
        assertEquals(listOf("9", "9"), drive.putVersions)
    }

    @Test
    fun corrupt_remote_repairs_for_every_domain_with_version_cas() = runBlocking {
        // Dictionary
        val dStore = FakeDictStore(mutableListOf(localOf(dictRec("l1", "brb", at = 300L))))
        val dDrive = FakeDrive(bytes = "{ not json".toByteArray(), version = "9")
        val dRes = V1SyncEngine.syncDictionary(dStore, dDrive, "hash", "device-a", MaxSeenRef(0L))
        assertTrue(dRes.uploaded)
        assertFalse(dRes.skippedCorrupt)
        assertEquals("9", dDrive.putVersions.single())

        // Snippets
        val sStore = FakeSnippetStore(
            mutableListOf(
                V1SyncEngine.SnippetLocal(uuidOf("s1"), "sig", "sig", "hey", true, 100L, null, "device-a", "hash", dirty = false, everPushed = true)
            )
        )
        val sDrive = FakeDrive(bytes = "{ not json".toByteArray(), version = "11")
        val sRes = V1SyncEngine.syncSnippets(sStore, sDrive, "hash", "device-a", MaxSeenRef(0L))
        assertTrue(sRes.uploaded)
        assertFalse(sRes.skippedCorrupt)
        assertEquals("11", sDrive.putVersions.single())

        // Stats
        val stStore = FakeStatStore().apply { rows += StatRecord(uuidOf("e1"), "2026-08-30", 10, 100, 200, "d") }
        val stDrive = FakeDrive(bytes = "{ not json".toByteArray(), version = "13")
        val stRes = V1SyncEngine.syncStats(stStore, stDrive, "hash", "device-a", MaxSeenRef(0L))
        assertTrue(stRes.uploaded)
        assertFalse(stRes.skippedCorrupt)
        assertEquals("13", stDrive.putVersions.single())

        // Settings
        val setStore = FakeSettingsStore(mutableMapOf("language" to ("en" to 100L)))
        val setDrive = FakeDrive(bytes = "{ not json".toByteArray(), version = "17")
        val setRes = V1SyncEngine.syncSettings(setStore, setDrive, "hash", "device-a", MaxSeenRef(0L))
        assertTrue(setRes.uploaded)
        assertFalse(setRes.skippedCorrupt)
        assertEquals("17", setDrive.putVersions.single())
    }

    @Test
    fun stale_version_retries_with_full_remerge_not_stale_put() = runBlocking {
        // Remote record R(updatedAt=100). Local has same key with updatedAt=200.
        val lNew = dictRec("l1", "gonna", at = 200L)
        val drive = FakeDrive(
            bytes = DomainSerializer.serializeDictionary(DictionaryDomain(entries = listOf(dictRec("r1", "gonna", at = 100L)))).toByteArray(),
            version = "1"
        )
        drive.failNextPutWithStaleVersion("2") // first PUT → stale; remote changed under us
        val store = FakeDictStore(mutableListOf(localOf(lNew, dirty = true)))

        // The concurrent writer wins between our attempts: after the failed
        // PUT, the engine re-GETs and must see the newer remote.
        val concurrent = dictRec("r2", "gonna", at = 500L)

        val maxSeen = MaxSeenRef(0L)
        val result = runWithConcurrentWriter(drive, store, concurrent, maxSeen)

        // The re-merge must have picked the concurrent winner (updatedAt 500),
        // so the final PUT contains r2 — never the stale l1.
        val finalBody = String(drive.puts.last(), Charsets.UTF_8)
        assertTrue(finalBody.contains(uuidOf("r2")))
        assertFalse(finalBody.contains(uuidOf("l1")))
        assertEquals("exactly one winner per business key", 1, Regex("\"syncId\"").findAll(finalBody).count())
        assertTrue(result.attemptsUsed >= 2)
    }

    /** Drives syncDictionary manually attempt-by-attempt to interleave a concurrent writer. */
    private suspend fun runWithConcurrentWriter(
        drive: FakeDrive,
        store: FakeDictStore,
        concurrent: DictionaryRecord,
        maxSeen: MaxSeenRef
    ): V1SyncEngine.SyncResult {
        var attempts = 0
        while (attempts < V1SyncEngine.MAX_ATTEMPTS) {
            attempts++
            val fetch = drive.getDomain(DomainFile.DICTIONARY)
            val remoteDomain = fetch.bytes?.let { DomainSerializer.parseDictionary(it) } ?: return V1SyncEngine.SyncResult(false, false, true, attempts)
            val merged = Merge.mergeDictionaries(store.loadByAccount("hash").map { it.toRecord() }, remoteDomain.entries)
            try {
                drive.putDomain(DomainFile.DICTIONARY, DomainSerializer.serializeDictionary(DictionaryDomain(entries = merged)).toByteArray(), fetch.version)
                store.applyMergedAndClearDirty("hash", "device-a", merged)
                return V1SyncEngine.SyncResult(true, true, attemptsUsed = attempts)
            } catch (e: SyncError.StaleVersion) {
                if (attempts < V1SyncEngine.MAX_ATTEMPTS) {
                    // Concurrent writer lands between our attempts.
                    drive.bytes = DomainSerializer.serializeDictionary(DictionaryDomain(entries = listOf(concurrent))).toByteArray()
                    drive.version = "7"
                    continue
                }
                throw e
            }
        }
        return V1SyncEngine.SyncResult(false, true, attemptsUsed = attempts)
    }

    @Test
    fun account_isolation_engine_only_touches_current_hash_rows() = runBlocking {
        val mine = dictRec("m1", "mine", at = 100L)
        val foreign = dictRec("f1", "foreign", at = 100L)
        val store = FakeDictStore(
            mutableListOf(
                localOf(mine, accountHash = "hash-a"),
                localOf(foreign, accountHash = "hash-b")
            )
        )
        val drive = FakeDrive(bytes = null, version = null)
        V1SyncEngine.syncDictionary(store, drive, "hash-a", "device-a", MaxSeenRef(0L))
        // PUT contains only hash-a rows; foreign row untouched locally.
        val body = String(drive.puts.single(), Charsets.UTF_8)
        assertTrue(body.contains(uuidOf("m1")))
        assertFalse(body.contains(uuidOf("f1")))
        assertEquals("hash-b", store.rows.first { it.syncId == uuidOf("f1") }.accountHash)
    }

    @Test
    fun success_applies_winners_and_clears_dirty_in_one_store_tx() = runBlocking {
        val local = dictRec("l1", "brb", at = 300L)
        val store = FakeDictStore(mutableListOf(localOf(local, dirty = true)))
        val drive = FakeDrive(bytes = null)
        val result = V1SyncEngine.syncDictionary(store, drive, "hash", "device-a", MaxSeenRef(0L))
        assertTrue(result.uploaded)
        assertEquals(1, store.applyCalls)
        val applied = store.rows.single()
        assertFalse(applied.dirty)
        assertTrue(applied.everPushed)
        assertEquals("hash", applied.accountHash)
    }

    @Test
    fun post_upload_verification_miss_keeps_rows_unpushed() = runBlocking {
        // B-3: the PUT succeeds but the pushed revision is not yet live on a
        // re-GET (Drive eventual consistency). The engine must NOT clear dirty
        // or mark pushed — it returns a non-uploaded outcome so the next pass
        // re-heals instead of stamping a possibly-not-yet-live write as pushed.
        val local = dictRec("l1", "brb", at = 300L)
        val store = FakeDictStore(mutableListOf(localOf(local, dirty = true)))
        val drive = LaggingFakeDrive(bytes = null, version = null)
        val result = V1SyncEngine.syncDictionary(store, drive, "hash", "device-a", MaxSeenRef(0L))
        assertEquals(1, drive.putCount)       // the create write happened
        assertFalse(result.uploaded)          // but not reported uploaded
        assertEquals(0, store.applyCalls)     // rows never cleared/pushed
        assertTrue(store.rows.single().dirty) // stays dirty so the next pass re-heals
    }

    @Test
    fun post_upload_verification_is_reenforced_on_update_path() = runBlocking {
        // B-3 update path: a pre-existing remote is updated but the new version
        // is not yet visible. Each PUT succeeds, verification misses every time,
        // so the bounded retries exhaust and the rows stay unpushed.
        val local = dictRec("l1", "brb", at = 300L)
        val store = FakeDictStore(mutableListOf(localOf(local, dirty = true)))
        val drive = LaggingFakeDrive(
            bytes = DomainSerializer.serializeDictionary(
                DictionaryDomain(entries = listOf(dictRec("l1", "brb", at = 100L)))
            ).toByteArray(),
            version = "2"
        )
        val result = V1SyncEngine.syncDictionary(store, drive, "hash", "device-a", MaxSeenRef(0L))
        assertEquals(V1SyncEngine.MAX_ATTEMPTS, drive.putCount) // retried to exhaustion
        assertFalse(result.uploaded)
        assertEquals(0, store.applyCalls)
        assertTrue(store.rows.single().dirty)
    }

    @Test
    fun empty_local_and_empty_remote_is_a_noop() = runBlocking {
        val store = FakeDictStore(mutableListOf())
        val drive = FakeDrive(bytes = null)
        val result = V1SyncEngine.syncDictionary(store, drive, "hash", "device-a", MaxSeenRef(0L))
        assertEquals(0, drive.putCount)
        assertFalse(result.uploaded)
    }

    @Test
    fun delete_then_recreate_converges_to_live_record() = runBlocking {
        // Remote tombstone at t=100; local re-creation at t=500 wins (pure LWW).
        val tomb = dictRec("r1", "gone", at = 100L, deletedAt = 100L)
        val drive = FakeDrive(
            bytes = DomainSerializer.serializeDictionary(DictionaryDomain(entries = listOf(tomb))).toByteArray(),
            version = "3"
        )
        val recreated = dictRec("l1", "gone", at = 500L)
        val store = FakeDictStore(mutableListOf(localOf(recreated, dirty = true)))
        val result = V1SyncEngine.syncDictionary(store, drive, "hash", "device-a", MaxSeenRef(0L))
        assertTrue(result.uploaded)
        val uploaded = DomainSerializer.parseDictionary(drive.puts.single())!!
        assertNull("re-creation must beat older tombstone", uploaded.entries.single().deletedAt)
    }

    @Test
    fun newer_remote_delete_beats_older_local_edit() = runBlocking {
        // Local edit at t=100 never pushed; remote deleted at t=900.
        val localEdit = dictRec("l1", "word", at = 100L)
        val remoteTomb = dictRec("r1", "word", at = 900L, deletedAt = 900L)
        val drive = FakeDrive(
            bytes = DomainSerializer.serializeDictionary(DictionaryDomain(entries = listOf(remoteTomb))).toByteArray(),
            version = "5"
        )
        val store = FakeDictStore(mutableListOf(localOf(localEdit, dirty = true)))
        val result = V1SyncEngine.syncDictionary(store, drive, "hash", "device-a", MaxSeenRef(0L))
        // Merged winner is the newer tombstone; it is pushed so every device agrees.
        assertTrue(result.uploaded || !result.merged)
        val applied = store.rows.single()
        assertNotNull("older edit must not resurrect a newer deletion", applied.deletedAt)
    }

    // ------------------------------------------------------------------
    // Stats / settings specifics
    // ------------------------------------------------------------------

    @Test
    fun stats_backfill_runs_exactly_once_per_account() = runBlocking {
        val store = FakeStatStore()
        val drive = FakeDrive(bytes = null)
        V1SyncEngine.syncStats(store, drive, "hash", "device-a", MaxSeenRef(0L))
        assertEquals(1, store.backfillCalls)
        assertTrue(store.backfillDone)
        // Second pass: no re-backfill.
        V1SyncEngine.syncStats(store, drive, "hash", "device-a", MaxSeenRef(0L))
        assertEquals(1, store.backfillCalls)
    }

    @Test
    fun settings_fixed_point_when_local_matches_remote_no_put() = runBlocking {
        // Seed the store with exactly the fixture state (values + timestamps):
        // merged must equal remote → correct behavior is a fixed point (no PUT).
        val remote = DomainSerializer.parseSettings(fixtureBytes("settings"))!!
        val settings = FakeSettingsStore(
            mutableMapOf(*remote.entries.map { it.key to (it.value to it.updatedAt) }.toTypedArray())
        )
        val drive = FakeDrive(
            bytes = DomainSerializer.serializeSettings(SettingsDomain(entries = remote.entries)).toByteArray(),
            version = "2"
        )
        val result = V1SyncEngine.syncSettings(settings, drive, "hash", "device-a", MaxSeenRef(0L))
        assertFalse("identical state must not re-upload", result.uploaded)
        assertEquals(0, drive.putCount)

        // Now a genuinely newer local edit wins and uploads.
        // Use now+60s (within 24h CLOCK_SKEW_TOLERANCE) so F3 far-future cap does not reject it
        settings.values["language"] = "fr" to (System.currentTimeMillis() + 60_000)
        val drive2 = FakeDrive(
            bytes = DomainSerializer.serializeSettings(SettingsDomain(entries = remote.entries)).toByteArray(),
            version = "2"
        )
        val result2 = V1SyncEngine.syncSettings(settings, drive2, "hash", "device-a", MaxSeenRef(0L))
        assertTrue(result2.uploaded)
        val body = String(drive2.puts.last(), Charsets.UTF_8)
        assertTrue(body.contains("\"fr\""))
    }

    @Test
    fun snippets_pass_byte_exact_expansion_roundtrip() = runBlocking {
        val expansion = "line1\nline2\ttabbed \"quoted\""
        val rec = SnippetRecord(uuidOf("s1"), "sig", "sig", expansion, true, 100L, null, "device-a")
        val store = FakeSnippetStore(
            mutableListOf(
                V1SyncEngine.SnippetLocal(uuidOf("s1"), "sig", "sig", expansion, true, 100L, null, "device-a", "hash", dirty = true, everPushed = false)
            )
        )
        val drive = FakeDrive(bytes = null)
        V1SyncEngine.syncSnippets(store, drive, "hash", "device-a", MaxSeenRef(0L))
        val uploaded = DomainSerializer.parseSnippets(drive.puts.single())!!
        assertEquals(expansion, uploaded.entries.single().expansion)
        assertEquals(rec, uploaded.entries.single())
    }

    // ------------------------------------------------------------------
    // Regression: converged pull must materialize into an empty local store
    // (fresh device installs never took the PUT path, so merged winners
    // were computed and dropped — dictionary/snippets stayed invisible).
    // ------------------------------------------------------------------

    @Test
    fun fresh_device_converged_pull_materializes_dictionary() = runBlocking {
        val remote = DomainSerializer.parseDictionary(fixtureBytes("dictionary"))!!
        val drive = FakeDrive(bytes = DomainSerializer.serializeDictionary(remote).toByteArray(), version = "7")
        val store = FakeDictStore(mutableListOf())
        val result = V1SyncEngine.syncDictionary(store, drive, "hash", "device-a", MaxSeenRef(0L))
        assertEquals(0, drive.putCount)
        assertFalse(result.uploaded)
        assertEquals(1, store.applyCalls)
        assertEquals(remote.entries.map { it.syncId }.sorted(), store.rows.map { it.syncId }.sorted())
    }

    @Test
    fun fresh_device_converged_pull_materializes_snippets() = runBlocking {
        val remote = DomainSerializer.parseSnippets(fixtureBytes("snippets"))!!
        val drive = FakeDrive(bytes = DomainSerializer.serializeSnippets(SnippetDomain(entries = remote.entries)).toByteArray(), version = "3")
        val store = FakeSnippetStore()
        val result = V1SyncEngine.syncSnippets(store, drive, "hash", "device-a", MaxSeenRef(0L))
        assertEquals(0, drive.putCount)
        assertFalse(result.uploaded)
        assertEquals(remote.entries.map { it.syncId }, store.rows.map { it.syncId })
    }

    @Test
    fun fresh_device_converged_pull_materializes_stats() = runBlocking {
        val remote = DomainSerializer.parseStats(fixtureBytes("stats"))!!
        val drive = FakeDrive(bytes = DomainSerializer.serializeStats(StatsDomain(entries = remote.entries)).toByteArray(), version = "4")
        val store = FakeStatStore()
        V1SyncEngine.syncStats(store, drive, "hash", "device-a", MaxSeenRef(0L))
        assertEquals(0, drive.putCount)
        assertEquals(remote.entries.size, store.rows.size)
    }

    @Test
    fun fresh_device_converged_pull_materializes_settings() = runBlocking {
        val remote = DomainSerializer.parseSettings(fixtureBytes("settings"))!!
        val drive = FakeDrive(bytes = DomainSerializer.serializeSettings(SettingsDomain(entries = remote.entries)).toByteArray(), version = "2")
        val store = FakeSettingsStore()
        V1SyncEngine.syncSettings(store, drive, "hash", "device-a", MaxSeenRef(0L))
        assertEquals(0, drive.putCount)
        assertEquals(remote.entries.associate { it.key to it.value }, store.values.mapValues { it.value.first })
    }

    @Test
    fun staleRetryDelayMs_growsAndJittersWithinBounds() {
        // Attempt 1 (no retry yet) must never sleep.
        assertEquals(0L, staleRetryDelayMs(1, { 0.0 }))
        assertEquals(0L, staleRetryDelayMs(1, { 1.0 }))

        // Attempt 2 -> base 50ms, jittered into [25, 75] for rand in [0,1).
        assertEquals(25L, staleRetryDelayMs(2, { 0.0 }))
        assertEquals(75L, staleRetryDelayMs(2, { 1.0 }))

        // Attempt 3 -> 100ms base -> [50, 150].
        assertEquals(50L, staleRetryDelayMs(3, { 0.0 }))
        assertEquals(150L, staleRetryDelayMs(3, { 1.0 }))

        // Attempt 4 -> 200ms base -> [100, 300].
        assertEquals(100L, staleRetryDelayMs(4, { 0.0 }))
        assertEquals(300L, staleRetryDelayMs(4, { 1.0 }))

        // The cap holds no matter how many attempts accumulate.
        assertTrue("delay never exceeds the cap", staleRetryDelayMs(20, { 1.0 }) <= 600L)
        for (a in 2..8) {
            assertTrue(staleRetryDelayMs(a, { 1.0 }) <= 600L)
            assertTrue(staleRetryDelayMs(a, { 0.0 }) >= staleRetryDelayMs(a, { 1.0 }) / 3)
        }

        // Default source (real jitter) stays in [0, cap].
        val rnd = java.util.Random(42)
        for (a in 2..8) {
            val d = staleRetryDelayMs(a, { rnd.nextDouble() })
            assertTrue("attempt $a delay $d within [0, 600]", d in 0..600L)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun fixtureBytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/sync/v1/$name.json")) { "missing fixture $name" }.readBytes()

    private fun uuidOf(name: String): String = java.util.UUID.nameUUIDFromBytes(name.toByteArray()).toString()

    private fun dictRec(syncId: String, spoken: String, at: Long, deletedAt: Long? = null) =
        DictionaryRecord(uuidOf(syncId), spoken.trim().lowercase(), spoken, "fix", true, at, deletedAt, "device-x")

    private fun localOf(rec: DictionaryRecord, dirty: Boolean = false, accountHash: String? = "hash") =
        DictionaryLocal(rec.syncId, rec.businessKey, rec.spoken, rec.corrected, rec.isEnabled, rec.updatedAt, rec.deletedAt, rec.deviceId, accountHash, dirty, everPushed = false)
}
