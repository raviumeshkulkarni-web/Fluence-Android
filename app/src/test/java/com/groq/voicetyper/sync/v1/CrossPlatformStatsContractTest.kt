package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CrossPlatformStatsContractTest {

    @Test
    fun windowsAuthoredStatsItemWithoutUpdatedAtOrDeviceId_parses() {
        val json = """{"v":1,"entries":[{"eventId":"11111111-1111-4111-8111-111111111111","day":"2026-01-02","timestampMs":1000}]}""" + "\n"
        val bytes = json.toByteArray(Charsets.UTF_8)
        val domain = DomainSerializer.parseStats(bytes)
        assertNotNull(domain)
        assertEquals(1, domain!!.entries.size)
        assertEquals("11111111-1111-4111-8111-111111111111", domain.entries[0].eventId)
    }

    @Test
    fun unknownLegacyFieldsAreIgnored() {
        val json = """{"v":1,"entries":[{"eventId":"11111111-1111-4111-8111-111111111111","day":"2026-01-02","timestampMs":1000,"hotkey":"ctrl","secret":"x"}]}""" + "\n"
        val bytes = json.toByteArray(Charsets.UTF_8)
        val domain = DomainSerializer.parseStats(bytes)
        assertNotNull(domain)
        assertEquals(1, domain!!.entries.size)
        val rec = domain!!.entries[0]
        assertEquals("11111111-1111-4111-8111-111111111111", rec.eventId)
        assertEquals("2026-01-02", rec.day)
        assertEquals(1000L, rec.timestampMs)
    }

    @Test
    fun spokenWith3000EmojiPassesCodePointCheck() {
        val spoken = String(Character.toChars(0x1F600)).repeat(3000) // 3000 code points, 6000 UTF-16 units
        val record = DictionaryRecord(
            syncId = "33333333-3333-4333-8333-333333333333",
            businessKey = DictionaryRecord.businessKeyOf(spoken),
            spoken = spoken,
            corrected = "x",
            isEnabled = true,
            updatedAt = 1L,
            deletedAt = null,
            deviceId = "d"
        )
        val domain = DictionaryDomain(entries = listOf(record))
        val bytes = DomainSerializer.serializeDictionary(domain).toByteArray(Charsets.UTF_8)
        val parsed = DomainSerializer.parseDictionary(bytes)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.entries.size)
        assertEquals(spoken, parsed.entries[0].spoken)
    }

    @Test
    fun serializeThenParse_roundtripsSuperset() {
        val original = StatRecord(
            eventId = "22222222-2222-4222-8222-222222222222",
            day = "2026-01-02",
            wordCount = 42,
            durationMs = 5000L,
            updatedAt = 999L,
            deviceId = "d",
            deletedAt = null,
            timestampMs = 123L,
            chars = 7
        )
        val bytes = DomainSerializer.serializeStats(StatsDomain(entries = listOf(original))).toByteArray(Charsets.UTF_8)
        val parsed = DomainSerializer.parseStats(bytes)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.entries.size)
        val round = parsed.entries[0]
        assertEquals(original.eventId, round.eventId)
        assertEquals(original.day, round.day)
        assertEquals(original.timestampMs, round.timestampMs)
        assertEquals(original.wordCount, round.wordCount)
        assertEquals(original.chars, round.chars)
        assertEquals(original.durationMs, round.durationMs)
        assertEquals(original.updatedAt, round.updatedAt)
        assertEquals(original.deviceId, round.deviceId)
    }
}
