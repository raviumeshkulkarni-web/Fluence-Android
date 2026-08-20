package com.groq.voicetyper.sync.engine

import com.groq.voicetyper.sync.wire.ParseResult
import com.groq.voicetyper.sync.wire.RecordType
import com.groq.voicetyper.sync.wire.WireRecord
import com.groq.voicetyper.sync.wire.parse
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreTest {

    private fun memoryStore(): SettingsStore = SettingsStore()

    private fun wireOf(row: LocalRow): WireRecord =
        when (val r = parse(row.toWire().toJson().toByteArray(), row.uuid)) {
            is ParseResult.Ok -> r.record
            is ParseResult.Err -> error("row serializes to a valid settings record")
        }

    @Test
    fun enabled_toggle_roundtrips() {
        val store = memoryStore()
        val u1 = store.toggle(KEY_SNIPPETS_ENABLED, "true")
        assertEquals(true, store.liveEnabled())
        assertEquals("true", store.liveValue(KEY_SNIPPETS_ENABLED))

        // Toggle off: old row tombstoned, fresh UUID row with the new value.
        val u2 = store.toggle(KEY_SNIPPETS_ENABLED, "false")
        assertNotEquals(u1, u2)
        assertEquals(false, store.liveEnabled())

        val old = store.rows().first { it.uuid == u1 }
        assertTrue("old row kept as tombstone", old.deletedAt != null)
        assertEquals("true", old.value)

        // §30 wire roundtrip: the new row is a valid settings record.
        val fresh = store.rows().first { it.uuid == u2 }
        val rec = wireOf(SettingsStore.toLocal(fresh))
        assertEquals(RecordType.Settings, rec.rtype)
        assertEquals(KEY_SNIPPETS_ENABLED, rec.settingsKey)
        assertEquals("false", rec.settingsValue)

        // A re-toggled row's UUID is fresh again; roundtrip stays lossless.
        val u3 = store.toggle(KEY_SNIPPETS_ENABLED, "true")
        assertNotEquals(u3, u2)
        assertEquals(true, store.liveEnabled())
    }

    @Test
    fun settings_toggle_quarantines_on_divergence() {
        // Two live rows for the same key with different values: the incoming
        // row is latched `collision`; the local value is never silently lost.
        val store = memoryStore()
        val u1 = store.toggle(KEY_SNIPPETS_ENABLED, "true")

        store.import(settingsRow("00000000-0000-4000-8000-0000000000dd", KEY_SNIPPETS_ENABLED, "false"))
        store.import(settingsRow("00000000-0000-4000-8000-0000000000ee", KEY_SNIPPETS_ENABLED, "false"))

        val incoming = store.rows().first { it.uuid == "00000000-0000-4000-8000-0000000000ee" }
        assertEquals(
            "divergent live value must be latched, not silently adopted",
            "collision",
            incoming.quarantineReason
        )
        assertEquals(SYNC_STATE_QUARANTINED, incoming.syncState)
        assertEquals("local value wins", true, store.liveEnabled())
        assertEquals(u1, store.liveRow(KEY_SNIPPETS_ENABLED)?.uuid)

        // A tombstone for the same key is never a collision.
        val tomb = settingsRow("00000000-0000-4000-8000-0000000000ff", KEY_SNIPPETS_ENABLED, "true")
            .copy(deletedAt = 1713472000123L, syncState = SYNC_STATE_DIRTY)
        store.import(tomb)
        val tombRow = store.rows().first { it.uuid == "00000000-0000-4000-8000-0000000000ff" }
        assertNull(tombRow.quarantineReason)
    }

    @Test
    fun tombstone_import_never_collides() {
        val store = memoryStore()
        store.toggle(KEY_SNIPPETS_ENABLED, "true")
        val tomb = settingsRow("00000000-0000-4000-8000-0000000000aa", KEY_SNIPPETS_ENABLED, "false")
            .copy(deletedAt = 1713472000123L)
        store.import(tomb)
        val incoming = store.rows().first { it.uuid == "00000000-0000-4000-8000-0000000000aa" }
        assertNull(incoming.quarantineReason)
        assertEquals(true, store.liveEnabled())
    }

    @Test
    fun import_is_idempotent_and_persists() {
        val path = Files.createTempFile("fluence-sync-settings-test-", ".json").toFile()
            .also { it.delete() }
            .absolutePath
        try {
            val store = SettingsStore(path)
            store.toggle(KEY_SNIPPETS_ENABLED, "true")
            val live = store.liveRow(KEY_SNIPPETS_ENABLED)!!
            val asLocal = SettingsStore.toLocal(live)

            // Re-import of the same row is an idempotent upsert (one row only).
            store.import(asLocal)
            store.import(asLocal)
            assertEquals(1, store.rows().size)

            // A fresh instance from the same path sees the persisted row.
            val reloaded = SettingsStore(path)
            assertEquals(true, reloaded.liveEnabled())
            assertEquals(1, reloaded.rows().size)
        } finally {
            File(path).delete()
        }
    }

    @Test
    fun mirror_enabled_applies_live_value() {
        val store = memoryStore()
        val applied = mutableListOf<Boolean>()
        store.mirrorEnabled { applied.add(it) }
        assertTrue("no live row → nothing to mirror", applied.isEmpty())

        store.toggle(KEY_SNIPPETS_ENABLED, "false")
        store.mirrorEnabled { applied.add(it) }
        assertEquals(listOf(false), applied)
    }

    @Test
    fun engine_pass_round_trips_a_toggle() {
        // Store + engine integration: toggle → engine creates the record on
        // Drive and the store row converges to clean.
        val drive = FakeDrive()
        val store = memoryStore()
        store.toggle(KEY_SNIPPETS_ENABLED, "true")

        val o = SyncEngine.run(RecordType.Settings, "account-a", store, drive, FakeToken(true), InMemoryFileCacheStore())
        assertEquals(
            SyncOutcome(created = 1),
            o
        )
        val live = store.liveRow(KEY_SNIPPETS_ENABLED)!!
        assertTrue(live.serverFileId != null)
        assertEquals("clean", live.syncState)
        assertEquals(1, drive.files.size)
        assertEquals(KEY_SNIPPETS_ENABLED, drive.files[0].second.settingsKey)
        assertEquals("true", drive.files[0].second.settingsValue)
    }

    private class FakeDrive(
        val files: MutableList<Pair<String, WireRecord>> = mutableListOf(),
    ) : DriveStore {
        override fun findOrCreateFolder() {}

        override fun listFiles(): List<FileMeta> =
            files.map { FileMeta(it.first, "${it.second.id}.json") }

        override fun getContent(fileId: String): ByteArray? =
            files.firstOrNull { it.first == fileId }?.second?.toJson()?.toByteArray()

        override fun createFile(name: String, record: WireRecord): String {
            val id = "F${files.size + 1}"
            files.add(id to record)
            return id
        }

        override fun updateContent(fileId: String, record: WireRecord) {
            val index = files.indexOfFirst { it.first == fileId }
            if (index >= 0) files[index] = fileId to record
        }
    }

    private class FakeToken(private val valid: Boolean) : TokenProvider {
        override fun hasValidToken(): Boolean = valid
    }

    private fun settingsRow(uuid: String, key: String, value: String): LocalRow =
        SettingsStore.settingsRow(uuid, key, value)
}