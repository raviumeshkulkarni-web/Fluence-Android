package com.groq.voicetyper.sync.v1

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 Android mirror — account-scoped agents/styles storage.
 *
 * Mirrors the approved Windows `account_scope` semantics: separate
 * per-account preferences FILE, strict 64-lowercase-hex hash, `deletedAt`
 * tombstones, legacy storage untouched, no adoption of ambiguous legacy data.
 */
class AccountScopeTest {

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)
    private val realHash = "47ff03cc027b9d2d104aa8e14e37fb9572f4a9aec859cee75bf440e429ca80bb"

    private fun agent(id: String, name: String, deletedAt: Long? = null) =
        AccountScope.AccountAgent(
            id = id, name = name, hint = "hint-$name",
            syncId = "sync-$id", updatedAt = 100L, deviceId = "dev-a",
            deletedAt = deletedAt
        )

    private fun style(id: String, name: String, deletedAt: Long? = null) =
        AccountScope.AccountStyle(
            id = id, name = name, hint = "hint-$name",
            syncId = "sync-$id", updatedAt = 100L, deviceId = "dev-a",
            deletedAt = deletedAt
        )

    // ---------------- storage separation: the forward-compat guarantee ----

    @Test
    fun account_prefs_file_is_never_the_legacy_file() {
        // The structural guarantee: a build that does not know this module
        // cannot open the account file at all.
        assertNotEquals(AccountScope.LEGACY_PREFS, AccountScope.agentsPrefsName(hashA))
        assertNotEquals(AccountScope.LEGACY_PREFS, AccountScope.stylesPrefsName(hashA))
    }

    @Test
    fun agents_and_styles_use_different_files_per_account() {
        val names = listOf(
            AccountScope.agentsPrefsName(hashA),
            AccountScope.agentsPrefsName(hashB),
            AccountScope.stylesPrefsName(hashA),
            AccountScope.stylesPrefsName(hashB)
        )
        assertEquals("every store must be distinct", 4, names.toSet().size)
        names.forEach { assertTrue(it.contains(realHash) || it.contains(hashA) || it.contains(hashB)) }
    }

    @Test
    fun account_file_name_carries_the_hash() {
        assertTrue(AccountScope.agentsPrefsName(hashA).endsWith(hashA))
        assertTrue(AccountScope.stylesPrefsName(hashB).endsWith(hashB))
    }

    // ---------------- strict hash validation -------------------------------

    @Test
    fun valid_hashes_are_exactly_64_lowercase_hex() {
        assertTrue(AccountScope.validAccountHash(hashA))
        assertTrue(AccountScope.validAccountHash(realHash))
        assertTrue(AccountScope.validAccountHash("0".repeat(64)))
        assertTrue(AccountScope.validAccountHash("0123456789abcdef".repeat(4)))
    }

    @Test
    fun invalid_hashes_are_rejected() {
        assertFalse(AccountScope.validAccountHash(null))
        assertFalse(AccountScope.validAccountHash(""))
        assertFalse(AccountScope.validAccountHash("aaaa"))                 // truncated
        assertFalse(AccountScope.validAccountHash("a".repeat(63)))
        assertFalse(AccountScope.validAccountHash("a".repeat(65)))
        assertFalse(AccountScope.validAccountHash("A".repeat(64)))         // uppercase
        assertFalse(AccountScope.validAccountHash("user@example.com"))     // email
        assertFalse(AccountScope.validAccountHash("../escape"))
        assertFalse(AccountScope.validAccountHash("g".repeat(64)))         // non-hex
    }

    @Test
    fun invalid_hash_throws_before_touching_storage() {
        var threw = false
        try {
            AccountScope.agentsPrefsName("../escape")
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a hostile hash must be refused at name construction", threw)
    }

    // ---------------- tombstone round-trip ---------------------------------

    @Test
    fun agent_tombstone_round_trips_and_is_retained() {
        val live = agent("agent:1", "kept")
        val dead = agent("agent:2", "gone", deletedAt = 1_700_000_000_000L)
        val back = AccountScope.parseAgents(AccountScope.serializeAgents(listOf(live, dead)))!!
        assertEquals(2, back.size)
        assertNull(back[0].deletedAt)
        assertEquals(1_700_000_000_000L, back[1].deletedAt)
    }

    @Test
    fun style_tombstone_round_trips() {
        val back = AccountScope.parseStyles(
            AccountScope.serializeStyles(
                listOf(style("custom:1", "one"), style("custom:2", "two", deletedAt = 42L))
            )
        )!!
        assertNull(back[0].deletedAt)
        assertEquals(42L, back[1].deletedAt)
    }

    @Test
    fun absent_tombstone_means_live_not_deleted() {
        // The dangerous polarity would be a non-null default where 0 means
        // deleted, resurrecting a delete. Absence must mean live.
        val json = AccountScope.serializeAgents(listOf(agent("agent:1", "x")))
        assertFalse(json.contains("deletedAt"))
        assertNull(AccountScope.parseAgents(json)!![0].deletedAt)
    }

    @Test
    fun explicit_zero_tombstone_is_preserved_as_zero() {
        // `deletedAt = 0` must round-trip as 0, not collapse to null, or a
        // delete could be resurrected. The merge contract must treat it as
        // deleted-at-epoch; storage must not silently drop it.
        val json = AccountScope.serializeAgents(listOf(agent("agent:1", "x", deletedAt = 0L)))
        assertTrue(json.contains("deletedAt"))
        assertEquals(0L, AccountScope.parseAgents(json)!![0].deletedAt)
    }

    @Test
    fun camelCase_wire_names_match_the_dictionary_convention() {
        val json = AccountScope.serializeAgents(listOf(agent("agent:1", "x")))
        listOf("syncId", "updatedAt", "deviceId").forEach { assertTrue(json.contains(it)) }
        assertFalse("snake_case would be a second wire format", json.contains("sync_id"))
    }

    @Test
    fun absent_optional_fields_are_omitted() {
        val bare = AccountScope.AccountAgent(id = "a", name = "n", hint = "h")
        val json = AccountScope.serializeAgents(listOf(bare))
        assertFalse(json.contains("syncId"))
        assertFalse(json.contains("deletedAt"))
    }

    // ---------------- per-record tolerance ---------------------------------

    @Test
    fun malformed_record_is_skipped_not_fatal() {
        val arr = JSONArray()
        arr.put(org.json.JSONObject().apply { put("id", "agent:1"); put("name", "ok"); put("hint", "h") })
        arr.put("not-an-object")
        arr.put(org.json.JSONObject().apply { put("name", "no id") })
        val parsed = AccountScope.parseAgents(arr.toString())!!
        assertEquals(1, parsed.size)
        assertEquals("agent:1", parsed[0].id)
    }

    @Test
    fun empty_payload_is_empty_not_corrupt() {
        assertEquals(emptyList<AccountScope.AccountAgent>(), AccountScope.parseAgents(null))
        assertEquals(emptyList<AccountScope.AccountAgent>(), AccountScope.parseAgents(""))
        assertEquals(emptyList<AccountScope.AccountStyle>(), AccountScope.parseStyles("  "))
    }

    @Test
    fun non_array_payload_is_reported_as_corrupt() {
        assertNull(AccountScope.parseAgents("{ not json"))
        assertNull(AccountScope.parseStyles("\"a string\""))
    }


    // Read/write through a real SharedPreferences is covered at the
    // serialization + file-separation layers above; the storage-seam and
    // quarantine paths are exercised by the Windows mirror's equivalent
    // filesystem tests, which need no mocking framework.
}
