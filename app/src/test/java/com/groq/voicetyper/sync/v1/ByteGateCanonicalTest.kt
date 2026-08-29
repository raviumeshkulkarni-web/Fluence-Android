package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ByteGateCanonicalTest {

    private fun fixtureBytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/sync/v1/$name.json")) { "missing fixture $name" }.readBytes()

    private fun normalizeLf(bytes: ByteArray): ByteArray =
        String(bytes, Charsets.UTF_8).replace("\r", "").toByteArray(Charsets.UTF_8)

    @Test
    fun dictionary_fixture_byte_identical() {
        val raw = fixtureBytes("dictionary")
        val domain = DomainSerializer.parseDictionary(raw)
        assertNotNull(domain)
        assertEquals(3, domain!!.entries.size)
        assertEquals(listOf("asap", "gonna", "teh"), domain.entries.map { it.businessKey })
        val reserialized = DomainSerializer.serializeDictionary(domain).toByteArray(Charsets.UTF_8)
        assertEquals(String(normalizeLf(raw), Charsets.UTF_8), String(reserialized, Charsets.UTF_8))
        assertEquals(normalizeLf(raw).toList(), reserialized.toList())
    }

    @Test
    fun snippets_fixture_byte_identical() {
        val raw = fixtureBytes("snippets")
        val domain = DomainSerializer.parseSnippets(raw)
        assertNotNull(domain)
        assertEquals(2, domain!!.entries.size)
        assertEquals(listOf("addr", "brb"), domain.entries.map { it.businessKey })
        val reserialized = DomainSerializer.serializeSnippets(domain).toByteArray(Charsets.UTF_8)
        assertEquals(String(normalizeLf(raw), Charsets.UTF_8), String(reserialized, Charsets.UTF_8))
    }

    @Test
    fun settings_fixture_byte_identical() {
        val raw = fixtureBytes("settings")
        val domain = DomainSerializer.parseSettings(raw)
        assertNotNull(domain)
        assertEquals(5, domain!!.entries.size)
        assertEquals(
            listOf("ai_polish_style", "auto_learn_enabled", "dictionary_enabled", "language", "snippets_enabled"),
            domain.entries.map { it.key }
        )
        val reserialized = DomainSerializer.serializeSettings(domain).toByteArray(Charsets.UTF_8)
        assertEquals(String(normalizeLf(raw), Charsets.UTF_8), String(reserialized, Charsets.UTF_8))
    }

    @Test
    fun stats_fixture_byte_identical() {
        val raw = fixtureBytes("stats")
        val domain = DomainSerializer.parseStats(raw)
        assertNotNull(domain)
        assertEquals(2, domain!!.entries.size)
        assertEquals(listOf("2026-08-20", "2026-08-21"), domain.entries.map { it.day })
        val reserialized = DomainSerializer.serializeStats(domain).toByteArray(Charsets.UTF_8)
        assertEquals(String(normalizeLf(raw), Charsets.UTF_8), String(reserialized, Charsets.UTF_8))
    }
}
