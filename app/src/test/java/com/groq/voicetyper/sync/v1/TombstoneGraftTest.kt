package com.groq.voicetyper.sync.v1

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C3 — the tombstone graft in the real `applyMergedAndClearDirty` store path.
 *
 * `merged` is already the LWW-resolved winner set, so each `rec` IS the winning
 * record and already carries the correct tombstone state. The code used to add
 *
 *     deletedAt = rec.deletedAt ?: prior?.deletedAt
 *
 * which grafted a LOSING local `deletedAt` onto the WINNING record. A newer
 * live winner was then persisted as a tombstone that no device ever produced.
 * The local side wins a timestamp tie, so later passes keep re-PUTting that
 * fabricated tombstone, and it can reach the peer and delete a record there.
 *
 * These tests drive the production store —
 * `V1Stores.AccountAgentV1Store.applyMergedAndClearDirty` and its style
 * counterpart — and read persistence back through `AccountScope`. Winner
 * selection is delegated to the production `Merge` object; no merge rule is
 * reimplemented here.
 */
class TombstoneGraftTest {

    private val email = "test@example.com"
    private val hash = AccountHash.of(email)!!
    private val agentKey = "agent:${"a".repeat(32)}"
    private val styleKey = "custom:${"b".repeat(32)}"
    private val device = "device-b"

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun seedAgent(
        store: FakePrefsStore,
        updatedAt: Long,
        deletedAt: Long?,
        name: String = "OldName",
        hint: String = "old hint"
    ) = AccountScope.saveAgents(
        store.context(), hash,
        listOf(
            AccountScope.AccountAgent(
                id = agentKey, name = name, hint = hint,
                syncId = "sync-a", updatedAt = updatedAt,
                deviceId = "device-a", deletedAt = deletedAt, dirty = false
            )
        )
    )

    private fun seedStyle(
        store: FakePrefsStore,
        updatedAt: Long,
        deletedAt: Long?,
        name: String = "OldStyle",
        hint: String = "old hint"
    ) = AccountScope.saveStyles(
        store.context(), hash,
        listOf(
            AccountScope.AccountStyle(
                id = styleKey, name = name, hint = hint,
                syncId = "sync-a", updatedAt = updatedAt,
                deviceId = "device-a", deletedAt = deletedAt, dirty = false
            )
        )
    )

    /** The persisted row, projected onto the wire record type (no merge logic). */
    private fun localAgents(store: FakePrefsStore): List<AgentRecord> =
        AccountScope.loadAgents(store.context(), hash).map {
            AgentRecord(
                syncId = it.syncId!!, businessKey = it.id, name = it.name, hint = it.hint,
                updatedAt = it.updatedAt!!, deletedAt = it.deletedAt, deviceId = it.deviceId!!
            )
        }

    private fun localStyles(store: FakePrefsStore): List<StyleRecord> =
        AccountScope.loadStyles(store.context(), hash).map {
            StyleRecord(
                syncId = it.syncId!!, businessKey = it.id, name = it.name, hint = it.hint,
                updatedAt = it.updatedAt!!, deletedAt = it.deletedAt, deviceId = it.deviceId!!
            )
        }

    /** The peer's record for the same business key. */
    private fun remoteAgent(updatedAt: Long, deletedAt: Long?) = AgentRecord(
        syncId = "sync-b", businessKey = agentKey, name = "NewName", hint = "new hint",
        updatedAt = updatedAt, deletedAt = deletedAt, deviceId = device
    )

    private fun remoteStyle(updatedAt: Long, deletedAt: Long?) = StyleRecord(
        syncId = "sync-b", businessKey = styleKey, name = "NewStyle", hint = "new hint",
        updatedAt = updatedAt, deletedAt = deletedAt, deviceId = device
    )

    private fun storedAgent(store: FakePrefsStore) = AccountScope.loadAgents(store.context(), hash).single()

    private fun storedStyle(store: FakePrefsStore) = AccountScope.loadStyles(store.context(), hash).single()

    /**
     * Asserts nothing is queued for upload.
     *
     * The production gate is `toLocal(...).dirty`, computed as
     * `dirty || syncId == null || updatedAt == null`, and `hasDirty` is just
     * `loadByAccount(...).any { it.dirty }`. `hasDirty` cannot be called here:
     * it routes through `DeviceIdProvider`, which prefers
     * `EncryptedSharedPreferences`; against a keystore-less fake context that
     * half-initialises instead of throwing, so it fails later inside Tink rather
     * than taking the documented plain fallback. Asserting the three persisted
     * inputs pins the identical invariant without the keystore.
     */
    private fun assertNothingQueuedForUpload(store: FakePrefsStore) {
        for (row in AccountScope.loadAgents(store.context(), hash)) {
            assertFalse("a merged agent must not be queued for upload", row.dirty)
            assertNotNull("a null syncId re-uploads the row every pass", row.syncId)
            assertNotNull("a null updatedAt re-uploads the row every pass", row.updatedAt)
        }
        for (row in AccountScope.loadStyles(store.context(), hash)) {
            assertFalse("a merged style must not be queued for upload", row.dirty)
            assertNotNull("a null syncId re-uploads the row every pass", row.syncId)
            assertNotNull("a null updatedAt re-uploads the row every pass", row.updatedAt)
        }
    }

    // ==================================================================
    // A. Newer live beats older tombstone
    // ==================================================================

    @Test
    fun agents_newer_live_beats_older_tombstone_and_uploads_nothing() = runBlocking {
        val store = FakePrefsStore()
        // Persisted local row is a tombstone at T1...
        seedAgent(store, updatedAt = 100, deletedAt = 100)
        val live = remoteAgent(updatedAt = 200, deletedAt = null)

        // ...and the newer live record wins the production merge.
        val merged = Merge.mergeAgents(localAgents(store), listOf(live))
        assertEquals(200L, merged.single().updatedAt)
        assertNull("precondition: the winner is live", merged.single().deletedAt)

        V1Stores.agentStore(store.context()).applyMergedAndClearDirty(hash, device, merged)

        val row = storedAgent(store)
        assertNull(
            "the losing local tombstone must NOT be grafted onto the winning live record",
            row.deletedAt
        )
        assertEquals("winner name must be preserved", "NewName", row.name)
        assertEquals("winner hint must be preserved", "new hint", row.hint)
        assertEquals("winner updatedAt must be preserved", 200L, row.updatedAt)
        assertEquals("winner deviceId must be preserved", device, row.deviceId)
        assertEquals("winner syncId must be preserved", "sync-b", row.syncId)
        assertNothingQueuedForUpload(store)

        // Second pass with the SAME unchanged remote winner: a fixed point.
        val again = Merge.mergeAgents(localAgents(store), listOf(live))
        V1Stores.agentStore(store.context()).applyMergedAndClearDirty(hash, device, again)

        assertNull(
            "no fabricated tombstone may appear on the second pass",
            storedAgent(store).deletedAt
        )
        assertNothingQueuedForUpload(store)
    }

    // ==================================================================
    // B. Newer tombstone beats older live
    // ==================================================================

    @Test
    fun agents_newer_tombstone_beats_older_live_and_stays_deleted() = runBlocking {
        val store = FakePrefsStore()
        seedAgent(store, updatedAt = 100, deletedAt = null)
        val tombstone = remoteAgent(updatedAt = 200, deletedAt = 200)

        val merged = Merge.mergeAgents(localAgents(store), listOf(tombstone))
        V1Stores.agentStore(store.context()).applyMergedAndClearDirty(hash, device, merged)

        assertEquals("the newer tombstone must win", 200L, storedAgent(store).deletedAt)
        assertNothingQueuedForUpload(store)

        val again = Merge.mergeAgents(localAgents(store), listOf(tombstone))
        V1Stores.agentStore(store.context()).applyMergedAndClearDirty(hash, device, again)

        assertEquals(
            "the tombstone must be stable across an unchanged pass",
            200L, storedAgent(store).deletedAt
        )
        assertNothingQueuedForUpload(store)
    }

    // ==================================================================
    // C. No graft / no resurrection — the graft proven impossible
    // ==================================================================

    /**
     * The graft stated directly: a losing local tombstone must never survive
     * onto a winning live record.
     */
    @Test
    fun a_losing_local_tombstone_is_never_grafted_onto_a_winning_live_record() = runBlocking {
        val store = FakePrefsStore()
        seedAgent(store, updatedAt = 100, deletedAt = 100)

        val merged = Merge.mergeAgents(localAgents(store), listOf(remoteAgent(200, null)))
        V1Stores.agentStore(store.context()).applyMergedAndClearDirty(hash, device, merged)

        assertEquals(
            "the persisted tombstone state must equal the WINNER's, not the loser's",
            merged.single().deletedAt,
            storedAgent(store).deletedAt
        )
    }

    /**
     * The reverse graft: a losing remote tombstone must not delete a winning
     * newer local record.
     */
    @Test
    fun a_losing_remote_tombstone_never_deletes_a_winning_newer_local_record() = runBlocking {
        val store = FakePrefsStore()
        seedAgent(store, updatedAt = 200, deletedAt = null)

        val merged = Merge.mergeAgents(localAgents(store), listOf(remoteAgent(100, 100)))
        V1Stores.agentStore(store.context()).applyMergedAndClearDirty(hash, device, merged)

        assertNull(
            "an older remote tombstone must not delete a newer local record",
            storedAgent(store).deletedAt
        )
    }

    // ==================================================================
    // Styles — same law, same store
    // ==================================================================

    @Test
    fun styles_newer_live_beats_older_tombstone_and_uploads_nothing() = runBlocking {
        val store = FakePrefsStore()
        seedStyle(store, updatedAt = 100, deletedAt = 100)
        val live = remoteStyle(updatedAt = 200, deletedAt = null)

        val merged = Merge.mergeStyles(localStyles(store), listOf(live))
        V1Stores.styleStore(store.context()).applyMergedAndClearDirty(hash, device, merged)

        val row = storedStyle(store)
        assertNull(
            "the losing local tombstone must NOT be grafted onto the winning live style",
            row.deletedAt
        )
        assertEquals("winner name must be preserved", "NewStyle", row.name)
        assertEquals("winner hint must be preserved", "new hint", row.hint)
        assertEquals("winner updatedAt must be preserved", 200L, row.updatedAt)
        assertNothingQueuedForUpload(store)

        val again = Merge.mergeStyles(localStyles(store), listOf(live))
        V1Stores.styleStore(store.context()).applyMergedAndClearDirty(hash, device, again)

        assertNull(
            "no fabricated tombstone may appear on the second pass",
            storedStyle(store).deletedAt
        )
        assertNothingQueuedForUpload(store)
    }

    @Test
    fun styles_newer_tombstone_beats_older_live_and_stays_deleted() = runBlocking {
        val store = FakePrefsStore()
        seedStyle(store, updatedAt = 100, deletedAt = null)
        val tombstone = remoteStyle(updatedAt = 200, deletedAt = 200)

        val merged = Merge.mergeStyles(localStyles(store), listOf(tombstone))
        V1Stores.styleStore(store.context()).applyMergedAndClearDirty(hash, device, merged)

        assertEquals(200L, storedStyle(store).deletedAt)
        assertNothingQueuedForUpload(store)

        val again = Merge.mergeStyles(localStyles(store), listOf(tombstone))
        V1Stores.styleStore(store.context()).applyMergedAndClearDirty(hash, device, again)

        assertEquals(
            "the tombstone must be stable across an unchanged pass",
            200L, storedStyle(store).deletedAt
        )
        assertNothingQueuedForUpload(store)
    }
}
