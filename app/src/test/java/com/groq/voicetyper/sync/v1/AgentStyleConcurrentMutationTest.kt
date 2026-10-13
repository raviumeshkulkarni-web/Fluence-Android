package com.groq.voicetyper.sync.v1

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Audit finding H5 - a local Agent/Style mutation that lands while a sync pass
 * is on the network must survive that pass.
 *
 * `syncAgents` snapshots the local store BEFORE the Drive round trip and writes
 * the merge result back afterwards. `applyMergedAndClearDirty` used to write
 * that result wholesale, so anything the user changed in between was destroyed:
 * a row created during a pass ended up in no store at all, and a concurrent
 * edit or delete was silently reverted.
 *
 * These tests drive the REAL `AccountAgentV1Store` / `AccountStyleV1Store` over
 * the in-memory prefs fake and hand them the *stale* payload a pass would have
 * produced before the mutation - which is precisely the lost-update window.
 * (The engine entry point is not used here because `stampUnstamped` resolves a
 * device id through EncryptedSharedPreferences, which needs a keystore the
 * prefs fake cannot provide.)
 *
 * The rule is the same one Windows applies in `AgentDirtyStore::save_merged`,
 * so both platforms converge.
 */
class AgentStyleConcurrentMutationTest {

    private val hash = "a".repeat(64)
    private val deviceId = "dev-local"

    /** A valid business key: the record validator requires `agent:<uuid>`. */
    private fun agentId() = "agent:${UUID.randomUUID()}"

    /** The style counterpart. */
    private fun styleId() = "custom:${UUID.randomUUID()}"

    /**
     * The deterministic stable sync id, mirroring `V1Stores.stableSyncId`
     * (private there), so a seeded row matches what production would stamp.
     */
    private fun stableAgentSyncId(id: String) =
        UUID.nameUUIDFromBytes(("fluence-agent:$id").toByteArray()).toString()

    private fun stableStyleSyncId(id: String) =
        UUID.nameUUIDFromBytes(("fluence-style:$id").toByteArray()).toString()

    private fun agentRow(
        id: String,
        name: String,
        hint: String = "hint",
        updatedAt: Long? = 1_000L,
        deletedAt: Long? = null,
        dirty: Boolean = false,
    ) = AccountScope.AccountAgent(
        id = id, name = name, hint = hint,
        syncId = stableAgentSyncId(id), updatedAt = updatedAt,
        deviceId = deviceId, deletedAt = deletedAt, dirty = dirty,
    )

    /** A wire record as the pass would have produced it from that row. */
    private fun agentRecord(row: AccountScope.AccountAgent) = AgentRecord(
        syncId = row.syncId!!,
        businessKey = row.id,
        name = row.name,
        hint = row.hint,
        updatedAt = row.updatedAt!!,
        deletedAt = row.deletedAt,
        deviceId = row.deviceId!!,
    )

    private fun seedAgent(ctx: android.content.Context, vararg rows: AccountScope.AccountAgent) {
        AccountScope.saveAgents(ctx, hash, rows.toList())
    }

    private fun agentNamed(ctx: android.content.Context, id: String) =
        AccountScope.loadAgents(ctx, hash).firstOrNull { it.id == id }

    // ---------------------------------------------------------------- create

    @Test
    fun agent_created_after_the_snapshot_survives_the_merge_writeback() = runBlocking {
        val ctx = FakePrefsStore().context()
        val seeded = agentId()
        val created = agentId()
        val seedRow = agentRow(seeded, "Original", "old hint")
        seedAgent(ctx, seedRow)

        // The pass snapshotted the seeded row only...
        val stalePayload = listOf(agentRecord(seedRow))

        // ...and the user creates a new agent while the pass is on the network.
        AccountScope.upsertAgent(ctx, hash, created, "Brand New", "typed while syncing")

        V1Stores.AccountAgentV1Store(ctx)
            .applyMergedAndClearDirty(hash, deviceId, stalePayload)

        val row = agentNamed(ctx, created)
        assertNotNull("a row created during the pass must survive it", row)
        assertEquals("Brand New", row!!.name)
        assertTrue("and must stay dirty so the next pass uploads it", row.dirty)
        assertNotNull("the pre-existing row must survive too", agentNamed(ctx, seeded))
    }

    // ------------------------------------------------------------------ edit

    @Test
    fun agent_edited_after_the_snapshot_survives_the_merge_writeback() = runBlocking {
        val ctx = FakePrefsStore().context()
        val id = agentId()
        val seedRow = agentRow(id, "Original", "old hint")
        seedAgent(ctx, seedRow)

        val stalePayload = listOf(agentRecord(seedRow))

        AccountScope.upsertAgent(ctx, hash, id, "Edited Mid Pass", "new hint")

        V1Stores.AccountAgentV1Store(ctx)
            .applyMergedAndClearDirty(hash, deviceId, stalePayload)

        val row = agentNamed(ctx, id)
        assertNotNull("row must survive", row)
        assertEquals(
            "the edit made during the pass must win over the older snapshot",
            "Edited Mid Pass", row!!.name
        )
        assertTrue("and it must be queued for the next push", row.dirty)
    }

    // ---------------------------------------------------------------- delete

    @Test
    fun agent_deleted_after_the_snapshot_survives_the_merge_writeback() = runBlocking {
        val ctx = FakePrefsStore().context()
        val id = agentId()
        val seedRow = agentRow(id, "Original", "old hint")
        seedAgent(ctx, seedRow)

        val stalePayload = listOf(agentRecord(seedRow))

        assertTrue(
            "the delete must be durably written during the pass",
            AccountScope.deleteAgent(ctx, hash, id)
        )

        V1Stores.AccountAgentV1Store(ctx)
            .applyMergedAndClearDirty(hash, deviceId, stalePayload)

        val row = agentNamed(ctx, id)
        assertNotNull("the tombstone row must remain", row)
        assertTrue(
            "the delete must survive, not be resurrected by the older payload",
            row!!.deletedAt != null
        )
        assertTrue("and the tombstone must still be uploaded", row.dirty)
    }

    // ------------------------------------------------- remote still wins (LWW)

    @Test
    fun a_newer_remote_record_still_replaces_the_local_row() = runBlocking {
        val ctx = FakePrefsStore().context()
        val id = agentId()
        seedAgent(ctx, agentRow(id, "Original", "old hint", updatedAt = 1_000L))

        // A peer edited the same record with a newer revision; the payload
        // therefore carries the peer's content and the local row is CLEAN.
        val peer = agentRecord(
            agentRow(id, "Remote Wins", "remote hint", updatedAt = 9_000_000_000L)
                .copy(deviceId = "dev-peer")
        )
        V1Stores.AccountAgentV1Store(ctx)
            .applyMergedAndClearDirty(hash, deviceId, listOf(peer))

        val row = agentNamed(ctx, id)
        assertNotNull("row must exist", row)
        assertEquals(
            "a newer remote record must still replace the local row",
            "Remote Wins", row!!.name
        )
        assertTrue(
            "a row that lost LWW must not stay dirty, or every pass re-uploads it",
            !row.dirty
        )
    }

    // ------------------------------- convergence: no perpetual-dirty re-upload

    @Test
    fun a_row_equal_to_the_payload_is_written_back_clean() = runBlocking {
        // This is the anti-regression guard for the reconcile: once a row's
        // content EQUALS what the pass uploaded, keeping it "dirty" would
        // re-upload it on every pass forever. The winning row must go clean.
        val ctx = FakePrefsStore().context()
        val id = agentId()
        val row = agentRow(id, "Converged", "hint", dirty = true)
        seedAgent(ctx, row)

        val payload = listOf(agentRecord(row))

        V1Stores.AccountAgentV1Store(ctx)
            .applyMergedAndClearDirty(hash, deviceId, payload)

        val settled = agentNamed(ctx, id)
        assertNotNull(settled)
        assertEquals("Converged", settled!!.name)
        assertTrue(
            "once uploaded, the row must be clean or every pass re-uploads it",
            !settled.dirty
        )
    }

    @Test
    fun a_preserved_edit_is_cleaned_once_a_later_pass_carries_it() = runBlocking {
        // Full two-pass shape: pass 1 preserves the edit (dirty kept), pass 2
        // delivers a payload containing it and must clear the flag.
        val ctx = FakePrefsStore().context()
        val id = agentId()
        val seedRow = agentRow(id, "Original", "old hint")
        seedAgent(ctx, seedRow)

        V1Stores.AccountAgentV1Store(ctx)
            .applyMergedAndClearDirty(hash, deviceId, listOf(agentRecord(seedRow)))

        AccountScope.upsertAgent(ctx, hash, id, "Converged", "hint")
        val store = V1Stores.AccountAgentV1Store(ctx)
        store.applyMergedAndClearDirty(hash, deviceId, listOf(agentRecord(seedRow)))

        val preserved = agentNamed(ctx, id)
        assertEquals("Converged", preserved!!.name)
        assertTrue("pass 1 preserves the edit and keeps it queued", preserved.dirty)

        // Pass 2 uploads it and writes it back clean.
        store.applyMergedAndClearDirty(
            hash, deviceId,
            listOf(agentRecord(preserved))
        )
        assertTrue(
            "pass 2 must clear the flag once the content is uploaded",
            !agentNamed(ctx, id)!!.dirty
        )
    }

    // ------------------------------------------------------- competing writes

    @Test
    fun competing_local_mutations_in_one_window_all_survive() = runBlocking {
        val ctx = FakePrefsStore().context()
        val seeded = agentId()
        val a = agentId()
        val b = agentId()
        val c = agentId()
        val seedRow = agentRow(seeded, "Original", "old hint")
        seedAgent(ctx, seedRow)

        val stalePayload = listOf(agentRecord(seedRow))

        // Three mutations, all inside the same pre-writeback window.
        AccountScope.upsertAgent(ctx, hash, a, "A", "ha")
        AccountScope.upsertAgent(ctx, hash, b, "B", "hb")
        AccountScope.upsertAgent(ctx, hash, c, "C", "hc")

        V1Stores.AccountAgentV1Store(ctx)
            .applyMergedAndClearDirty(hash, deviceId, stalePayload)

        for ((id, name) in listOf(a to "A", b to "B", c to "C")) {
            val row = agentNamed(ctx, id)
            assertNotNull("$name must survive the competing window", row)
            assertEquals(name, row!!.name)
            assertTrue("$name must still be queued for upload", row.dirty)
        }
        assertNotNull("the pre-existing row must survive too", agentNamed(ctx, seeded))
    }

    // ------------------------------------------------------ styles share it

    @Test
    fun style_created_after_the_snapshot_survives_the_merge_writeback() = runBlocking {
        val ctx = FakePrefsStore().context()
        val seeded = styleId()
        val created = styleId()
        val seedRow = AccountScope.AccountStyle(
            id = seeded, name = "Seed", hint = "h",
            syncId = stableStyleSyncId(seeded), updatedAt = 1_000L,
            deviceId = deviceId,
        )
        AccountScope.saveStyles(ctx, hash, listOf(seedRow))

        val stalePayload = listOf(
            StyleRecord(
                syncId = seedRow.syncId!!, businessKey = seeded, name = "Seed",
                hint = "h", updatedAt = 1_000L, deletedAt = null, deviceId = deviceId,
            )
        )

        AccountScope.upsertStyle(ctx, hash, created, "Brand New Style", "typed while syncing")

        V1Stores.AccountStyleV1Store(ctx)
            .applyMergedAndClearDirty(hash, deviceId, stalePayload)

        val styles = AccountScope.loadStyles(ctx, hash)
        val row = styles.firstOrNull { it.id == created }
        assertNotNull("a style created during the pass must survive it", row)
        assertEquals("Brand New Style", row!!.name)
        assertTrue("and stay dirty for the next push", row.dirty)
        assertEquals("the pre-existing style must survive too", 2, styles.size)
    }

    @Test
    fun a_never_pushed_tombstone_with_no_counterpart_is_purged() = runBlocking {
        // A tombstone for a record that has NO counterpart in the payload
        // (created and deleted while sync was off) carries nothing a peer could
        // need, so it is dropped - identical to the Windows store. The row here
        // has no syncId because it was never pushed.
        val ctx = FakePrefsStore().context()
        val id = agentId()
        AccountScope.saveAgents(
            ctx, hash,
            listOf(
                AccountScope.AccountAgent(
                    id = id, name = "Ghost", hint = "h",
                    syncId = null, updatedAt = null, deviceId = null, dirty = true,
                )
            )
        )
        AccountScope.deleteAgent(ctx, hash, id)

        V1Stores.AccountAgentV1Store(ctx)
            .applyMergedAndClearDirty(hash, deviceId, emptyList())

        assertNull(
            "a never-pushed tombstone with no remote counterpart is dropped",
            agentNamed(ctx, id)
        )
    }

    @Test
    fun a_pushed_tombstone_is_never_purged_even_with_an_empty_payload() = runBlocking {
        // The opposite guard: a tombstone whose record HAS been pushed must
        // survive, or the delete is silently lost and the row comes back.
        val ctx = FakePrefsStore().context()
        val id = agentId()
        AccountScope.saveAgents(ctx, hash, listOf(agentRow(id, "Original", "old hint")))
        AccountScope.deleteAgent(ctx, hash, id)

        V1Stores.AccountAgentV1Store(ctx)
            .applyMergedAndClearDirty(hash, deviceId, emptyList())

        val row = agentNamed(ctx, id)
        assertNotNull("a pushed tombstone must not be dropped", row)
        assertTrue("and must still carry the delete", row!!.deletedAt != null)
    }
}
