package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * F31 — the Android structural roundtrip guard.
 *
 * The engine used to self-check only record COUNT plus the businessKey/eventId
 * SET, which cannot see a dropped `corrected`, a flipped `isEnabled` or a lost
 * tombstone. Windows refuses to upload an envelope it cannot re-read into
 * identical state (`frozen.rs`: `decode(&bytes) != Some(merged)` -> `Fatal`).
 *
 * The guard added here is full structural equality expressed as a CANONICAL
 * STRING projection — `serialize(parse(serialize(x))) == serialize(x)` — never a
 * `JSONObject` comparison. That is the whole point: the device/JVM
 * null-and-number asymmetry the old comment warned about is a property of
 * `JSONObject.equals`, and neither operand of this comparison ever touches
 * org.json. Both sides are produced by the same pure `StringBuilder` writer, so
 * device and JVM run byte-identical code.
 */
class CanonicalRoundtripFixTest {

    private val past = 1_700_000_000_000L
    private val uuidA = "00000000-0000-4000-8000-000000000001"
    private val uuidB = "00000000-0000-4000-8000-000000000002"

    /**
     * Exercises the awkward cases a structural guard could regress on:
     * non-ASCII, quotes/backslashes/control bytes, a tombstone, an empty
     * optional field, and a zero-valued stats aggregate.
     */
private fun dictionaryDomain() = DictionaryDomain(
        entries = listOf(
            DictionaryRecord(uuidA, DictionaryRecord.businessKeyOf("gonna"), "gonna", "going to", true, past, null, "device-a"),
            DictionaryRecord(
                uuidB,
                DictionaryRecord.businessKeyOf("grüßen   "),
                "grüßen   ",
                "say \"hi\"\\ then\ttab",
                false,
                past,
                past,
                "device-b",
            ),
        )
    )

    private fun snippetDomain() = SnippetDomain(
        entries = listOf(
            SnippetRecord(uuidA, SnippetRecord.businessKeyOf("addr"), "addr", "123 Main St", true, past, null, "device-a"),
            SnippetRecord(uuidB, SnippetRecord.businessKeyOf("sig"), "sig", "Sent from ‡phone", false, past, past, "device-b"),
        )
    )

    private fun statsDomain() = StatsDomain(
        entries = listOf(
            StatRecord(uuidA, "2026-01-02", 5, 1_000L, past, "device-a", timestampMs = past, chars = 25),
            // Aggregate row: updatedAt == 0 and no deviceId — the optional-field
            // shape the frozen contract calls out.
            StatRecord(uuidB, "2026-01-03", 0, 0L, 0L, ""),
        )
    )

    private fun settingsDomain() = SettingsDomain(
        entries = listOf(
            SettingsRecord("language", "en-US", past, "device-a"),
            SettingsRecord("ai_polish_style", "quote \"me\"", past, "device-a", deletedAt = past),
        )
    )

    private fun agentDomain() = AgentDomain(
        entries = listOf(
            AgentRecord(uuidA, AgentRecord.PREFIX + uuidA, "Writer", "note «here»", past, null, "device-a"),
            AgentRecord(uuidB, AgentRecord.PREFIX + uuidB, "Retired", "gone", past, past, "device-b"),
        )
    )

    private fun styleDomain() = StyleDomain(
        entries = listOf(
            StyleRecord(uuidA, StyleRecord.PREFIX + uuidA, "Technical", "hint\twith tab", past, null, "device-a"),
            StyleRecord(uuidB, StyleRecord.PREFIX + uuidB, "Casual", "hint", past, past, "device-b"),
        )
    )

    @Test
    fun every_domain_roundtrips_to_identical_canonical_bytes() {
        assertCanonicalRoundtrip(
            "dictionary", dictionaryDomain(),
            DomainSerializer::serializeDictionary, DomainSerializer::parseDictionary,
        )
        assertCanonicalRoundtrip(
            "snippets", snippetDomain(),
            DomainSerializer::serializeSnippets, DomainSerializer::parseSnippets,
        )
        assertCanonicalRoundtrip(
            "stats", statsDomain(),
            DomainSerializer::serializeStats, DomainSerializer::parseStats,
        )
        assertCanonicalRoundtrip(
            "settings", settingsDomain(),
            DomainSerializer::serializeSettings, DomainSerializer::parseSettings,
        )
        assertCanonicalRoundtrip(
            "agents", agentDomain(),
            DomainSerializer::serializeAgents, DomainSerializer::parseAgents,
        )
        assertCanonicalRoundtrip(
            "styles", styleDomain(),
            DomainSerializer::serializeStyles, DomainSerializer::parseStyles,
        )
    }

    @Test
    fun the_guard_is_device_safe_because_it_never_touches_org_json() {
        // The property that makes this safe on a real device: both operands are
        // produced by the SAME pure writer. Proven by re-running the comparison
        // on the raw fixture bytes, where the "record" side is the wire text
        // rather than an in-memory record graph.
        val wire = DomainSerializer.serializeDictionary(dictionaryDomain())
        val reSerialized = DomainSerializer.serializeDictionary(DomainSerializer.parseDictionary(wire.toByteArray())!!)
        assertEquals(wire, reSerialized)
        assertTrue("the frozen envelope shape is unchanged", wire.startsWith("{\"v\":1,\"entries\":["))
        assertTrue("exactly one trailing newline", wire.endsWith("]}\n"))
    }

    /** The teeth: a parser that silently rewrites a field must be caught. */
    @Test
    fun a_parse_that_drops_a_field_is_rejected() {
        try {
            assertCanonicalRoundtrip(
                "dictionary", dictionaryDomain(),
                DomainSerializer::serializeDictionary,
            ) { bytes ->
                DomainSerializer.parseDictionary(bytes)?.let { parsed ->
                    // A parser that loses `corrected` — invisible to the old
                    // count + businessKey-set check.
                    parsed.copy(entries = parsed.entries.map { it.copy(corrected = "") })
                }
            }
            fail("a lost field must refuse the upload")
        } catch (e: SyncError.Rejected) {
            assertTrue(e.message!!.contains("canonical"))
        }
    }

    /** The teeth: a parser that loses a whole record must be caught. */
    @Test
    fun a_parse_that_drops_a_record_is_rejected() {
        try {
            assertCanonicalRoundtrip(
                "snippets", snippetDomain(),
                DomainSerializer::serializeSnippets,
            ) { bytes -> DomainSerializer.parseSnippets(bytes)?.copy(entries = emptyList()) }
            fail("a lost record must refuse the upload")
        } catch (e: SyncError.Rejected) {
            assertTrue(e.message!!.contains("canonical"))
        }
    }

    @Test
    fun an_unparseable_envelope_is_rejected_not_silently_uploaded() {
        try {
            assertCanonicalRoundtrip(
                "dictionary", dictionaryDomain(),
                DomainSerializer::serializeDictionary,
            ) { null }
            fail("a null parse must refuse the upload")
        } catch (e: SyncError.Rejected) {
            assertTrue(e.message!!.contains("null"))
        }
    }

    /** The guard must stay a strict superset of the check it replaced. */
    @Test
    fun the_guard_still_catches_what_the_tolerant_check_caught() {
        // A record whose updatedAt is beyond the far-future tolerance is dropped
        // at parse — the old count check rejected this, so the structural guard
        // may not regress it.
        val future = System.currentTimeMillis() + DomainSerializer.CLOCK_SKEW_TOLERANCE_MS + 60_000L
        val domain = DictionaryDomain(
            entries = listOf(
                DictionaryRecord(uuidA, DictionaryRecord.businessKeyOf("gonna"), "gonna", "going to", true, past, null, "device-a"),
                DictionaryRecord(uuidB, DictionaryRecord.businessKeyOf("tomorrow"), "tomorrow", "future", true, future, null, "device-a"),
            )
        )
        try {
            assertCanonicalRoundtrip(
                "dictionary", domain,
                DomainSerializer::serializeDictionary, DomainSerializer::parseDictionary,
            )
            fail("a record the parser drops must refuse the upload, exactly as the count check did")
        } catch (e: SyncError.Rejected) {
            assertNotNull(e.message)
        }
    }
}