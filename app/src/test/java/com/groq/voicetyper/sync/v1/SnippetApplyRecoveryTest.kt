package com.groq.voicetyper.sync.v1

import com.groq.voicetyper.snippets.Snippet
import com.groq.voicetyper.snippets.SnippetPreferences
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F3 — snippet persistence gap (Phase D reviewer carry-forward).
 *
 * Covers three named items against the REAL prefs-backed store:
 * - (B) `nextFree` must derive from the pre-drop id space, so a later insert
 *   never reuses the local id of a duplicate removed by merge healing.
 * - (C) quarantined rows are never hard-deleted by merge healing and never
 *   enter the sync load (dictionary-store parity).
 */
class SnippetApplyRecoveryTest {

    private val hash = "a".repeat(64)
    private val deviceId = "dev-local"
    private val sid = "11111111-1111-4111-8111-111111111111"

    /**
     * The JVM test context has no Android keystore, so the encrypted device-id
     * store encrypts keys to null and crashes the prefs fake — a
     * test-environment artifact, not production behavior (production always
     * has a keystore; the keystore-out fallback uses plain keys). Pin the id.
     */
    @org.junit.Before
    fun stubDeviceId() {
        mockkObject(DeviceIdProvider)
        every { DeviceIdProvider.getDeviceId(any()) } returns deviceId
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun row(
        id: Long,
        trigger: String,
        updatedAt: Long? = 1_000L,
        dirty: Boolean = false,
        quarantineReason: String? = null,
    ) = Snippet(
        id = id,
        trigger = trigger,
        expansion = "exp",
        uuid = sid,
        createdAt = 1_000L,
        updatedAt = updatedAt,
        deletedAt = null,
        deviceId = deviceId,
        isEnabled = true,
        dirty = dirty,
        everPushed = true,
        syncState = null,
        serverFileId = null,
        syncAccount = hash,
        quarantineReason = quarantineReason,
    )

    private fun record(trigger: String, updatedAt: Long = 1_000L) = SnippetRecord(
        syncId = sid,
        businessKey = SnippetRecord.businessKeyOf(trigger),
        trigger = trigger,
        expansion = "exp",
        isEnabled = true,
        updatedAt = updatedAt,
        deletedAt = null,
        deviceId = "dev-peer",
    )

    @Test
    fun nextFree_never_reuses_a_healed_duplicate_id() {
        val ctx = FakePrefsStore().context()
        // Winner keeps id 1; the superseded duplicate holds the max id 2 and
        // is dropped by merge healing.
        SnippetPreferences.saveAll(
            ctx,
            listOf(row(1, "new addr"), row(2, "old addr"))
        )

        PrefsSnippetV1Store(ctx).apply {
            // Suspend call from a JVM test: the body is blocking prefs I/O.
            kotlinx.coroutines.runBlocking {
                applyMergedAndClearDirty(hash, deviceId, listOf(record("new addr")))
            }
        }

        val kept = SnippetPreferences.allEntries(ctx)
        assertEquals("healed duplicate must be gone", 1, kept.size)
        assertEquals("new addr", kept[0].trigger)

        // The next local insert must take id 3 (past the pre-drop max 2),
        // never the recycled id 2 of the dropped duplicate.
        assertEquals(SnippetPreferences.SaveResult.INSERTED,
            SnippetPreferences.saveSnippet(ctx, "brand new", "exp"))
        val inserted = SnippetPreferences.allEntries(ctx).firstOrNull { it.trigger == "brand new" }
        assertNotNull("insert must survive", inserted)
        assertEquals("dropped duplicate id must never be reused", 3L, inserted!!.id)
    }

    @Test
    fun superseded_drop_never_takes_a_quarantined_row() {
        val quarantined = row(7, "old addr", quarantineReason = "corrupt_file")
        val kept = decideSnippetSupersededDrops(
            listOf(row(1, "new addr"), quarantined),
            listOf(record("new addr")),
            hash
        )
        assertTrue(
            "a quarantined row must survive merge healing even when superseded",
            kept.any { it.id == 7L }
        )
        assertTrue("the live winner still survives", kept.any { it.id == 1L })
    }

    @Test
    fun sync_load_excludes_quarantined_rows() {
        val ctx = FakePrefsStore().context()
        SnippetPreferences.saveAll(
            ctx,
            listOf(row(1, "live addr"), row(2, "held addr", quarantineReason = "corrupt_file"))
        )
        val loaded = PrefsSnippetV1Store(ctx).let {
            kotlinx.coroutines.runBlocking {
                // loadByAccount is the sync seam: quarantined rows must be
                // impossible to serialize even if stamped (contract defense).
                it.loadByAccount(hash)
            }
        }
        assertEquals(1, loaded.size)
        assertEquals("live addr", loaded[0].trigger)
        // The quarantined row itself is untouched in storage (held, not deleted).
        assertNotNull(
            SnippetPreferences.allEntries(ctx).firstOrNull { it.quarantineReason != null }
        )
    }
}
