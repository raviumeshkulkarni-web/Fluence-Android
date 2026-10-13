package com.groq.voicetyper.sync.v1

import com.groq.voicetyper.snippets.SnippetPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase E: safe corrupt-store recovery for the snippet JSON document.
 *
 * A malformed document must be preserved aside under a backup key — never
 * silently converted into an empty store that the next edit persists over.
 * Uses [FakePrefsStore] so no Android framework is needed.
 *
 * The prefs file/key names duplicate SnippetPreferences' private consts;
 * if those change, these tests fail loudly rather than testing nothing.
 */
class SnippetCorruptRecoveryTest {

    private val prefsFile = "fluence_prefs"
    private val liveKey = "snippets_json"
    private val corruptPrefix = SnippetPreferences.CORRUPT_PREFIX

    private fun corruptKeys(store: FakePrefsStore): List<String> =
        store.fileOf(prefsFile).keys.filter { it.startsWith(corruptPrefix) }

    @Test
    fun corrupt_json_is_quarantined_and_reads_empty() {
        val store = FakePrefsStore()
        val raw = "{\"snippets\": [broken"
        store.fileOf(prefsFile)[liveKey] = raw

        val out = SnippetPreferences.allEntries(store.context())

        assertTrue("unreadable document must read as empty, not throw", out.isEmpty())
        assertFalse("live key must be rotated aside", store.fileOf(prefsFile).containsKey(liveKey))
        val quarantined = corruptKeys(store)
        assertEquals(1, quarantined.size)
        assertEquals(raw, store.fileOf(prefsFile)[quarantined.single()])
    }

    @Test
    fun write_after_corruption_operates_on_fresh_store_without_touching_backup() {
        val store = FakePrefsStore()
        val raw = "definitely not json {{{"
        store.fileOf(prefsFile)[liveKey] = raw

        // Drive the real sync apply path (read-quarantine, then write-back),
        // not a blind saveAll: the pass reads through allEntries first.
        val winner = SnippetRecord(
            syncId = "s-1",
            businessKey = SnippetRecord.businessKeyOf("addr"),
            trigger = "addr",
            expansion = "address",
            isEnabled = true,
            updatedAt = 100L,
            deletedAt = null,
            deviceId = "d"
        )
        runBlocking {
            PrefsSnippetV1Store(store.context()).applyMergedAndClearDirty("hash", "d", listOf(winner))
        }

        val quarantined = corruptKeys(store)
        assertEquals(1, quarantined.size)
        assertEquals("original bytes must survive the subsequent write", raw, store.fileOf(prefsFile)[quarantined.single()])
        val live = SnippetPreferences.allEntries(store.context())
        assertEquals(1, live.size)
        assertEquals("addr", live[0].trigger)
    }

    @Test
    fun unsupported_future_version_is_left_untouched_not_emptied() {
        // A newer client owns this document (cf. frozen.rs is_future_envelope:
        // "NOT corrupt and must never be repaired over"). Quarantining it
        // would delete a newer build's data and propagate a downgrade.
        val store = FakePrefsStore()
        val raw = "{\"v\":99,\"snippets\":[]}"
        store.fileOf(prefsFile)[liveKey] = raw

        val out = SnippetPreferences.allEntries(store.context())

        assertTrue(out.isEmpty())
        assertEquals(
            "the live key of a future-schema document must survive",
            raw,
            store.fileOf(prefsFile)[liveKey]
        )
        assertTrue(
            "no backup may be taken for a document that is not corrupt",
            corruptKeys(store).isEmpty()
        )
    }

    @Test
    fun supported_version_with_broken_body_is_quarantined() {
        // The gap between the two branches: root parses, version is
        // supported, but the body is broken. Unambiguously corruption —
        // must be preserved aside, never read as empty in place.
        // (Mirrors Windows wrong_shape_json_errors_and_preserves_bytes.)
        val store = FakePrefsStore()
        val raw = "{\"v\":2,\"snippets\":{}}"
        store.fileOf(prefsFile)[liveKey] = raw

        val out = SnippetPreferences.allEntries(store.context())

        assertTrue(out.isEmpty())
        assertFalse("live key must be rotated aside", store.fileOf(prefsFile).containsKey(liveKey))
        val quarantined = corruptKeys(store)
        assertEquals(1, quarantined.size)
        assertEquals(raw, store.fileOf(prefsFile)[quarantined.single()])
    }

    @Test
    fun missing_key_reads_empty_without_backup() {
        val store = FakePrefsStore()

        assertTrue(SnippetPreferences.allEntries(store.context()).isEmpty())
        assertTrue(corruptKeys(store).isEmpty())
    }

    @Test
    fun repeated_corruptions_do_not_overwrite_each_other() {
        val store = FakePrefsStore()
        store.fileOf(prefsFile)[liveKey] = "{bad one"
        SnippetPreferences.allEntries(store.context())
        store.fileOf(prefsFile)[liveKey] = "{bad two"
        SnippetPreferences.allEntries(store.context())

        val quarantined = corruptKeys(store)
        assertEquals(2, quarantined.size)
        val raws = quarantined.map { store.fileOf(prefsFile)[it] }.toSet()
        assertEquals(setOf("{bad one", "{bad two"), raws)
    }
}
