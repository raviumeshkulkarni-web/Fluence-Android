package com.groq.voicetyper.sync.v1

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * STAGE 8 runtime regression: Android Agent/Style dirty-state lifecycle.
 *
 * Found by real-device QA, not by the suite. After a record had synced once it
 * carried a `syncId`/`updatedAt` forever, and `dirty` was derived from their
 * absence:
 *
 *     dirty = syncId == null || updatedAt == null
 *
 * so every edit and delete AFTER the first sync was permanently invisible to
 * upload. CREATE propagated; EDIT and DELETE did not. Windows had the identical
 * defect.
 *
 * These tests drive the real [AccountScope] mutation functions and the real
 * [V1Stores.AccountAgentV1Store] / [V1Stores.AccountStyleV1Store]
 * dirty/stamp/merge seam. Only [DeviceIdProvider] is stubbed, because its
 * EncryptedSharedPreferences path needs an Android keystore that a JVM unit
 * test cannot provide; every other line is production code.
 */
class AgentStyleDirtyLifecycleTest {

    private val hash = "c".repeat(64)
    private val device = "qa-device-id"

    @Before
    fun stubDeviceId() {
        mockkObject(DeviceIdProvider)
        every { DeviceIdProvider.getDeviceId(any()) } returns device
    }

    @After
    fun unstubDeviceId() {
        unmockkObject(DeviceIdProvider)
    }

    private fun ctx() = FakePrefsStore().context()

    private fun agentRow(ctx: android.content.Context, id: String) =
        AccountScope.loadAgents(ctx, hash).first { it.id == id }

    private fun styleRow(ctx: android.content.Context, id: String) =
        AccountScope.loadStyles(ctx, hash).first { it.id == id }

    /** Merge every local row back in, i.e. "a sync pass accepted the state". */
    private suspend fun V1Stores.AccountAgentV1Store.acceptAll(ctx: android.content.Context) =
        applyMergedAndClearDirty(
            hash, device,
            AccountScope.loadAgents(ctx, hash).map {
                AgentRecord(
                    it.syncId!!, it.id, it.name, it.hint, it.updatedAt!!,
                    it.deletedAt, it.deviceId!!
                )
            }
        )

    private suspend fun V1Stores.AccountStyleV1Store.acceptAll(ctx: android.content.Context) =
        applyMergedAndClearDirty(
            hash, device,
            AccountScope.loadStyles(ctx, hash).map {
                StyleRecord(
                    it.syncId!!, it.id, it.name, it.hint, it.updatedAt!!,
                    it.deletedAt, it.deviceId!!
                )
            }
        )

    // ------------------------------------------------------------------
    // Agents
    // ------------------------------------------------------------------

    @Test
    fun agent_create_sync_edit_sync_makes_the_edit_dirty_and_gives_it_a_newer_revision() =
        runBlocking {
            val ctx = ctx()
            val store = V1Stores.AccountAgentV1Store(ctx)
            val id = "agent:qa-edit-0001"

            // CREATE -> dirty until the sync accepts it.
            AccountScope.upsertAgent(ctx, hash, id, "v1", "hint-1")
            assertTrue("a new agent must be dirty", store.hasDirty(hash))
            assertTrue(agentRow(ctx, id).dirty)

            // SYNC: stamp, then a completed merge makes it clean.
            store.stampUnstamped(hash)
            val firstRev = agentRow(ctx, id).updatedAt
            assertNotNull("a synced agent must carry a revision", firstRev)
            assertTrue(firstRev!! > 0)
            assertEquals(device, agentRow(ctx, id).deviceId)
            store.acceptAll(ctx)
            assertFalse("a successfully synchronized agent must be clean", store.hasDirty(hash))

            // EDIT of an already-synced agent must become dirty AGAIN. This is
            // the step that used to be a silent no-op at runtime.
            AccountScope.upsertAgent(ctx, hash, id, "v2", "hint-2")
            assertTrue("an edit to an already-synced agent must re-dirty", store.hasDirty(hash))

            // The edit must get a NEWER revision from THIS device, never the
            // revision inherited from the peer it was pulled from.
            store.stampUnstamped(hash)
            val edited = agentRow(ctx, id)
            assertEquals("hint-2", edited.hint)
            assertTrue(
                "the edit must carry a strictly newer LWW revision",
                edited.updatedAt!! > firstRev
            )
            assertEquals(device, edited.deviceId)

            store.acceptAll(ctx)
            assertFalse(store.hasDirty(hash))
            assertEquals(edited.updatedAt, agentRow(ctx, id).updatedAt)
        }

    @Test
    fun agent_survives_two_edit_sync_cycles() = runBlocking {
        val ctx = ctx()
        val store = V1Stores.AccountAgentV1Store(ctx)
        val id = "agent:qa-twice-0001"

        AccountScope.upsertAgent(ctx, hash, id, "v1", "hint-1")
        store.stampUnstamped(hash)
        store.acceptAll(ctx)

        var lastRev = agentRow(ctx, id).updatedAt!!
        for (round in 2..3) {
            AccountScope.upsertAgent(ctx, hash, id, "v$round", "hint")
            assertTrue("edit round $round must re-dirty", store.hasDirty(hash))
            store.stampUnstamped(hash)
            val rev = agentRow(ctx, id).updatedAt!!
            assertTrue("round $round must advance the revision", rev > lastRev)
            lastRev = rev
            store.acceptAll(ctx)
            assertFalse("round $round must end clean", store.hasDirty(hash))
        }
    }

    @Test
    fun agent_delete_after_first_sync_uploads_a_fresh_tombstone() = runBlocking {
        val ctx = ctx()
        val store = V1Stores.AccountAgentV1Store(ctx)
        val id = "agent:qa-delete-0001"

        AccountScope.upsertAgent(ctx, hash, id, "v1", "hint-1")
        store.stampUnstamped(hash)
        val syncedRev = agentRow(ctx, id).updatedAt!!
        store.acceptAll(ctx)
        assertFalse("precondition: the row starts clean", store.hasDirty(hash))

        AccountScope.deleteAgent(ctx, hash, id)
        assertTrue("a delete of an already-synced agent must re-dirty", store.hasDirty(hash))

        store.stampUnstamped(hash)
        val dead = agentRow(ctx, id)
        assertNotNull("the row must be a tombstone, not a removal", dead.deletedAt)
        assertTrue(
            "the tombstone must carry a newer revision so LWW cannot lose the delete",
            dead.updatedAt!! > syncedRev
        )
        // Uploaded rather than purged: it holds a syncId, so a peer still
        // holding the live row cannot resurrect it.
        store.acceptAll(ctx)
        assertNotNull(store.loadByAccount(hash).first().deletedAt)
    }

    @Test
    fun agent_delete_then_recreate_keeps_the_tombstone_and_survives_the_new_row() =
        runBlocking {
            val ctx = ctx()
            val store = V1Stores.AccountAgentV1Store(ctx)
            val oldId = "agent:qa-recreate-old"
            val newId = "agent:qa-recreate-new"

            AccountScope.upsertAgent(ctx, hash, oldId, "original", "hint")
            store.stampUnstamped(hash)
            store.acceptAll(ctx)

            AccountScope.deleteAgent(ctx, hash, oldId)
            store.stampUnstamped(hash)
            store.acceptAll(ctx)

            // Recreating mints a fresh id; the old tombstone must not suppress it.
            AccountScope.upsertAgent(ctx, hash, newId, "recreated", "hint")
            assertTrue(store.hasDirty(hash))
            store.stampUnstamped(hash)
            store.acceptAll(ctx)

            val rows = store.loadByAccount(hash)
            assertEquals("tombstone and replacement must both survive", 2, rows.size)
            assertTrue(rows.any { it.businessKey == oldId && it.deletedAt != null })
            assertTrue(
                "the replacement must be live, not suppressed by the old tombstone",
                rows.any { it.businessKey == newId && it.deletedAt == null }
            )
        }

    // ------------------------------------------------------------------
    // Styles
    // ------------------------------------------------------------------

    @Test
    fun style_edit_and_delete_after_first_sync_both_re_dirty() = runBlocking {
        val ctx = ctx()
        val store = V1Stores.AccountStyleV1Store(ctx)
        val id = "custom:qa-style-0001"

        AccountScope.upsertStyle(ctx, hash, id, "v1", "style-hint")
        assertTrue("a new style must be dirty", store.hasDirty(hash))
        store.stampUnstamped(hash)
        val firstRev = styleRow(ctx, id).updatedAt!!
        store.acceptAll(ctx)
        assertFalse("a synced style must be clean", store.hasDirty(hash))

        AccountScope.upsertStyle(ctx, hash, id, "v2", "style-hint-2")
        assertTrue("a style edit must re-dirty", store.hasDirty(hash))
        store.stampUnstamped(hash)
        val edited = styleRow(ctx, id)
        assertEquals("style-hint-2", edited.hint)
        assertTrue(edited.updatedAt!! > firstRev)
        assertEquals(device, edited.deviceId)
        store.acceptAll(ctx)
        assertFalse(store.hasDirty(hash))

        AccountScope.deleteStyle(ctx, hash, id)
        assertTrue("a style delete must re-dirty", store.hasDirty(hash))
        store.stampUnstamped(hash)
        val dead = styleRow(ctx, id)
        assertNotNull("a style delete must tombstone", dead.deletedAt)
        assertTrue(dead.updatedAt!! > edited.updatedAt!!)
    }

    // ------------------------------------------------------------------
    // Persistence + convergence
    // ------------------------------------------------------------------

    @Test
    fun the_dirty_flag_survives_the_prefs_round_trip() {
        val ctx = ctx()
        val id = "agent:qa-flag-0001"
        AccountScope.upsertAgent(ctx, hash, id, "v1", "hint")
        assertTrue("the flag must be persisted, not held in memory", agentRow(ctx, id).dirty)
        assertTrue(AccountScope.loadAgents(ctx, hash).first { it.id == id }.dirty)
    }

    @Test
    fun a_peer_winner_replaces_the_local_row_and_leaves_it_clean() = runBlocking {
        val ctx = ctx()
        val store = V1Stores.AccountAgentV1Store(ctx)
        val id = "agent:qa-converge-0001"

        AccountScope.upsertAgent(ctx, hash, id, "local", "local-hint")
        store.stampUnstamped(hash)
        store.acceptAll(ctx)

        val peer = AgentRecord(
            syncId = java.util.UUID.nameUUIDFromBytes("fluence-agent:$id".toByteArray()).toString(),
            businessKey = id,
            name = "peer",
            hint = "peer-hint",
            updatedAt = agentRow(ctx, id).updatedAt!! + 5_000L,
            deletedAt = null,
            deviceId = "peer-device",
        )
        store.applyMergedAndClearDirty(hash, device, listOf(peer))

        val local = store.loadByAccount(hash)
        assertEquals(1, local.size)
        assertEquals("peer", local[0].name)
        assertEquals("peer-hint", local[0].hint)
        // The STORED row must carry the peer's revision identity, which is what
        // a later merge compares. (`AgentLocal.deviceId` is deliberately the
        // LOCAL device: `toLocal` projects the store's own id, and for a dirty
        // row the two agree because stamping just set the row to this device.)
        val stored = AccountScope.loadAgents(ctx, hash).first { it.id == id }
        assertEquals("peer-device", stored.deviceId)
        assertEquals(peer.updatedAt, stored.updatedAt)
        assertFalse(stored.dirty)
        assertFalse(
            "a merged peer winner must leave the row clean, or it re-uploads forever",
            store.hasDirty(hash)
        )
    }
}