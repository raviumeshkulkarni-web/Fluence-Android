package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C4 — UUID admission must be byte-for-byte equivalent to Rust
 * `Uuid::parse_str`, which is what Windows uses to validate `syncId` and the
 * agent/style business key.
 *
 * That function dispatches purely on length: 32 hex digits ("simple") or the
 * canonical 8-4-4-4-12 hyphenated form ("hyphenated"); every other length is
 * rejected outright.
 *
 * `java.util.UUID.fromString`, which Android used to call, differs in BOTH
 * directions, so this is not a one-sided tightening:
 *
 *  - it ACCEPTS arbitrary group lengths. `1-1-1-1-1` becomes
 *    `00000001-0001-0001-0001-000000000001` on Android and is rejected on
 *    Windows, so a hand-edited or corrupt Drive file was admitted on one
 *    platform only;
 *  - it REJECTS the 32-digit simple form that Windows ACCEPTS. A fix that simply
 *    demanded the canonical hyphenated shape would have closed the first gap
 *    while opening this one, which is why [isStrictUuid] honours both shapes
 *    Windows honours and nothing else.
 *
 * The vectors below were confirmed against this JDK before the change; the
 * "premise" test keeps that observation honest.
 */
class StrictUuidParityTest {

    private val canonicalLower = "01234567-89ab-cdef-0123-456789abcdef"
    private val canonicalUpper = canonicalLower.uppercase()
    private val simple32 = "0123456789abcdef0123456789abcdef"

    // ------------------------------------------------------------------
    // What Windows accepts
    // ------------------------------------------------------------------

    @Test
    fun canonical_hyphenated_is_accepted() {
        assertTrue(canonicalLower, isStrictUuid(canonicalLower))
        assertTrue(canonicalUpper, isStrictUuid(canonicalUpper))
    }

    @Test
    fun the_32_digit_simple_form_is_accepted_like_windows() {
        // Rust dispatches length 32 to `parse_simple`. If this ever fails, a
        // fix has tightened past the Windows contract.
        assertTrue(simple32, isStrictUuid(simple32))
        assertTrue(simple32.uppercase(), isStrictUuid(simple32.uppercase()))
    }

    // ------------------------------------------------------------------
    // What Windows rejects, but java.util.UUID.fromString accepted
    // ------------------------------------------------------------------

    @Test
    fun lenient_group_lengths_are_rejected() {
        for (s in listOf("1-1-1-1-1", "1-2-3-4-5", "a-b-c-d-e")) {
            assertFalse("'$s' must be rejected", isStrictUuid(s))
        }
    }

    @Test
    fun wrong_lengths_are_rejected() {
        for (s in listOf(
            "", "abc",
            canonicalLower.dropLast(1),                        // 35
            canonicalLower + "0",                              // 37
            "01234567-89ab-cdef-0123-456789abcdefff",          // 38
            simple32.dropLast(1),                              // 31
            simple32 + "0",                                    // 33
        )) {
            assertFalse("len=${s.length} '$s' must be rejected", isStrictUuid(s))
        }
    }

    @Test
    fun non_hex_characters_are_rejected() {
        for (s in listOf(
            "0123456789abcdef0123456789abcdeg",
            "01234567-89ab-cdef-0123-456789abcdeg",
            "g1234567-89ab-cdef-0123-456789abcdef",
            "0123456g-89ab-cdef-0123-456789abcdef",
        )) {
            assertFalse("'$s' must be rejected", isStrictUuid(s))
        }
    }

    @Test
    fun misplaced_hyphens_are_rejected() {
        // Same length and same character set, wrong hyphen positions.
        for (s in listOf(
            "0123456789abc-def0123-4567-89abcdef0",
            "0123456-789ab-cdef-0123-456789abcdef",
            "-1234567-89ab-cdef-0123-456789abcdef",
            "01234567-89ab-cdef-0123-456789abcdef-",
        )) {
            assertFalse("'$s' must be rejected", isStrictUuid(s))
        }
    }

    // ------------------------------------------------------------------
    // The production call sites
    // ------------------------------------------------------------------

    @Test
    fun agent_business_keys_match_the_windows_rule() {
        assertTrue(AgentRecord.isValidBusinessKey("agent:$canonicalLower"))
        assertTrue("the simple form must be honoured too", AgentRecord.isValidBusinessKey("agent:$simple32"))
        assertFalse(AgentRecord.isValidBusinessKey("agent:1-1-1-1-1"))
        assertFalse(AgentRecord.isValidBusinessKey("agent:${canonicalLower}0"))
        assertFalse(AgentRecord.isValidBusinessKey("agent:"))
        assertFalse(AgentRecord.isValidBusinessKey("style:$canonicalLower"))
    }

    @Test
    fun style_business_keys_match_the_windows_rule() {
        assertTrue(StyleRecord.isValidBusinessKey("custom:$canonicalLower"))
        assertTrue("the simple form must be honoured too", StyleRecord.isValidBusinessKey("custom:$simple32"))
        assertFalse(StyleRecord.isValidBusinessKey("custom:1-1-1-1-1"))
        assertFalse(StyleRecord.isValidBusinessKey("custom:${canonicalLower}0"))
        assertFalse(StyleRecord.isValidBusinessKey("custom:"))
        assertFalse(StyleRecord.isValidBusinessKey("agent:$canonicalLower"))
    }

    // ------------------------------------------------------------------
    // Premise: the JDK behaviour this replaced
    // ------------------------------------------------------------------

    @Test
    fun premise_the_jdk_uuid_parser_is_the_lenient_one_we_stopped_trusting() {
        // If a future JDK tightens `fromString`, this test reports the change
        // rather than letting the two validators silently converge by accident.
        assertTrue(
            "java.util.UUID.fromString accepts 1-1-1-1-1; isStrictUuid must not",
            runCatching { java.util.UUID.fromString("1-1-1-1-1") }.isSuccess
        )
        assertTrue(
            "java.util.UUID.fromString rejects the 32-digit simple form that Rust accepts",
            runCatching { java.util.UUID.fromString(simple32) }.isFailure
        )
    }
}
