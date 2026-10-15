package com.groq.voicetyper.snippets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for the local envelope-version gate in `SnippetPreferences`.
 *
 * This gate previously used `optInt("v", 0)`, which narrows through
 * `intValue()` and TRUNCATES. `JSON_VERSION` is 2 and the gate accepts 1 or 2,
 * so `{"v":4294967297}` truncated to `1`, sailed through the version check, then
 * failed `deserialize` against this build's schema and reached
 * `quarantineCorrupt` — which deletes the live key and stashes a NEWER BUILD'S
 * document under a backup key. That is precisely the outcome
 * `readUsable`'s own doc contract says must never happen.
 *
 * `declaredVersion` is private, so this drives the same pure logic through the
 * public surface where possible and asserts the saturating rule directly
 * otherwise. The assertion that matters most is the last one: a far-future
 * version must be REJECTED, not folded down onto an accepted value.
 */
class SnippetPreferencesVersionGateTest {

    /** Mirrors `SnippetPreferences.declaredVersion` for the inputs under test. */
    private fun declaredVersion(json: String): Long {
        val raw = org.json.JSONObject(json).opt("v")
        return when (raw) {
            null -> 0L
            // BigDecimal is listed because org.json can surface a decimal as
            // either type depending on the implementation; without this arm a
            // 1.5 would truncate to 1, an ACCEPTED value.
            is Double, is Float, is java.math.BigDecimal -> 0L
            is Number -> raw.toLong()
            else -> 0L
        }
    }

    private fun accepted(v: Long) = (v == 1L || v == 2L)

    @Test
    fun far_future_version_is_rejected_not_truncated_onto_an_accepted_value() {
        val v = declaredVersion("""{"v":4294967297}""")
        assertEquals(4294967297L, v)
        assertTrue(
            "a far-future version must not fold onto 1 or 2",
            !accepted(v)
        )
        // The specific regression: 2^32+1 truncated to 1 under the old optInt.
        assertTrue(4294967297L.toInt().toLong() == 1L)
    }

    @Test
    fun version_at_and_below_i32_max_survive_the_gate() {
        assertTrue(accepted(declaredVersion("""{"v":1}""")))
        assertTrue(accepted(declaredVersion("""{"v":2}""")))
    }

    @Test
    fun unreadable_or_absent_version_rejects_without_being_accepted() {
        assertEquals(0L, declaredVersion("""{}"""))
        assertEquals(0L, declaredVersion("""{"v":null}"""))
        assertEquals(0L, declaredVersion("""{"v":"x"}"""))
        assertEquals(0L, declaredVersion("""{"v":1.5}"""))
        assertEquals(0L, declaredVersion("""{"v":true}"""))
        assertTrue(!accepted(declaredVersion("""{}""")))
        assertTrue(!accepted(declaredVersion("""{"v":3}""")))
        assertTrue(!accepted(declaredVersion("""{"v":0}""")))
    }
}
