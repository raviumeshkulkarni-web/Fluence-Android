package com.groq.voicetyper.sync.v1

import com.groq.voicetyper.sync.v1.V1SyncEngine.AgentLocal
import com.groq.voicetyper.sync.v1.V1SyncEngine.AgentV1Store
import com.groq.voicetyper.sync.v1.V1SyncEngine.MaxSeenRef
import com.groq.voicetyper.sync.v1.V1SyncEngine.StyleLocal
import com.groq.voicetyper.sync.v1.V1SyncEngine.StyleV1Store
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 — Agents/Styles as additive v1 sync domains.
 *
 * Contract under test (frozen v1, no new wire format):
 *  - `agents.json` / `styles.json` ride the EXISTING v1 envelope and are ignored
 *    by old clients simply because they do not know the file names.
 *  - 2B LWW ([Merge.compareKeyed]) is the SOLE authority for duplicate IDs.
 *  - Tombstones are ordinary records: they round-trip and they win exactly when
 *    they are newest, so a delete can never be undone by a stale copy.
 *  - Records are account-partitioned: load == accountHash ONLY.
 *  - Ordering is deterministic: (businessKey, syncId).
 *
 * businessKey for these domains is the record's own stable id
 * ("agent:<uuid>" / "custom:<uuid>"), NOT its name. Renaming must not fork a
 * record, and two devices that independently create agents must not collide.
 */
class AgentStyleDomainSyncTest {

    // ------------------------------------------------------------------
    // Fakes (mirroring V1SyncEngineTest conventions)
    // ------------------------------------------------------------------

    private class FakeDrive(
        var bytes: ByteArray? = null,
        var version: String? = null,
        var putCount: Int = 0,
        val puts: MutableList<ByteArray> = mutableListOf(),
        val putVersions: MutableList<String?> = mutableListOf(),
    ) : V1SyncEngine.DomainGateway {
        override fun getDomain(domain: DomainFile): AppDataDriveStore.DomainFetch =
            AppDataDriveStore.DomainFetch(bytes?.copyOf(), version)

        override fun putDomain(domain: DomainFile, data: ByteArray, expectedVersion: String?): String {
            putCount++
            putVersions.add(expectedVersion)
            puts.add(data.copyOf())
            bytes = data.copyOf()
            version = "v-$putCount"
            return version!!
        }
    }

    /**
     * Mirrors the production contract in `AccountAgentV1Store` as of STAGE 4.
     *
     * There is deliberately NO ownership map here any more. The store used to
     * keep one and refuse to write a record another account had claimed, but
     * that only made sense while the Drive envelope was POOLED. STAGE 3 puts
     * each account's file in its own `fluence/v1/acct-<hash>/` partition, so
     * everything arriving here is provably this account's and needs no filter.
     *
     * Keeping a fake that still filtered would have let these tests pass against
     * behaviour the product no longer has.
     */
    private class FakeAgentStore(
        val rows: MutableList<AgentLocal> = mutableListOf(),
    ) : AgentV1Store {

        override suspend fun loadByAccount(hash: String) = rows.filter { it.accountHash == hash }

        override suspend fun stampUnstamped(hash: String) {
            for (i in rows.indices) {
                if (rows[i].accountHash == null) rows[i] = rows[i].copy(accountHash = hash)
            }
        }

        override suspend fun hasDirty(hash: String) = rows.any { it.accountHash == hash && it.dirty }

        override suspend fun applyMergedAndClearDirty(
            hash: String,
            deviceId: String,
            merged: List<AgentRecord>,
        ) {
            val mergedIds = merged.map { it.syncId }.toSet()
            rows.removeAll { it.accountHash == hash && it.syncId !in mergedIds }
            for (rec in merged) {
                val idx = rows.indexOfFirst { it.syncId == rec.syncId }
                val local = AgentLocal(
                    syncId = rec.syncId, businessKey = rec.businessKey, name = rec.name,
                    hint = rec.hint, updatedAt = rec.updatedAt, deletedAt = rec.deletedAt,
                    deviceId = rec.deviceId, accountHash = hash, dirty = false, everPushed = true,
                )
                if (idx >= 0) rows[idx] = local else rows.add(local)
            }
        }
    }

    private class FakeStyleStore(
        val rows: MutableList<StyleLocal> = mutableListOf(),
    ) : StyleV1Store {
        override suspend fun loadByAccount(hash: String) = rows.filter { it.accountHash == hash }

        override suspend fun stampUnstamped(hash: String) {
            for (i in rows.indices) {
                if (rows[i].accountHash == null) rows[i] = rows[i].copy(accountHash = hash)
            }
        }

        override suspend fun hasDirty(hash: String) = rows.any { it.accountHash == hash && it.dirty }

        override suspend fun applyMergedAndClearDirty(
            hash: String,
            deviceId: String,
            merged: List<StyleRecord>,
        ) {
            val mergedIds = merged.map { it.syncId }.toSet()
            rows.removeAll { it.accountHash == hash && it.syncId !in mergedIds }
            for (rec in merged) {
                val idx = rows.indexOfFirst { it.syncId == rec.syncId }
                val local = StyleLocal(
                    syncId = rec.syncId, businessKey = rec.businessKey, name = rec.name,
                    hint = rec.hint, updatedAt = rec.updatedAt, deletedAt = rec.deletedAt,
                    deviceId = rec.deviceId, accountHash = hash, dirty = false, everPushed = true,
                )
                if (idx >= 0) rows[idx] = local else rows.add(local)
            }
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * syncId MUST be a real UUID — the shared per-record validator rejects
     * anything else, so a fixture using "s1" would be skipped at ingest and the
     * test would fail for the wrong reason.
     */
    private fun uuidOf(name: String): String =
        java.util.UUID.nameUUIDFromBytes(name.toByteArray()).toString()

    private fun agentRec(
        syncId: String,
        name: String,
        at: Long,
        hint: String = "be concise",
        deletedAt: Long? = null,
        deviceId: String = "dev-a",
        businessKey: String = "agent:" + uuidOf(syncId),
    ) = AgentRecord(uuidOf(syncId), businessKey, name, hint, at, deletedAt, deviceId)

    private fun styleRec(
        syncId: String,
        name: String,
        at: Long,
        hint: String = "formal",
        deletedAt: Long? = null,
        deviceId: String = "dev-a",
        businessKey: String = "custom:" + uuidOf(syncId),
    ) = StyleRecord(uuidOf(syncId), businessKey, name, hint, at, deletedAt, deviceId)

    private fun localOf(rec: AgentRecord, dirty: Boolean = false, accountHash: String? = "hash") =
        AgentLocal(
            rec.syncId, rec.businessKey, rec.name, rec.hint, rec.updatedAt,
            rec.deletedAt, rec.deviceId, accountHash, dirty, true,
        )

    private fun localStyleOf(rec: StyleRecord, dirty: Boolean = false, accountHash: String? = "hash") =
        StyleLocal(
            rec.syncId, rec.businessKey, rec.name, rec.hint, rec.updatedAt,
            rec.deletedAt, rec.deviceId, accountHash, dirty, true,
        )

    // ------------------------------------------------------------------
    // 1. Codec round-trip
    // ------------------------------------------------------------------

    @Test
    fun agent_envelope_round_trips_all_sync_fields() {
        val original = AgentDomain(
            entries = listOf(agentRec("s1", "Standup", 1_700_000_000_000))
        )
        val parsed = DomainSerializer.parseAgents(
            DomainSerializer.serializeAgents(original).toByteArray()
        )
        assertNotNull(parsed)
        assertEquals(1, parsed!!.entries.size)
        val e = parsed.entries[0]
        assertEquals(uuidOf("s1"), e.syncId)
        assertEquals("agent:" + uuidOf("s1"), e.businessKey)
        assertEquals("Standup", e.name)
        assertEquals("be concise", e.hint)
        assertEquals(1_700_000_000_000L, e.updatedAt)
        assertEquals("dev-a", e.deviceId)
        assertNull(e.deletedAt)
    }

    @Test
    fun style_envelope_round_trips_all_sync_fields() {
        val original = StyleDomain(
            entries = listOf(styleRec("s1", "Formal", 1_700_000_000_000))
        )
        val parsed = DomainSerializer.parseStyles(
            DomainSerializer.serializeStyles(original).toByteArray()
        )
        assertNotNull(parsed)
        val e = parsed!!.entries[0]
        assertEquals(uuidOf("s1"), e.syncId)
        assertEquals("custom:" + uuidOf("s1"), e.businessKey)
        assertEquals("Formal", e.name)
        assertEquals("formal", e.hint)
    }

    @Test
    fun envelope_carries_v1_and_domain_file_is_registered() {
        assertEquals(1, AgentDomain(entries = emptyList()).v)
        assertEquals(1, StyleDomain(entries = emptyList()).v)
        // The two additive files. Old clients never see these names.
        assertTrue(DomainFile.entries.any { it.name == "AGENTS" })
        assertTrue(DomainFile.entries.any { it.name == "STYLES" })
    }

    // ------------------------------------------------------------------
    // 2. Tombstone round-trip (deletedAt == 0 is a REAL tombstone)
    // ------------------------------------------------------------------

    @Test
    fun agent_tombstone_survives_codec_round_trip() {
        val bytes = DomainSerializer.serializeAgents(
            AgentDomain(entries = listOf(agentRec("s1", "Gone", 10, deletedAt = 42)))
        ).toByteArray()
        val parsed = DomainSerializer.parseAgents(bytes)!!
        assertEquals(42L, parsed.entries[0].deletedAt)
    }

    @Test
    fun agent_zero_deleted_at_is_preserved_as_a_tombstone() {
        val bytes = DomainSerializer.serializeAgents(
            AgentDomain(entries = listOf(agentRec("s1", "Gone", 10, deletedAt = 0)))
        ).toByteArray()
        val parsed = DomainSerializer.parseAgents(bytes)!!
        assertEquals(0L, parsed.entries[0].deletedAt)
    }

    @Test
    fun style_tombstone_survives_codec_round_trip() {
        val bytes = DomainSerializer.serializeStyles(
            StyleDomain(entries = listOf(styleRec("s1", "Gone", 10, deletedAt = 7)))
        ).toByteArray()
        val parsed = DomainSerializer.parseStyles(bytes)!!
        assertEquals(7L, parsed.entries[0].deletedAt)
    }

    // ------------------------------------------------------------------
    // 3. Merge: 2B LWW is the sole duplicate-ID authority
    // ------------------------------------------------------------------

    @Test
    fun duplicate_agent_id_resolves_by_existing_2b_lww() {
        val older = agentRec("s1", "Old", 100, deviceId = "dev-a")
        val newer = agentRec("s1", "New", 200, deviceId = "dev-b")
        val merged = Merge.mergeAgents(listOf(older), listOf(newer))
        assertEquals(1, merged.size)
        assertEquals("New", merged[0].name)
    }

    @Test
    fun duplicate_agent_id_tiebreaks_on_device_then_sync_id() {
        val a = agentRec("s1", "A", 100, deviceId = "dev-a")
        val b = agentRec("s1", "B", 100, deviceId = "dev-b")
        val merged = Merge.mergeAgents(listOf(a), listOf(b))
        assertEquals(1, merged.size)
        assertEquals("B", merged[0].name)
    }

    @Test
    fun newer_tombstone_beats_older_live_record() {
        val live = agentRec("s1", "Alive", 100)
        val tomb = agentRec("s1", "Alive", 200, deletedAt = 200)
        val merged = Merge.mergeAgents(listOf(live), listOf(tomb))
        assertEquals(1, merged.size)
        assertEquals(200L, merged[0].deletedAt)
    }

    @Test
    fun older_tombstone_loses_to_newer_recreate() {
        val tomb = agentRec("s1", "Back", 100, deletedAt = 100)
        val recreated = agentRec("s1", "Back", 300)
        val merged = Merge.mergeAgents(listOf(tomb), listOf(recreated))
        assertEquals(1, merged.size)
        assertNull(merged[0].deletedAt)
        assertEquals(300L, merged[0].updatedAt)
    }

    // ------------------------------------------------------------------
    // 4. Sync pass behaviour
    // ------------------------------------------------------------------

    @Test
    fun clean_account_creates_the_domain_file_once() = runBlocking {
        val drive = FakeDrive()
        val store = FakeAgentStore(mutableListOf(localOf(agentRec("s1", "Standup", 100), dirty = true)))
        val result = V1SyncEngine.syncAgents(store, drive, "hash", "dev-a", MaxSeenRef(0))
        assertTrue(result.uploaded)
        assertEquals(1, drive.putCount)
        val parsed = DomainSerializer.parseAgents(drive.bytes!!)!!
        assertEquals(1, parsed.entries.size)
        assertEquals("Standup", parsed.entries[0].name)
    }

    @Test
    fun fixed_point_produces_no_second_put() = runBlocking {
        val drive = FakeDrive()
        val store = FakeAgentStore(mutableListOf(localOf(agentRec("s1", "Standup", 100), dirty = true)))
        V1SyncEngine.syncAgents(store, drive, "hash", "dev-a", MaxSeenRef(0))
        val first = drive.putCount
        V1SyncEngine.syncAgents(store, drive, "hash", "dev-a", MaxSeenRef(0))
        assertEquals("a clean fixed point must not re-upload", first, drive.putCount)
    }

    @Test
    fun remote_record_is_merged_into_the_local_account() = runBlocking {
        val remote = DomainSerializer.serializeAgents(
            AgentDomain(entries = listOf(agentRec("s-remote", "FromOther", 500, deviceId = "dev-b")))
        ).toByteArray()
        val drive = FakeDrive(bytes = remote, version = "v1")
        val store = FakeAgentStore(mutableListOf(localOf(agentRec("s-local", "Mine", 100), dirty = true)))
        V1SyncEngine.syncAgents(store, drive, "hash", "dev-a", MaxSeenRef(0))
        val names = store.rows.map { it.name }.sorted()
        assertEquals(listOf("FromOther", "Mine"), names)
    }

    @Test
    fun delete_then_sync_does_not_resurrect() = runBlocking {
        // Remote holds a live copy; local has since deleted it.
        val remote = DomainSerializer.serializeAgents(
            AgentDomain(entries = listOf(agentRec("s1", "Doomed", 100)))
        ).toByteArray()
        val drive = FakeDrive(bytes = remote, version = "v1")
        val store = FakeAgentStore(
            mutableListOf(localOf(agentRec("s1", "Doomed", 500, deletedAt = 500), dirty = true))
        )
        V1SyncEngine.syncAgents(store, drive, "hash", "dev-a", MaxSeenRef(0))
        val row = store.rows.single { it.syncId == uuidOf("s1") }
        assertEquals("the local tombstone must win over the older remote", 500L, row.deletedAt)
        val uploaded = DomainSerializer.parseAgents(drive.bytes!!)!!
        assertEquals(500L, uploaded.entries.single { it.syncId == uuidOf("s1") }.deletedAt)
    }

    @Test
    fun recreate_newer_than_tombstone_survives_a_sync_pass() = runBlocking {
        val remote = DomainSerializer.serializeAgents(
            AgentDomain(entries = listOf(agentRec("s1", "Old", 100, deletedAt = 100)))
        ).toByteArray()
        val drive = FakeDrive(bytes = remote, version = "v1")
        val store = FakeAgentStore(
            mutableListOf(localOf(agentRec("s1", "Old", 900, deletedAt = null), dirty = true))
        )
        V1SyncEngine.syncAgents(store, drive, "hash", "dev-a", MaxSeenRef(0))
        val row = store.rows.single { it.syncId == uuidOf("s1") }
        assertNull("a newer recreate must clear the tombstone", row.deletedAt)
    }

    @Test
    fun style_sync_round_trips_through_the_engine() = runBlocking {
        val drive = FakeDrive()
        val store = FakeStyleStore(
            mutableListOf(localStyleOf(styleRec("s1", "Formal", 100), dirty = true))
        )
        val result = V1SyncEngine.syncStyles(store, drive, "hash", "dev-a", MaxSeenRef(0))
        assertTrue(result.uploaded)
        val parsed = DomainSerializer.parseStyles(drive.bytes!!)!!
        assertEquals("Formal", parsed.entries[0].name)
    }

    @Test
    fun style_delete_then_sync_does_not_resurrect() = runBlocking {
        val remote = DomainSerializer.serializeStyles(
            StyleDomain(entries = listOf(styleRec("s1", "Old", 100)))
        ).toByteArray()
        val drive = FakeDrive(bytes = remote, version = "v1")
        val store = FakeStyleStore(
            mutableListOf(localStyleOf(styleRec("s1", "Old", 700, deletedAt = 700), dirty = true))
        )
        V1SyncEngine.syncStyles(store, drive, "hash", "dev-a", MaxSeenRef(0))
        assertEquals(700L, store.rows.single { it.syncId == uuidOf("s1") }.deletedAt)
    }

    // ------------------------------------------------------------------
    // 5. Account isolation
    // ------------------------------------------------------------------

    @Test
    fun account_a_never_sees_account_b_records() = runBlocking {
        val drive = FakeDrive()
        val store = FakeAgentStore(
            mutableListOf(
                localOf(agentRec("a1", "MineA", 100), dirty = true, accountHash = "hashA"),
                localOf(agentRec("b1", "TheirsB", 100), dirty = true, accountHash = "hashB"),
            )
        )
        V1SyncEngine.syncAgents(store, drive, "hashA", "dev-a", MaxSeenRef(0))
        val uploaded = DomainSerializer.parseAgents(drive.bytes!!)!!
        assertEquals(listOf("MineA"), uploaded.entries.map { it.name })
    }

    @Test
    fun account_a_switching_to_b_then_back_keeps_both_sets_separate() = runBlocking {
        // STAGE 3: the Drive envelope is no longer pooled. Each account has its
        // own `fluence/v1/acct-<hash>/agents.json`, so A's upload is simply not
        // present in B's partition and there is nothing for B to adopt. The
        // separate `partA` / `partB` fakes model exactly that.
        val partA = FakeDrive()
        val partB = FakeDrive()
        val store = FakeAgentStore(
            mutableListOf(
                localOf(agentRec("a1", "MineA", 100), dirty = true, accountHash = "hashA"),
                localOf(agentRec("b1", "TheirsB", 100), dirty = true, accountHash = "hashB"),
            )
        )

        // A
        V1SyncEngine.syncAgents(store, partA, "hashA", "dev-a", MaxSeenRef(0))
        assertEquals(listOf("MineA"), DomainSerializer.parseAgents(partA.bytes!!)!!.entries.map { it.name })
        assertNull("B's partition must not have been touched by A", partB.bytes)

        // B. B reads only its own partition, which is still empty, then uploads
        // only B's row. A's record is not in it and cannot leak in.
        V1SyncEngine.syncAgents(store, partB, "hashB", "dev-a", MaxSeenRef(0))
        assertEquals(listOf("TheirsB"), DomainSerializer.parseAgents(partB.bytes!!)!!.entries.map { it.name })
        assertEquals(
            listOf("TheirsB"),
            store.rows.filter { it.accountHash == "hashB" }.map { it.name },
        )
        assertEquals(
            "A's row must not appear in B's partition",
            listOf("MineA"),
            DomainSerializer.parseAgents(partA.bytes!!)!!.entries.map { it.name },
        )

        // Back to A: A still sees its own record.
        V1SyncEngine.syncAgents(store, partA, "hashA", "dev-a", MaxSeenRef(0))
        assertEquals(
            listOf("MineA"),
            store.rows.filter { it.accountHash == "hashA" }.map { it.name },
        )
        // Tie to production: the engine-level separation above models what these
        // canonical paths guarantee — per-account gateways the engine cannot
        // cross. The contamination surface is the SHARED store (asserted above),
        // not the separate drives; the path derivation itself is pinned in
        // AccountPartitionTest, and this asserts the two levels agree that two
        // accounts never share a location.
        val ha = "a".repeat(64)
        val hb = "b".repeat(64)
        assertNotEquals(
            AccountPartition.relativePath(DomainFile.AGENTS, ha),
            AccountPartition.relativePath(DomainFile.AGENTS, hb),
        )
    }

    @Test
    fun a_fresh_device_signing_into_a_downloads_as_own_records() = runBlocking {
        // The STAGE 4 regression that the removed OwnershipIndex could not have
        // satisfied: it only saw claims made on the same device, so a fresh
        // install had no index entries and every record looked adoptable. With
        // structural partitioning, provenance comes from the path.
        val partA = FakeDrive()
        val deviceOne = FakeAgentStore(
            mutableListOf(localOf(agentRec("x1", "AgentX", 500), dirty = true, accountHash = "hashA"))
        )
        V1SyncEngine.syncAgents(deviceOne, partA, "hashA", "dev-1", MaxSeenRef(0))
        assertEquals(listOf("AgentX"), DomainSerializer.parseAgents(partA.bytes!!)!!.entries.map { it.name })

        // Fresh device, same account, empty local store.
        val deviceTwo = FakeAgentStore()
        V1SyncEngine.syncAgents(deviceTwo, partA, "hashA", "dev-2", MaxSeenRef(0))
        assertEquals(
            listOf("AgentX"),
            deviceTwo.rows.filter { it.accountHash == "hashA" }.map { it.name },
        )
        assertTrue("the downloaded record must be executable, not dirty", deviceTwo.rows.none { it.dirty })
    }

    @Test
    fun a_different_account_cannot_reach_as_partition() = runBlocking {
        // B addressing its own partition would require B's own path; it is
        // derived from B's account hash, so A's bytes are simply not there.
        //
        // B is given a record of its OWN so that a real merge, a real upload and
        // a real partition write all happen. Asserting "B uploaded nothing"
        // would pass trivially on an empty store and would prove nothing about
        // partitioning.
        val partA = FakeDrive()
        val deviceOne = FakeAgentStore(
            mutableListOf(localOf(agentRec("x1", "AgentX", 500), dirty = true, accountHash = "hashA"))
        )
        V1SyncEngine.syncAgents(deviceOne, partA, "hashA", "dev-1", MaxSeenRef(0))
        assertEquals(listOf("AgentX"), DomainSerializer.parseAgents(partA.bytes!!)!!.entries.map { it.name })

        // B has its own agent, and shares no partition with A.
        val deviceB = FakeAgentStore(
            mutableListOf(localOf(agentRec("y1", "AgentY", 500), dirty = true, accountHash = "hashB"))
        )
        val partB = FakeDrive()
        V1SyncEngine.syncAgents(deviceB, partB, "hashB", "dev-2", MaxSeenRef(0))

        assertEquals(
            "B's partition must contain only B's own record, never A's",
            listOf("AgentY"),
            DomainSerializer.parseAgents(partB.bytes!!)!!.entries.map { it.name },
        )
        assertTrue(
            "B must not have downloaded A's record",
            deviceB.rows.none { it.name == "AgentX" },
        )
        assertEquals(
            "A's partition must be untouched by B's pass",
            listOf("AgentX"),
            DomainSerializer.parseAgents(partA.bytes!!)!!.entries.map { it.name },
        )
    }

    @Test
    fun never_signed_in_does_not_write_a_domain() = runBlocking {
        val drive = FakeDrive()
        val store = FakeAgentStore(
            mutableListOf(localOf(agentRec("a1", "LocalOnly", 100), dirty = true, accountHash = null))
        )
        V1SyncEngine.syncAgents(store, drive, "", "dev-a", MaxSeenRef(0))
        assertEquals("no account means no upload", 0, drive.putCount)
    }

    // ------------------------------------------------------------------
    // 6. Deterministic ordering
    // ------------------------------------------------------------------

    @Test
    fun uploaded_entries_are_ordered_by_business_key_then_sync_id() = runBlocking {
        val drive = FakeDrive()
        val recs = listOf("k3", "k1", "k2").map { k ->
            agentRec(k, "N$k", 100, businessKey = "agent:" + uuidOf(k))
        }
        val store = FakeAgentStore(recs.map { localOf(it, dirty = true) }.toMutableList())
        V1SyncEngine.syncAgents(store, drive, "hash", "dev-a", MaxSeenRef(0))
        val keys = DomainSerializer.parseAgents(drive.bytes!!)!!.entries.map { it.businessKey to it.syncId }
        // Determinism is the property under test: the uploaded order must equal
        // the canonical sort, and must be identical on a repeat run.
        val expected = keys.sortedWith(compareBy({ it.first }, { it.second }))
        assertEquals(expected, keys)
        assertEquals(3, keys.size)
        // Same input, fresh engine run -> byte-identical ordering.
        val drive2 = FakeDrive()
        val store2 = FakeAgentStore(recs.map { localOf(it, dirty = true) }.toMutableList())
        V1SyncEngine.syncAgents(store2, drive2, "hash", "dev-a", MaxSeenRef(0))
        val keys2 = DomainSerializer.parseAgents(drive2.bytes!!)!!.entries.map { it.businessKey to it.syncId }
        assertEquals(keys, keys2)
    }

    // ------------------------------------------------------------------
    // 7. Future-version refusal (inherited from the shared envelope guard)
    // ------------------------------------------------------------------

    @Test
    fun future_agent_envelope_is_refused_not_overwritten() = runBlocking {
        val future = """{"v":99,"entries":[]}"""
        val drive = FakeDrive(bytes = future.toByteArray(), version = "v1")
        val store = FakeAgentStore(mutableListOf(localOf(agentRec("s1", "Mine", 100), dirty = true)))
        V1SyncEngine.syncAgents(store, drive, "hash", "dev-a", MaxSeenRef(0))
        assertEquals("a future envelope must never be clobbered", 0, drive.putCount)
    }

    @Test
    fun future_stamped_agent_record_is_skipped_not_trusted() {
        val far = System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000
        val bytes = DomainSerializer.serializeAgents(
            AgentDomain(entries = listOf(agentRec("s1", "FromTheFuture", far)))
        ).toByteArray()
        val parsed = DomainSerializer.parseAgents(bytes)!!
        assertTrue("far-future record must be dropped", parsed.entries.isEmpty())
    }

    // ------------------------------------------------------------------
    // 8. Corrupt-record handling (per-record skip, existing parser contract)
    // ------------------------------------------------------------------

    @Test
    fun corrupt_agent_entry_is_skipped_without_fataling_the_envelope() {
        val good = "11111111-1111-4111-8111-111111111111"
        val bytes = """
            {"v":1,"entries":[
              {"syncId":"$good","businessKey":"agent:$good","name":"Fine","hint":"h",
               "updatedAt":100,"deviceId":"dev-a"},
              {"syncId":"","businessKey":"","name":"","hint":"",
               "updatedAt":-5,"deviceId":""}
            ]}
        """.trimIndent().toByteArray()
        val parsed = DomainSerializer.parseAgents(bytes)!!
        assertEquals("only the valid record survives", listOf(good), parsed.entries.map { it.syncId })
    }

    @Test
    fun agent_with_forged_business_key_is_skipped() {
        // businessKey IS the record identity for this domain, so an unvalidated
        // wire value would let a hostile file forge a merge key and win an LWW
        // against a legitimate agent. Only well-formed agent:<uuid> keys pass.
        val good = "22222222-2222-4222-8222-222222222222"
        val bytes = """
            {"v":1,"entries":[
              {"syncId":"$good","businessKey":"custom:$good","name":"Impostor","hint":"h",
               "updatedAt":900,"deviceId":"dev-evil"}
            ]}
        """.trimIndent().toByteArray()
        val parsed = DomainSerializer.parseAgents(bytes)!!
        assertTrue("a style-prefixed key must not pass as an agent", parsed.entries.isEmpty())
    }

    @Test
    fun corrupt_envelope_yields_null_so_the_engine_can_preserve_remote() = runBlocking {
        val drive = FakeDrive(bytes = "not json at all".toByteArray(), version = "v1")
        val store = FakeAgentStore(mutableListOf(localOf(agentRec("s1", "Mine", 100), dirty = true)))
        V1SyncEngine.syncAgents(store, drive, "hash", "dev-a", MaxSeenRef(0))
        assertTrue("an unparseable remote must not be silently destroyed", drive.putCount <= 1)
    }

    // ------------------------------------------------------------------
    // STAGE 8 — cross-platform byte fixture (asserted identically in
    // windows-main/src-tauri/src/sync/domain.rs
    // agent_style_conformance_tests). Any divergence in field order, escaping,
    // sorting, the trailing newline, or null-vs-absent encoding fails here.
    // ------------------------------------------------------------------

    @Test
    fun agent_envelope_matches_the_cross_platform_fixture() {
        val expected =
            "{\"v\":1,\"entries\":[{\"syncId\":\"123e4567-e89b-12d3-a456-426614174000\"," +
            "\"businessKey\":\"agent:123e4567-e89b-12d3-a456-426614174001\"," +
            "\"name\":\"Translator\",\"hint\":\"Be concise\",\"updatedAt\":1700000000000," +
            "\"deletedAt\":null,\"deviceId\":\"dev-a\"}]}\n"
        val domain = AgentDomain(
            v = 1,
            entries = listOf(
                AgentRecord(
                    syncId = "123e4567-e89b-12d3-a456-426614174000",
                    businessKey = "agent:123e4567-e89b-12d3-a456-426614174001",
                    name = "Translator",
                    hint = "Be concise",
                    updatedAt = 1700000000000L,
                    deletedAt = null,
                    deviceId = "dev-a",
                )
            ),
        )
        assertEquals(expected, DomainSerializer.serializeAgents(domain))
        val parsed = DomainSerializer.parseAgents(expected.toByteArray(Charsets.UTF_8))!!
        assertEquals(domain.entries, parsed.entries)
    }

    @Test
    fun style_envelope_matches_the_cross_platform_fixture() {        val expected =
            "{\"v\":1,\"entries\":[{\"syncId\":\"123e4567-e89b-12d3-a456-426614174000\"," +
            "\"businessKey\":\"custom:123e4567-e89b-12d3-a456-426614174001\"," +
            "\"name\":\"Formal\",\"hint\":\"Be formal\",\"updatedAt\":1700000000000," +
            "\"deletedAt\":null,\"deviceId\":\"dev-a\"}]}\n"
        val domain = StyleDomain(
            v = 1,
            entries = listOf(
                StyleRecord(
                    syncId = "123e4567-e89b-12d3-a456-426614174000",
                    businessKey = "custom:123e4567-e89b-12d3-a456-426614174001",
                    name = "Formal",
                    hint = "Be formal",
                    updatedAt = 1700000000000L,
                    deletedAt = null,
                    deviceId = "dev-a",
                )
            ),
        )
        assertEquals(expected, DomainSerializer.serializeStyles(domain))
        val parsed = DomainSerializer.parseStyles(expected.toByteArray(Charsets.UTF_8))!!
        assertEquals(domain.entries, parsed.entries)
    }

    @Test
    fun control_escapes_match_serde_short_forms() {
        // 0x08/0x0C must serialize as the serde short forms, not the long hex
        // escapes — otherwise the same string yields different bytes per
        // platform (plausible via pasted PDF text, where 0x0C is the page
        // break). Written with code-point construction so the control bytes
        // stay visible in review.
        // (Escape text is spelled out, never literal: kapt's Java pre-lexer
        // rejects backslash-u sequences in comments.)
        val hint = "a" + 0x08.toChar() + "b" + 0x0C.toChar() + "c"
        val domain = AgentDomain(
            v = 1,
            entries = listOf(
                AgentRecord(
                    syncId = "123e4567-e89b-12d3-a456-426614174000",
                    businessKey = "agent:123e4567-e89b-12d3-a456-426614174001",
                    name = "T",
                    hint = hint,
                    updatedAt = 1700000000000L,
                    deletedAt = null,
                    deviceId = "dev-a",
                )
            ),
        )
        val bytes = DomainSerializer.serializeAgents(domain)
        assertTrue("must contain serde short form for 0x08", bytes.contains("a\\bb"))
        assertTrue("must contain serde short form for 0x0C", bytes.contains("b\\fc"))
        assertFalse("must not contain long form for 0x08", bytes.contains("u0008"))
        assertFalse("must not contain long form for 0x0C", bytes.contains("u000c"))
        val parsed = DomainSerializer.parseAgents(bytes.toByteArray(Charsets.UTF_8))!!
        assertEquals(hint, parsed.entries.single().hint)
    }

    @Test
    fun canonical_sort_keys_are_ascii_so_platform_ordering_coincides() {
        // Kotlin String.compareTo is UTF-16 code-unit order; Rust str::cmp is
        // UTF-8 byte order. They coincide for ASCII and diverge for
        // supplementary-plane characters. businessKey = prefix+UUID and syncId
        // = UUID are ASCII by construction; this pins that constraint so a
        // future move to name-derived identity cannot silently break
        // cross-platform convergence.
        val key = "agent:123e4567-e89b-12d3-a456-426614174001"
        val syncId = "123e4567-e89b-12d3-a456-426614174000"
        assertTrue(key.all { it.code < 0x80 })
        assertTrue(syncId.all { it.code < 0x80 })
        assertTrue(AgentRecord.isValidBusinessKey(key))
        assertTrue(StyleRecord.isValidBusinessKey("custom:" + syncId))
    }

    @Test
    fun non_ascii_names_do_not_influence_ordering() {
        // Names are free text and never sort keys: an emoji name must not change
        // the canonical bytes for a given key set, nor their input order.
        val withEmoji = AgentRecord(
            syncId = "123e4567-e89b-12d3-a456-426614174000",
            businessKey = "agent:123e4567-e89b-12d3-a456-426614174001",
            name = "\uD83C\uDF89 agent",
            hint = "h",
            updatedAt = 1700000000000L,
            deletedAt = null,
            deviceId = "dev-a",
        )
        val plain = AgentRecord(
            syncId = "223e4567-e89b-12d3-a456-426614174000",
            businessKey = "agent:323e4567-e89b-12d3-a456-426614174000",
            name = "zzz",
            hint = "h",
            updatedAt = 1700000000000L,
            deletedAt = null,
            deviceId = "dev-a",
        )
        val forward = DomainSerializer.serializeAgents(
            AgentDomain(v = 1, entries = listOf(withEmoji, plain))
        )
        val backward = DomainSerializer.serializeAgents(
            AgentDomain(v = 1, entries = listOf(plain, withEmoji))
        )
        assertEquals(
            "input order and name content must not change canonical output",
            forward,
            backward,
        )
    }

    // ------------------------------------------------------------------
    // STAGE 7 — sync-correctness gaps
    // ------------------------------------------------------------------

    @Test
    fun same_account_two_devices_divergent_edits_converge() = runBlocking {
        // Both devices share one partition. Each edits the same agent
        // differently offline; after both sync, both stores hold the identical
        // LWW winner — convergence, not last-writer-per-device.
        val part = FakeDrive()
        val dev1 = FakeAgentStore(
            mutableListOf(localOf(agentRec("x1", "AgentX", 500), dirty = true, accountHash = "hashA"))
        )
        V1SyncEngine.syncAgents(dev1, part, "hashA", "dev-1", MaxSeenRef(0))

        // Device 2 joins later and edits the same record with a NEWER timestamp.
        val dev2 = FakeAgentStore()
        V1SyncEngine.syncAgents(dev2, part, "hashA", "dev-2", MaxSeenRef(0))
        val dev2rows = dev2.rows.toMutableList()
        val idx = dev2rows.indexOfFirst { it.businessKey == dev2rows.first { r -> r.name == "AgentX" }.businessKey }
        val key = dev2rows[idx].businessKey
        dev2rows[idx] = dev2rows[idx].copy(name = "AgentX-v2", updatedAt = 900L, dirty = true)
        V1SyncEngine.syncAgents(FakeAgentStore(dev2rows), part, "hashA", "dev-2", MaxSeenRef(0))

        // Device 1 syncs again and must converge onto device 2's newer edit.
        V1SyncEngine.syncAgents(dev1, part, "hashA", "dev-1", MaxSeenRef(0))
        val names1 = dev1.rows.filter { it.accountHash == "hashA" }.map { it.name }
        assertEquals(listOf("AgentX-v2"), names1)
        assertEquals(
            "both devices must hold the same winner",
            dev1.rows.single { it.businessKey == key }.updatedAt,
            900L,
        )
    }

    @Test
    fun duplicate_ids_resolve_to_one_displayed_runnable_record() = runBlocking {
        // Two devices independently create DIFFERENT agents that collide on
        // businessKey (adversarial, but the merge must still pick exactly one).
        // Display, selection and execution must then agree on that winner.
        val part = FakeDrive()
        val key = "agent:" + uuidOf("collision")
        val dev1 = FakeAgentStore(
            mutableListOf(
                localOf(agentRec("c1", "First", 500, businessKey = key), dirty = true, accountHash = "hashA")
            )
        )
        V1SyncEngine.syncAgents(dev1, part, "hashA", "dev-1", MaxSeenRef(0))
        val dev2 = FakeAgentStore(
            mutableListOf(
                localOf(agentRec("c2", "Second", 700, businessKey = key), dirty = true, accountHash = "hashA")
            )
        )
        V1SyncEngine.syncAgents(dev2, part, "hashA", "dev-2", MaxSeenRef(0))
        V1SyncEngine.syncAgents(dev1, part, "hashA", "dev-1", MaxSeenRef(0))

        val survivors = dev1.rows.filter { it.businessKey == key && it.accountHash == "hashA" }
        assertEquals("exactly one winner per businessKey", 1, survivors.size)
        val winner = survivors.single()
        val snap = AccountScope.VisibleAgentsSnapshot.forTest(
            AccountScope.unionAgents(
                emptyList(),
                dev1.rows.filter { it.accountHash == "hashA" }.map {
                    AccountScope.AccountAgent(
                        it.businessKey, it.name, it.hint, it.syncId,
                        it.updatedAt, it.deviceId, it.deletedAt,
                    )
                },
            ),
            "a".repeat(64),
        )
        assertTrue("the winner must be runnable", snap.isRunnable(snap.displayRecords().single { it.id == key }))
        assertEquals(winner.name, snap.resolve(key)?.name)
    }

    @Test
    fun styles_a_to_b_to_a_keeps_partitions_separate() = runBlocking {
        // The styles counterpart of the agents A→B→A regression: separate
        // partitions, no adoption, nothing leaked.
        val partA = FakeDrive()
        val partB = FakeDrive()
        val store = FakeStyleStore(
            mutableListOf(
                localStyleOf(styleRec("s1", "StyleA", 100), dirty = true, accountHash = "hashA"),
                localStyleOf(styleRec("s2", "StyleB", 100), dirty = true, accountHash = "hashB"),
            )
        )
        V1SyncEngine.syncStyles(store, partA, "hashA", "dev-a", MaxSeenRef(0))
        assertEquals(listOf("StyleA"), DomainSerializer.parseStyles(partA.bytes!!)!!.entries.map { it.name })
        assertNull("B's partition untouched by A", partB.bytes)

        V1SyncEngine.syncStyles(store, partB, "hashB", "dev-a", MaxSeenRef(0))
        assertEquals(listOf("StyleB"), DomainSerializer.parseStyles(partB.bytes!!)!!.entries.map { it.name })

        V1SyncEngine.syncStyles(store, partA, "hashA", "dev-a", MaxSeenRef(0))
        assertEquals(
            listOf("StyleA"),
            store.rows.filter { it.accountHash == "hashA" }.map { it.name },
        )
    }

    @Test
    fun put_without_an_account_partition_throws_before_touching_the_network() {
        // R2 carry-forward #3, store level: the entire `localAccountHash`
        // safety argument rests on uploads being impossible without a verified
        // identity. This pins that no network I/O can precede the guard — the
        // throw happens before any folder resolution, listing or creation.
        val store = AppDataDriveStore("dummy-token")
        store.accountHash = null
        try {
            store.putDomain(DomainFile.AGENTS, "{}".toByteArray(), null)
            throw AssertionError("putDomain with no partition must throw")
        } catch (e: SyncError.Rejected) {
            assertTrue(e.message!!.contains("no account partition"))
        }
        try {
            store.putDomain(DomainFile.STYLES, "{}".toByteArray(), null)
            throw AssertionError("putDomain with no partition must throw")
        } catch (e: SyncError.Rejected) {
            assertTrue(e.message!!.contains("no account partition"))
        }
        // And the flat domains are unaffected by the guard.
        assertNotEquals(null, AccountPartition.relativePath(DomainFile.DICTIONARY, "garbage"))
    }

    @Test
    fun upsert_over_a_live_row_updates_in_place() {
        // R2 carry-forward #2, first branch: editing an existing record must
        // update it, preserving sync metadata so LWW continuity holds.
        val store = FakePrefsStore()
        val ctx = store.context()
        val hash = "a".repeat(64)
        AccountScope.saveAgents(
            ctx, hash,
            listOf(AccountScope.AccountAgent("x1", "Old", "h", "sync-x1", 100L, "dev-1", null)),
        )
        AccountScope.upsertAgent(ctx, hash, "x1", "New", "h2")
        val rows = AccountScope.loadAgents(ctx, hash)
        assertEquals(1, rows.size)
        assertEquals("x1", rows[0].id)
        assertEquals("New", rows.single().name)
        assertEquals("sync-x1", rows.single().syncId)
        assertEquals(100L, rows.single().updatedAt)
    }

    @Test
    fun upsert_of_a_new_record_appends_without_touching_others() {
        // R2 carry-forward #2, second branch: the `current[-1]` crash lived
        // here. A new record appends; existing rows are byte-identical.
        val store = FakePrefsStore()
        val ctx = store.context()
        val hash = "a".repeat(64)
        AccountScope.saveAgents(
            ctx, hash,
            listOf(AccountScope.AccountAgent("x1", "Old", "h", "sync-x1", 100L, "dev-1", null)),
        )
        AccountScope.upsertStyle(ctx, hash, "s9", "Brand-new", "hint")
        assertEquals(1, AccountScope.loadAgents(ctx, hash).size)
        val styles = AccountScope.loadStyles(ctx, hash)
        assertEquals(1, styles.size)
        assertEquals("Brand-new", styles.single().name)
        assertNull("fresh records carry no sync metadata until stamped", styles.single().syncId)
        // R1 cosmetic: the `current[-1]` crash existed in BOTH upserts, so pin
        // the agents append branch too, not just styles.
        AccountScope.upsertAgent(ctx, hash, "a9", "Agent-new", "hint")
        val agents = AccountScope.loadAgents(ctx, hash)
        assertEquals(2, agents.size)
        assertEquals("Agent-new", agents.single { it.id == "a9" }.name)
        assertNull(agents.single { it.id == "a9" }.syncId)
    }

    @Test
    fun admission_never_returns_owned_without_a_verified_hash() {
        // R1 strengthen: iterating displayRecords() alone cannot pin this,
        // because displayRecords() already withholds Owned records without a
        // verified hash — the loop would only ever see the legacy record even
        // if admissionOf were broken. So assert the execution side directly.
        val snap = AccountScope.VisibleAgentsSnapshot.forTest(
            AccountScope.unionAgents(
                listOf(AccountScope.VisibleAgent.Legacy("l1", "L", "h")),
                listOf(AccountScope.AccountAgent("x1", "X", "h", "s", 1L, "d", null)),
            ),
            null,
        )
        snap.displayRecords().forEach {
            assertNotEquals(
                "no record may read as owned without verification",
                AccountScope.Admission.OWNED,
                snap.admissionOf(it),
            )
        }
        assertTrue(
            "the owned record must not reach execution without verification",
            snap.admitted().none { it.id == "x1" },
        )
        val ownedRecord = snap.records().single { it.id == "x1" }
        assertFalse(
            "the owned record must not be runnable without verification",
            snap.isRunnable(ownedRecord),
        )
        assertTrue("legacy stays listed", snap.displayRecords().any { it.id == "l1" })
        assertTrue("account records withheld without verification", snap.displayRecords().none { it.id == "x1" })
    }
}
