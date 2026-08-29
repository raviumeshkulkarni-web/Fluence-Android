package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FarFutureClockTest {

    @Test
    fun future_stamped_record_skipped_per_record() {
        val now = System.currentTimeMillis()
        val future = now + DomainSerializer.CLOCK_SKEW_TOLERANCE_MS + 60_000
        // Dictionary future must be skipped, valid past still parses, whole file still parses
        val good = DictionaryRecord("00000000-0000-4000-8000-000000000001", "gonna", "gonna", "going to", true, now - 1000, null, "device-a")
        val bad = DictionaryRecord("00000000-0000-4000-8000-000000000002", "future", "future", "bad", true, future, null, "device-a")
        val domain = DictionaryDomain(entries = listOf(good, bad))
        val bytes = DomainSerializer.serializeDictionary(domain).toByteArray()
        val parsed = DomainSerializer.parseDictionary(bytes)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.entries.size)
        assertEquals("gonna", parsed.entries[0].businessKey)
        // Snippet future also skipped
        val sGood = SnippetRecord("00000000-0000-4000-8000-000000000003", "addr", "addr", "123", true, now - 1000, null, "device-a")
        val sBad = SnippetRecord("00000000-0000-4000-8000-000000000004", "future", "future", "exp", true, future, null, "device-a")
        val sDomain = SnippetDomain(entries = listOf(sGood, sBad))
        val sBytes = DomainSerializer.serializeSnippets(sDomain).toByteArray()
        val sParsed = DomainSerializer.parseSnippets(sBytes)
        assertNotNull(sParsed)
        assertEquals(1, sParsed!!.entries.size)
        // Settings future also skipped
        val setGood = SettingsRecord("language", "en", now - 1000, "device-a")
        val setBad = SettingsRecord("language", "xx", future, "device-a")
        val setDomain = SettingsDomain(entries = listOf(setGood, setBad))
        val setBytes = DomainSerializer.serializeSettings(setDomain).toByteArray()
        val setParsed = DomainSerializer.parseSettings(setBytes)
        // Settings parse filters per-record, so future bad is skipped, good remains
        // Note: Settings merge will pick winner, but parse should keep only valid
        assertNotNull(setParsed)
        // At least one valid remains (size 1 or 2 depending on filter, but future must not be present)
        assertTrue(setParsed!!.entries.none { it.value == "xx" && it.updatedAt == future })
        // Stats future also skipped per-record; updatedAt==0 aggregates stay valid (isFuture(0)=false)
        val validPast = StatRecord("00000000-0000-4000-8000-000000000006", "2026-08-20", 5, 1000, now - 1000, "device-a", timestampMs = now - 1000, chars = 25)
        val aggregate = StatRecord("00000000-0000-4000-8000-000000000007", "2026-08-21", 3, 500, 0, "device-a")
        val futureStats = StatRecord("00000000-0000-4000-8000-000000000008", "2026-08-22", 5, 1000, future, "device-a", timestampMs = now - 1000, chars = 25)
        val statsDomain = StatsDomain(entries = listOf(validPast, aggregate, futureStats))
        val statsBytes = DomainSerializer.serializeStats(statsDomain).toByteArray()
        val statsParsed = DomainSerializer.parseStats(statsBytes)
        assertNotNull(statsParsed)
        assertEquals(2, statsParsed!!.entries.size)
        assertTrue(statsParsed.entries.none { it.eventId == futureStats.eventId })
    }

    @Test
    fun within_tolerance_still_valid() {
        val now = System.currentTimeMillis()
        val within = now + DomainSerializer.CLOCK_SKEW_TOLERANCE_MS - 60_000
        val rec = DictionaryRecord("00000000-0000-4000-8000-000000000005", "hello", "hello", "hi", true, within, null, "device-a")
        val domain = DictionaryDomain(entries = listOf(rec))
        val bytes = DomainSerializer.serializeDictionary(domain).toByteArray()
        val parsed = DomainSerializer.parseDictionary(bytes)
        assertNotNull(parsed)
        assertEquals(1, parsed!!.entries.size)
    }
}
