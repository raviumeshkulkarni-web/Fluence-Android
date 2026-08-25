package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §27 categories: fixture parity (shared examples/sync/v1/ corpus parses and
 * re-serializes byte-identically), corruption auto-skip (null on malformed /
 * wrong version), unknown settings keys skipped, deterministic ordering.
 */
class DomainSerializerFixtureTest {

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/sync/v1/$name.json")) {
            "missing fixture sync/v1/$name.json"
        }.readBytes()

    // ------------------------------------------------------------------
    // Dictionary
    // ------------------------------------------------------------------

    @Test
    fun dictionary_fixture_parses_and_reserializes_byte_identical() {
        val bytes = fixture("dictionary")
        val domain = DomainSerializer.parseDictionary(bytes)
        assertNotNull(domain)
        assertEquals(3, domain!!.entries.size)
        val reserialized = DomainSerializer.serializeDictionary(domain)
        // Byte-fidelity modulo trailing line terminator (fixtures are CRLF).
        assertEquals(String(bytes, Charsets.UTF_8).trim(), reserialized.trim())
    }

    @Test
    fun dictionary_fixture_carries_v11_fields() {
        val domain = DomainSerializer.parseDictionary(fixture("dictionary"))!!
        val disabled = domain.entries.first { it.businessKey == "asap" }
        assertFalse(disabled.isEnabled)
        assertNull(disabled.deletedAt)
        val deleted = domain.entries.first { it.businessKey == "teh" }
        assertTrue(deleted.deletedAt != null)
        assertTrue(deleted.tombstoneBit == 1)
    }

    // ------------------------------------------------------------------
    // Snippets — expansion byte-exact
    // ------------------------------------------------------------------

    @Test
    fun snippet_fixture_parses_and_preserves_expansion_verbatim() {
        val bytes = fixture("snippets")
        val domain = DomainSerializer.parseSnippets(bytes)
        assertNotNull(domain)
        assertEquals("123 Example Street, Springfield", domain!!.entries[0].expansion)
        assertEquals(String(bytes, Charsets.UTF_8).trim(), DomainSerializer.serializeSnippets(domain).trim())
    }

    // ------------------------------------------------------------------
    // Stats / Settings
    // ------------------------------------------------------------------

    @Test
    fun stats_fixture_parses_sorted_by_day_then_eventId() {
        val bytes = fixture("stats")
        val domain = DomainSerializer.parseStats(bytes)
        assertNotNull(domain)
        assertEquals(listOf("2026-08-20", "2026-08-21"), domain!!.entries.map { it.day })
        assertEquals(String(bytes, Charsets.UTF_8).trim(), DomainSerializer.serializeStats(domain).trim())
    }

    @Test
    fun settings_fixture_contains_exactly_the_frozen_five() {
        val domain = DomainSerializer.parseSettings(fixture("settings"))
        assertNotNull(domain)
        assertEquals(SettingsRecord.ALLOWED_KEYS, domain!!.entries.map { it.key }.toSet())
        assertEquals(String(bytesOf("settings"), Charsets.UTF_8).trim(), DomainSerializer.serializeSettings(domain).trim())
    }

    private fun bytesOf(name: String) = fixture(name)

    // ------------------------------------------------------------------
    // Corruption auto-skip contract: parse returns null, never throws
    // ------------------------------------------------------------------

    @Test
    fun malformed_json_returns_null_for_every_domain() {
        val garbage = "{ not json".toByteArray()
        assertNull(DomainSerializer.parseDictionary(garbage))
        assertNull(DomainSerializer.parseSnippets(garbage))
        assertNull(DomainSerializer.parseStats(garbage))
        assertNull(DomainSerializer.parseSettings(garbage))
    }

    @Test
    fun wrong_version_returns_null() {
        val v2 = """{"v":2,"entries":[]}""".toByteArray()
        assertNull(DomainSerializer.parseDictionary(v2))
        assertNull(DomainSerializer.parseSnippets(v2))
        assertNull(DomainSerializer.parseStats(v2))
        assertNull(DomainSerializer.parseSettings(v2))
    }

    @Test
    fun missing_entries_array_treated_as_empty_domain() {
        val empty = """{"v":1}""".toByteArray()
        assertEquals(0, DomainSerializer.parseDictionary(empty)!!.entries.size)
        assertEquals(0, DomainSerializer.parseSnippets(empty)!!.entries.size)
        assertEquals(0, DomainSerializer.parseStats(empty)!!.entries.size)
        assertEquals(0, DomainSerializer.parseSettings(empty)!!.entries.size)
    }

    @Test
    fun unknown_settings_key_is_skipped_not_fatal() {
        val raw = """{"v":1,"entries":[{"key":"theme","value":"dark","updatedAt":1,"deviceId":"d"},{"key":"language","value":"en","updatedAt":2,"deviceId":"d"}]}"""
        val domain = DomainSerializer.parseSettings(raw.toByteArray())
        assertEquals(listOf("language"), domain!!.entries.map { it.key })
    }

    @Test
    fun serialization_is_deterministic_regardless_of_input_order() {
        val a = DomainSerializer.parseDictionary(fixture("dictionary"))!!
        val shuffled = a.copy(entries = a.entries.reversed())
        assertEquals(
            DomainSerializer.serializeDictionary(a),
            DomainSerializer.serializeDictionary(shuffled)
        )
    }
}
