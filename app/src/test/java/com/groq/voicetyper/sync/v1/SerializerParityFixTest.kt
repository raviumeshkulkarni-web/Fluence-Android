package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fix #11 - Android record validation must agree with the Rust twin
 * (`src-tauri/src/sync/domain.rs`) record for record.
 *
 * Before this change Android admitted records Rust skipped, so the two
 * platforms disagreed about what exists in the domain: one side kept a row
 * the other had already dropped, and each re-pushed it on every sync.
 *
 *  a) `corrected` - Android used `isNotEmpty()`, Rust uses
 *     `corrected.trim().is_empty()` (domain.rs:133): a whitespace-only
 *     correction is INVALID in Rust.
 *  b) `spoken`/`trigger`/`name` - Android used `isNotBlank()` (Java
 *     `Character.isWhitespace`, which EXCLUDES NBSP), Rust uses
 *     `str::trim` (which INCLUDES it).
 *  c) `day` - Android checked only `day.length == 10`, so "2026-99-99"
 *     passed; Rust parses a real calendar date (domain.rs:379-384). Android
 *     already owns that strict validator as `StatsShards.monthOfDay`, so it
 *     is reused instead of duplicated.
 *  d) UUID admission stays deliberately fail-closed: only the 32-digit
 *     simple form and the 36-char hyphenated form are accepted. Rust's
 *     `Uuid::parse_str` also accepts 38-char braced and 45-char URN forms,
 *     which this client never emits; Android rejects them. These tests pin
 *     that DECIDED divergence so neither side "fixes" it by accident.
 */
class SerializerParityFixTest {

    private val stamp = 1_700_000_000_000L
    private val hyphenated = "11111111-1111-4111-8111-111111111111"
    private val simple32 = "0123456789abcdef0123456789abcdef"

    /**
     * JSON string literal for [s].
     *
     * Must ESCAPE, not merely wrap: the blank-value vectors below include "\t"
     * and "\n", and a raw control character inside a JSON string literal is
     * malformed JSON — `JSONObject` throws, `parseDictionary` returns null, and
     * the test would fail on `assertNotNull` while never reaching the
     * whitespace validation it exists to exercise.
     */
    private fun q(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\b' -> sb.append("\\b")
                '' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private fun dictPayload(
        spoken: String = "hello",
        corrected: String = "hi",
        syncId: String = hyphenated,
        deletedAt: String = "null",
        updatedAt: Long = stamp
    ): ByteArray = (
        """{"v":1,"entries":[{"syncId":${q(syncId)},"businessKey":"ignored",""" +
            """"spoken":${q(spoken)},"corrected":${q(corrected)},"isEnabled":true,""" +
            """"updatedAt":$updatedAt,"deletedAt":$deletedAt,"deviceId":"dev-a"}]}"""
        ).toByteArray(Charsets.UTF_8)

    private fun snipPayload(
        trigger: String = "addr",
        expansion: String = "123 Main St",
        syncId: String = hyphenated
    ): ByteArray = (
        """{"v":1,"entries":[{"syncId":${q(syncId)},"businessKey":"ignored",""" +
            """"trigger":${q(trigger)},"expansion":${q(expansion)},"isEnabled":true,""" +
            """"updatedAt":$stamp,"deletedAt":null,"deviceId":"dev-a"}]}"""
        ).toByteArray(Charsets.UTF_8)

    private fun statsPayload(
        day: String,
        eventId: String = hyphenated
    ): ByteArray = (
        """{"v":1,"entries":[{"eventId":${q(eventId)},"day":${q(day)},"timestampMs":1000,""" +
            """"words":10,"chars":40,"durationMs":5000}]}"""
        ).toByteArray(Charsets.UTF_8)

    private fun agentPayload(name: String, syncId: String = hyphenated): ByteArray = (
        """{"v":1,"entries":[{"syncId":${q(syncId)},"businessKey":"agent:$hyphenated",""" +
            """"name":${q(name)},"hint":"h","updatedAt":$stamp,"deletedAt":null,"deviceId":"dev-a"}]}"""
        ).toByteArray(Charsets.UTF_8)

    // ==================================================================
    // (a) corrected - Rust: corrected.trim().is_empty() -> invalid
    // ==================================================================

    @Test
    fun a_whitespace_only_corrected_is_skipped_like_rust() {
        // Android used `isNotEmpty()`, so " " was admitted while Rust dropped
        // it. `runCatching` hides the per-record skip, so assert the count.
        for (blank in listOf(" ", "\t", "\n", 0xA0.toChar().toString(), 0x3000.toChar().toString(), " \t ")) {
            val parsed = DomainSerializer.parseDictionary(dictPayload(corrected = blank))
            assertNotNull(parsed)
            assertEquals("corrected=${blank.map { "U+%04X".format(it.code) }} must be skipped", 0, parsed!!.entries.size)
        }
    }

    @Test
    fun a_valid_corrected_is_still_kept_and_stored_verbatim() {
        val parsed = DomainSerializer.parseDictionary(dictPayload(corrected = "  hi there  "))!!
        assertEquals(1, parsed.entries.size)
        assertEquals("payload stays byte-exact; only validation looks at the trim", "  hi there  ", parsed.entries[0].corrected)
    }

    @Test
    fun a_one_bad_record_is_skipped_while_the_good_one_survives() {
        val json = (
            """{"v":1,"entries":[""" +
                """{"syncId":"$hyphenated","businessKey":"ignored","spoken":"hello","corrected":" ",""" +
                """"isEnabled":true,"updatedAt":$stamp,"deletedAt":null,"deviceId":"dev-a"},""" +
                """{"syncId":"22222222-2222-4222-8222-222222222222","businessKey":"ignored",""" +
                """"spoken":"gonna","corrected":"going to","isEnabled":true,""" +
                """"updatedAt":$stamp,"deletedAt":null,"deviceId":"dev-a"}]}"""
            ).toByteArray(Charsets.UTF_8)
        val parsed = DomainSerializer.parseDictionary(json)!!
        assertEquals(1, parsed.entries.size)
        assertEquals("gonna", parsed.entries[0].businessKey)
    }

    // ==================================================================
    // (b) spoken / trigger / name - Rust: str::trim (INCLUDES NBSP)
    // ==================================================================

    @Test
    fun b_spoken_that_is_only_unicode_whitespace_is_skipped_like_rust() {
        for (cp in listOf(0xA0, 0x3000, 0x2028, 0x2029, 0x85, 0x1680, 0x202F, 0x205F, 0x2003)) {
            val blank = (cp.toChar().toString() + " ".repeat(2))
            val parsed = DomainSerializer.parseDictionary(dictPayload(spoken = blank))
            assertNotNull(parsed)
            assertEquals("spoken=U+%04X must be skipped".format(cp), 0, parsed!!.entries.size)
        }
    }

    @Test
    fun b_snippet_trigger_that_is_only_unicode_whitespace_is_skipped_like_rust() {
        for (cp in listOf(0xA0, 0x3000, 0x2028)) {
            val blank = cp.toChar().toString()
            val parsed = DomainSerializer.parseSnippets(snipPayload(trigger = blank))
            assertNotNull(parsed)
            assertEquals("trigger=U+%04X must be skipped".format(cp), 0, parsed!!.entries.size)
        }
    }

    @Test
    fun b_snippet_expansion_keeps_rusts_is_empty_rule_not_a_trim() {
        // Deliberate NON-alignment: Rust checks `expansion.is_empty()`, never
        // `expansion.trim()`. An all-NBSP expansion is therefore VALID in
        // Rust and must stay valid here - tightening it would drop rows the
        // other platform keeps.
        val parsed = DomainSerializer.parseSnippets(snipPayload(expansion = 0xA0.toChar().toString()))
        assertNotNull(parsed)
        assertEquals(1, parsed!!.entries.size)
        assertEquals(0xA0.toChar().toString(), parsed.entries[0].expansion)
    }

    @Test
    fun b_agent_and_style_name_that_is_only_unicode_whitespace_is_skipped_like_rust() {
        // Rust `AgentItem::validate` / `StyleItem::validate` use
        // `name.trim().is_empty()`, so an NBSP-only name is invalid there.
        val blank = 0xA0.toChar().toString()
        assertEquals(0, DomainSerializer.parseAgents(agentPayload(name = blank))!!.entries.size)
        val styleJson = (
            """{"v":1,"entries":[{"syncId":"$hyphenated","businessKey":"custom:$hyphenated",""" +
                """"name":${q(blank)},"hint":"h","updatedAt":$stamp,"deletedAt":null,"deviceId":"dev-a"}]}"""
            ).toByteArray(Charsets.UTF_8)
        assertEquals(0, DomainSerializer.parseStyles(styleJson)!!.entries.size)
        assertEquals(1, DomainSerializer.parseAgents(agentPayload(name = "Translator"))!!.entries.size)
    }

    @Test
    fun b_a_padded_but_real_spoken_is_still_admitted_and_kept_verbatim() {
        val spoken = " " + 0x3000.toChar() + "hello" + 0xA0.toChar() + " "
        val parsed = DomainSerializer.parseDictionary(dictPayload(spoken = spoken))!!
        assertEquals(1, parsed.entries.size)
        assertEquals("hello", parsed.entries[0].businessKey)
        assertEquals(spoken, parsed.entries[0].spoken)
    }

    // ==================================================================
    // (c) day - Rust: NaiveDate::parse_from_str (a real calendar date)
    // ==================================================================

    @Test
    fun c_impossible_calendar_days_are_skipped() {
        // Android only checked `day.length == 10`, so these all passed here
        // and were dropped by Windows.
        for (day in listOf(
            "2026-99-99", "2026-13-01", "2026-00-10", "2026-02-30", "2023-02-29",
            "2026-04-31", "2026-06-31", "9999-99-99", "abcd-ef-gh", "2026-08-20 "
        )) {
            val parsed = DomainSerializer.parseStats(statsPayload(day = day))
            assertNotNull(parsed)
            assertEquals("day='$day' must be skipped", 0, parsed!!.entries.size)
        }
    }

    @Test
    fun c_real_calendar_days_are_admitted_including_leap_days() {
        for (day in listOf("2024-02-29", "2000-02-29", "2026-01-01", "2026-08-20", "2026-12-31", "1970-01-01")) {
            val parsed = DomainSerializer.parseStats(statsPayload(day = day))
            assertNotNull(parsed)
            assertEquals("day='$day' must be admitted", 1, parsed!!.entries.size)
            assertEquals(day, parsed.entries[0].day)
        }
    }

    @Test
    fun c_day_validation_is_the_shard_routing_validator_not_a_second_copy() {
        // The point of the fix: one strict validator. If `monthOfDay` ever
        // changes, ingest changes with it instead of drifting apart.
        for (day in listOf("2026-99-99", "2026-02-30", "2023-02-29", "2026-1-01", "nope", "")) {
            assertEquals("monthOfDay('$day')", null, StatsShards.monthOfDay(day))
            assertEquals("'$day' must be skipped by ingest", 0, DomainSerializer.parseStats(statsPayload(day = day))!!.entries.size)
        }
        for (day in listOf("2024-02-29", "2026-08-20")) {
            assertEquals("monthOfDay('$day')", day.substring(0, 7), StatsShards.monthOfDay(day))
            assertEquals(1, DomainSerializer.parseStats(statsPayload(day = day))!!.entries.size)
        }
    }

    // ==================================================================
    // (d) strict UUID admission - DECIDED: braced / URN are rejected
    // ==================================================================

    @Test
    fun d_braced_and_urn_uuid_forms_are_rejected_while_both_shipped_forms_pass() {
        val braced = "{" + hyphenated + "}"
        val urn = "urn:uuid:" + hyphenated
        // Premise (Rust `uuid` crate, `Uuid::parse_str`): length 38 dispatches
        // to the braced form and 45 to the URN form, so WINDOWS accepts both.
        // Android is DECIDED fail-closed on them - this client never emits
        // them, and accepting them would widen the ingest surface for nothing.
        assertEquals(38, braced.length)
        assertEquals(45, urn.length)
        assertFalse("braced form must be rejected", isStrictUuid(braced))
        assertFalse("URN form must be rejected", isStrictUuid(urn))
        assertTrue("36-char hyphenated is the shape both platforms emit", isStrictUuid(hyphenated))
        assertTrue("32-digit simple is accepted by Rust too", isStrictUuid(simple32))
    }

    @Test
    fun d_a_braced_sync_id_is_skipped_at_ingest_while_the_simple_form_survives() {
        val braced = "{" + hyphenated + "}"
        assertEquals(0, DomainSerializer.parseDictionary(dictPayload(syncId = braced))!!.entries.size)
        assertEquals(0, DomainSerializer.parseSnippets(snipPayload(syncId = braced))!!.entries.size)
        assertEquals(0, DomainSerializer.parseStats(statsPayload(day = "2026-08-20", eventId = braced))!!.entries.size)
        assertEquals(1, DomainSerializer.parseDictionary(dictPayload(syncId = simple32))!!.entries.size)
        assertEquals(1, DomainSerializer.parseDictionary(dictPayload(syncId = hyphenated))!!.entries.size)
    }

    @Test
    fun d_agent_business_keys_reject_the_rejected_forms_too() {
        assertFalse(AgentRecord.isValidBusinessKey("agent:{$hyphenated}"))
        assertFalse(StyleRecord.isValidBusinessKey("custom:urn:uuid:$hyphenated"))
        assertTrue(AgentRecord.isValidBusinessKey("agent:$simple32"))
    }
}
