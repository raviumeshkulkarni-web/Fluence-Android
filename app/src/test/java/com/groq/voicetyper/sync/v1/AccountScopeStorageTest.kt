package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 Android — storage-seam and recovery coverage.
 *
 * Uses a hand-written in-memory [android.content.SharedPreferences] fake rather
 * than a mocking framework, so `putString`/`getString`/`edit()`/`apply()` really
 * round-trip. This covers the seam the serialization-level tests cannot reach:
 * which preferences FILE a write lands in, that the legacy file is never
 * written, and that a corrupt account payload is quarantined in place.
 */
class AccountScopeStorageTest {

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    private fun agent(id: String, name: String) =
        AccountScope.AccountAgent(
            id = id, name = name, hint = "hint-$name",
            syncId = "sync-$id", updatedAt = 100L, deviceId = "dev-a"
        )

    private fun style(id: String, name: String) =
        AccountScope.AccountStyle(
            id = id, name = name, hint = "hint-$name",
            syncId = "sync-$id", updatedAt = 100L, deviceId = "dev-a"
        )

    // ---------------- normal read / write round trip -----------------------

    @Test
    fun agents_round_trip_through_a_real_prefs_file() {
        val store = FakePrefsStore()
        val ctx = store.context()
        AccountScope.saveAgents(ctx, hashA, listOf(agent("agent:1", "A-one")))
        assertEquals(
            listOf("A-one"),
            AccountScope.loadAgents(ctx, hashA).map { it.name }
        )
    }

    @Test
    fun styles_round_trip_through_a_real_prefs_file() {
        val store = FakePrefsStore()
        val ctx = store.context()
        AccountScope.saveStyles(ctx, hashA, listOf(style("custom:1", "A-style")))
        assertEquals(
            listOf("A-style"),
            AccountScope.loadStyles(ctx, hashA).map { it.name }
        )
    }

    @Test
    fun tombstone_survives_the_storage_seam() {
        val store = FakePrefsStore()
        val ctx = store.context()
        AccountScope.saveAgents(
            ctx, hashA,
            listOf(
                agent("agent:1", "kept"),
                agent("agent:2", "gone").copy(deletedAt = 1_700_000_000_000L)
            )
        )
        val loaded = AccountScope.loadAgents(ctx, hashA)
        assertEquals(2, loaded.size)
        assertEquals(null, loaded.first { it.id == "agent:1" }.deletedAt)
        assertEquals(1_700_000_000_000L, loaded.first { it.id == "agent:2" }.deletedAt)
    }

    // ---------------- storage separation -----------------------------------

    @Test
    fun write_lands_in_the_account_file_and_never_the_legacy_file() {
        val store = FakePrefsStore()
        val ctx = store.context()
        AccountScope.saveAgents(ctx, hashA, listOf(agent("agent:1", "x")))
        AccountScope.saveStyles(ctx, hashA, listOf(style("custom:1", "y")))

        assertTrue(store.exists(AccountScope.agentsPrefsName(hashA)))
        assertTrue(store.exists(AccountScope.stylesPrefsName(hashA)))
        assertFalse(
            "the legacy preferences file must never be created by this module",
            store.exists(AccountScope.LEGACY_PREFS)
        )
    }

    @Test
    fun a_and_b_live_in_disjoint_files() {
        val store = FakePrefsStore()
        val ctx = store.context()
        AccountScope.saveAgents(ctx, hashA, listOf(agent("agent:A", "A-only")))
        AccountScope.saveAgents(ctx, hashB, listOf(agent("agent:B", "B-only")))

        assertEquals(listOf("A-only"), AccountScope.loadAgents(ctx, hashA).map { it.name })
        assertEquals(listOf("B-only"), AccountScope.loadAgents(ctx, hashB).map { it.name })
    }

    @Test
    fun unknown_account_sees_nothing() {
        val store = FakePrefsStore()
        val ctx = store.context()
        AccountScope.saveAgents(ctx, hashA, listOf(agent("agent:A", "A-only")))
        val cHash = "c".repeat(64)
        assertEquals(emptyList<AccountScope.AccountAgent>(), AccountScope.loadAgents(ctx, cHash))
    }

    // ---------------- legacy is never written ------------------------------

    @Test
    fun legacy_keys_are_never_written() {
        val store = FakePrefsStore()
        val ctx = store.context()
        AccountScope.saveAgents(ctx, hashA, listOf(agent("agent:1", "x")))
        AccountScope.saveStyles(ctx, hashA, listOf(style("custom:1", "y")))
        store.names().forEach { name ->
            AccountScope.legacyKeys().forEach { key ->
                assertFalse("$name must not contain legacy key $key", store.fileOf(name).containsKey(key))
            }
        }
    }

    // ---------------- corrupt payload quarantine ---------------------------

    @Test
    fun corrupt_account_payload_is_quarantined_and_legacy_untouched() {
        val store = FakePrefsStore()
        val ctx = store.context()
        val agentsFile = AccountScope.agentsPrefsName(hashA)

        // Plant a corrupt payload as a previous version would have left it.
        store.fileOf(agentsFile)[AccountScope.agentsKey()] = "{ not json"
        store.fileOf(AccountScope.LEGACY_PREFS)["agent_custom_styles"] =
            """[{"id":"agent:legacy","name":"l","hint":"h"}]"""

        assertEquals(
            emptyList<AccountScope.AccountAgent>(),
            AccountScope.loadAgents(ctx, hashA)
        )

        // Raw text preserved for recovery, inside the ACCOUNT file only.
        val quarantined = store.fileOf(agentsFile).keys
            .filter { it.startsWith(AccountScope.corruptPrefix()) }
        assertEquals(1, quarantined.size)
        assertEquals(
            "{ not json",
            store.fileOf(agentsFile)[quarantined.single()]
        )
        // Legacy preferences file is byte-for-byte what it was.
        assertEquals(
            """[{"id":"agent:legacy","name":"l","hint":"h"}]""",
            store.fileOf(AccountScope.LEGACY_PREFS)["agent_custom_styles"]
        )
        assertFalse(
            "quarantine must not use edit().clear() on any file",
            store.cleared.contains(AccountScope.LEGACY_PREFS)
        )
    }

    @Test
    fun multiple_corrupt_payloads_do_not_overwrite_each_other() {
        val store = FakePrefsStore()
        val ctx = store.context()
        val agentsFile = AccountScope.agentsPrefsName(hashA)
        store.fileOf(agentsFile)[AccountScope.agentsKey()] = "bad-1"
        AccountScope.loadAgents(ctx, hashA)
        store.fileOf(agentsFile)[AccountScope.agentsKey()] = "bad-2"
        AccountScope.loadAgents(ctx, hashA)

        val kept = store.fileOf(agentsFile).filterKeys { it.startsWith(AccountScope.corruptPrefix()) }
        assertEquals(2, kept.size)
        assertEquals(setOf("bad-1", "bad-2"), kept.values.toSet())
    }

    @Test
    fun the_same_corruption_event_is_quarantined_only_once() {
        // Regression: quarantine must CLEAR the offending value, otherwise every
        // read re-detects it and appends another full copy of the payload —
        // unbounded growth of a file Android loads entirely into memory.
        val store = FakePrefsStore()
        val ctx = store.context()
        val agentsFile = AccountScope.agentsPrefsName(hashA)
        store.fileOf(agentsFile)[AccountScope.agentsKey()] = "bad-payload"

        repeat(25) { AccountScope.loadAgents(ctx, hashA) }

        val kept = store.fileOf(agentsFile).filterKeys { it.startsWith(AccountScope.corruptPrefix()) }
        assertEquals("one corruption event must cost exactly one entry", 1, kept.size)
        assertEquals("bad-payload", kept.values.single())
        assertFalse(
            "the corrupt value must be cleared, matching Windows rotate-aside",
            store.fileOf(agentsFile).containsKey(AccountScope.agentsKey())
        )
    }

    @Test
    fun corrupt_styles_payload_is_also_cleared_after_quarantine() {
        val store = FakePrefsStore()
        val ctx = store.context()
        val stylesFile = AccountScope.stylesPrefsName(hashA)
        store.fileOf(stylesFile)[AccountScope.stylesKey()] = "bad-styles"
        repeat(5) { AccountScope.loadStyles(ctx, hashA) }
        assertEquals(
            1,
            store.fileOf(stylesFile).keys.count { it.startsWith(AccountScope.corruptPrefix()) }
        )
        assertFalse(store.fileOf(stylesFile).containsKey(AccountScope.stylesKey()))
    }

    @Test
    fun good_payload_is_not_quarantined() {
        val store = FakePrefsStore()
        val ctx = store.context()
        AccountScope.saveAgents(ctx, hashA, listOf(agent("agent:1", "fine")))
        AccountScope.loadAgents(ctx, hashA)
        assertTrue(
            store.fileOf(AccountScope.agentsPrefsName(hashA)).keys
                .none { it.startsWith(AccountScope.corruptPrefix()) }
        )
    }

    // ---------------- never signed in --------------------------------------

    @Test
    fun no_account_means_no_file_is_opened() {
        val store = FakePrefsStore()
        val ctx = store.context()
        assertEquals(emptyList<AccountScope.AccountAgent>(), AccountScope.loadAgents(ctx, null))
        assertEquals(emptyList<AccountScope.AccountStyle>(), AccountScope.loadStyles(ctx, null))
        assertEquals(
            "a never-signed-in user must not have any account file created",
            emptySet<String>(),
            store.names()
        )
    }

    @Test
    fun invalid_hash_opens_no_file() {
        val store = FakePrefsStore()
        val ctx = store.context()
        assertEquals(emptyList<AccountScope.AccountAgent>(), AccountScope.loadAgents(ctx, "aaaa"))
        assertEquals(emptyList<AccountScope.AccountAgent>(), AccountScope.loadAgents(ctx, "../escape"))
        assertEquals(emptyList<AccountScope.AccountStyle>(), AccountScope.loadStyles(ctx, ""))
        assertEquals(emptySet<String>(), store.names())
    }
}
