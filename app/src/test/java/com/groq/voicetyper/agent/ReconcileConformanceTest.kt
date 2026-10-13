package com.groq.voicetyper.agent

import com.groq.voicetyper.sync.SyncAccounts
import com.groq.voicetyper.sync.v1.AccountHash
import com.groq.voicetyper.sync.v1.AccountScope
import com.groq.voicetyper.sync.v1.AgentRecord
import com.groq.voicetyper.sync.v1.DeviceIdProvider
import com.groq.voicetyper.sync.v1.FakePrefsStore
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Cross-platform reconcile conformance (audit finding G3).
 *
 * The Android `agentStore.applyMergedAndClearDirty` and the Windows
 * `AgentDirtyStore::save_merged` implement the same three rules, but nothing
 * mechanically enforced that until these vectors existed — a stale-merge test
 * on each side once documented OPPOSITE behaviors. These three scenarios use
 * fixed ids both sides construct literally, and the Windows twin
 * (`reconcile_conformance` in src-tauri/src/sync/stores.rs, same three names)
 * asserts the same outcomes through the real store path. If either side
 * changes its rule, its twin goes red; if the vectors drift apart, the names
 * stop matching.
 *
 * The rules, stated once:
 *  1. a row the merge omits entirely survives exactly as stored;
 *  2. a dirty row whose content still differs from its winner survives dirty,
 *     so the next pass uploads it;
 *  3. a dirty row whose content already equals its winner is replaced by the
 *     clean winner — otherwise `dirty` would re-upload forever.
 */
class ReconcileConformanceTest {

    private val emailA = "owner-a@example.com"
    private val hashA = AccountHash.of(emailA)!!

    // Fixed ids both sides construct literally. Chosen to sort distinctly and
    // to never collide with any other test's ids.
    private val v1 = "agent:conformance-0001"
    private val v2 = "agent:conformance-0002"
    private val v3 = "agent:conformance-0003"

    @Before
    fun stubDeviceId() {
        mockkObject(DeviceIdProvider)
        every { DeviceIdProvider.getDeviceId(any()) } returns "test-device"
    }

    @After
    fun unstubDeviceId() {
        unmockkObject(DeviceIdProvider)
    }

    private inline fun signedInAs(email: String?, block: () -> Unit) {
        val savedVerified = SyncAccounts.tokenVerified
        val savedAccount = SyncAccounts.cachedAccount
        SyncAccounts.clearAuthentication()
        SyncAccounts.cachedAccount = email
        try {
            block()
        } finally {
            SyncAccounts.clearAuthentication()
            if (savedVerified) SyncAccounts.publishAuthenticated(savedAccount) else {
                SyncAccounts.cachedAccount = savedAccount
            }
        }
    }

    private fun store() = FakePrefsStore()

    private fun row(
        id: String,
        name: String,
        syncId: String?,
        dirty: Boolean
    ) = AccountScope.AccountAgent(
        id = id,
        name = name,
        hint = "h",
        syncId = syncId,
        updatedAt = 1000L,
        deviceId = "test-device",
        deletedAt = null,
        dirty = dirty
    )

    private fun winner(id: String, name: String, syncId: String) = AgentRecord(
        syncId = syncId,
        businessKey = id,
        name = name,
        hint = "h",
        updatedAt = 2000L,
        deletedAt = null,
        deviceId = "test-device"
    )

    private fun seed(s: FakePrefsStore, rows: List<AccountScope.AccountAgent>) {
        AccountScope.saveAgents(s.context(), hashA, rows)
    }

    private fun load(s: FakePrefsStore) = AccountScope.loadAgents(s.context(), hashA)

    private fun apply(s: FakePrefsStore, merged: List<AgentRecord>) {
        kotlinx.coroutines.runBlocking {
            com.groq.voicetyper.sync.v1.V1Stores.agentStore(s.context())
                .applyMergedAndClearDirty(hashA, "test-device", merged)
        }
    }

    /**
     * Vector 1: a row the merge omits entirely survives exactly as stored.
     * Mirrors Windows `conformance_omitted_row_survives`.
     */
    @Test
    fun conformance_omitted_row_survives() = signedInAs(emailA) {
        val s = store()
        seed(s, listOf(row(v1, "Conf One", null, true)))

        apply(s, emptyList())

        val after = load(s)
        val survived = after.firstOrNull { it.id == v1 }
        assertNotNull("vector 1: omitted row must survive", survived)
        assertTrue("vector 1: and it stays dirty", survived!!.dirty)
        assertEquals("vector 1: exactly one row, never a duplicate", 1, after.count { it.id == v1 })
    }

    /**
     * Vector 2: a dirty row whose content differs from its winner survives.
     * Mirrors Windows `conformance_dirty_differing_row_survives`.
     */
    @Test
    fun conformance_dirty_differing_row_survives() = signedInAs(emailA) {
        val s = store()
        seed(s, listOf(row(v2, "Local Name", "sync-2", true)))

        apply(s, listOf(winner(v2, "Winner Name", "sync-2")))

        val after = load(s)
        val survived = after.firstOrNull { it.id == v2 }
        assertNotNull("vector 2: differing dirty row must survive", survived)
        assertEquals("vector 2: the local content wins until uploaded", "Local Name", survived!!.name)
        assertTrue("vector 2: and it stays dirty for the next upload", survived.dirty)
    }

    /**
     * Vector 3: a dirty row whose content already equals its winner is
     * replaced by the clean winner.
     * Mirrors Windows `conformance_dirty_matching_row_goes_clean`.
     */
    @Test
    fun conformance_dirty_matching_row_goes_clean() = signedInAs(emailA) {
        val s = store()
        seed(s, listOf(row(v3, "Same Name", "sync-3", true)))

        apply(s, listOf(winner(v3, "Same Name", "sync-3")))

        val after = load(s)
        assertEquals("vector 3: exactly one row, never a duplicate", 1, after.count { it.id == v3 })
        val settled = after.first { it.id == v3 }
        assertFalse(
            "vector 3: content equals the winner, so the winner is authoritative and the row goes clean",
            settled.dirty
        )
    }
}
