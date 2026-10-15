package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit follow-up (reviewer R-1): every merge tiebreak in this package must
 * compare strings in UTF-8 BYTE order, because that is what Rust's `str::cmp`
 * does on the Windows twin.
 *
 * Kotlin's `String.compareTo` is UTF-16 code-unit order and DISAGREES with
 * UTF-8 for supplementary-plane characters: it places a surrogate pair (lead
 * unit 0xD800-0xDBFF) BELOW U+E000..U+FFFF, while UTF-8 places it ABOVE. The
 * bug was live in `Clock.compareWinner` — reached by `compareSettingsWinner`,
 * `compareStatsWinner` and `isWinner` — while its sibling `Merge.kt` had
 * already been fixed. A second copy of the rule in one package is how it
 * recurs, so `compareUtf8` now lives in exactly one place (`Clock.kt`) and this
 * test pins every comparator that must use it.
 */
class Utf8TiebreakFixTest {

    // U+FFFD REPLACEMENT CHARACTER (BMP) vs U+1F600 GRINNING FACE (supplementary plane).
    private val bmp = "�"
    private val astral = "\uD83D\uDE00" // the UTF-16 surrogate pair for U+1F600

    @Test
    fun the_two_strings_really_do_diverge_under_kotlin_native_ordering() {
        // Guards the premise of this whole test. If Kotlin ever changed
        // String.compareTo semantics, the assertions below would become vacuous.
        assertTrue(
            "premise broken: String.compareTo now agrees with UTF-8, " +
                "so this test no longer proves anything",
            bmp.compareTo(astral) > 0
        )
    }

    @Test
    fun compareUtf8_orders_by_utf8_bytes_not_utf16_code_units() {
        // UTF-8: U+FFFD = EF BF BD ; U+1F600 = F0 9F 98 80. EF < F0, so the
        // astral character is LARGER — the opposite of Kotlin's native order.
        assertTrue("bmp must sort below astral under UTF-8", compareUtf8(bmp, astral) < 0)
        assertTrue("astral must sort above bmp under UTF-8", compareUtf8(astral, bmp) > 0)
    }

    @Test
    fun compareUtf8_breaks_ties_by_length_like_rust_lexicographic_ord() {
        // Equal prefix, then shorter-is-smaller — Rust's `Ord on &[u8]`.
        assertTrue(compareUtf8("ab", "abc") < 0)
        assertTrue(compareUtf8("abc", "ab") > 0)
        assertEquals(0, compareUtf8("abc", "abc"))
        // Empty vs non-empty.
        assertTrue(compareUtf8("", "a") < 0)
    }

    @Test
    fun clock_compareWinner_uses_utf8_order_for_deviceId() {
        // Windows clock.rs compares device_id with `str::cmp` (code-point
        // order). Under UTF-16 the BMP string would win; under UTF-8 the astral
        // one does. The platforms must agree.
        assertTrue(Clock.compareWinner(1000L, bmp, 1000L, astral) < 0)
        assertTrue(Clock.compareWinner(1000L, astral, 1000L, bmp) > 0)
        // updatedAt still dominates, as on both platforms.
        assertTrue(Clock.compareWinner(2000L, bmp, 1000L, astral) > 0)
    }

    @Test
    fun clock_isWinner_agrees_with_compareWinner() {
        // astral is the larger deviceId under UTF-8, so it wins regardless of
        // which side it is passed as.
        assertTrue(Clock.isWinner(1000L, astral, 1000L, bmp))
        assertFalse(Clock.isWinner(1000L, bmp, 1000L, astral))
        assertEquals(
            Clock.compareWinner(1000L, astral, 1000L, bmp) > 0,
            Clock.isWinner(1000L, astral, 1000L, bmp)
        )
    }

    @Test
    fun clock_compareSettingsWinner_uses_utf8_order_for_value() {
        assertTrue(Clock.compareSettingsWinner(1000L, "dev", bmp, 1000L, "dev", astral) < 0)
        assertTrue(Clock.compareSettingsWinner(1000L, "dev", astral, 1000L, "dev", bmp) > 0)
    }

    @Test
    fun clock_compareStatsWinner_uses_utf8_order_for_day() {
        val a = Clock.compareStatsWinner(
            1000L, "dev", bmp, 0L, 0L, 0L, 0L,
            1000L, "dev", astral, 0L, 0L, 0L, 0L
        )
        assertTrue("stats day tiebreak must be UTF-8 ordered", a < 0)
    }

    @Test
    fun merge_pick_winners_agree_with_the_clock_comparators() {
        // The dict/snippet pickers in Merge use the same canonical helper, so a
        // settings/stats divergence would show up here too.
        val lowDict = DictionaryRecord(
            syncId = "0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f01", businessKey = "k",
            spoken = "k", corrected = "A", isEnabled = true,
            updatedAt = 1000L, deletedAt = null, deviceId = bmp
        )
        val highDict = lowDict.copy(
            syncId = "0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f02",
            corrected = "Z", deviceId = astral
        )
        // On an updatedAt tie the deviceId decides, and astral > bmp in UTF-8.
        // ORDER-INDEPENDENCE is the invariant that makes the platforms converge:
        // BOTH argument orders must pick the same record, or one device keeps
        // local and the other keeps remote and they re-push forever.
        assertEquals(highDict, Merge.pickDictionaryWinner(lowDict, highDict))
        assertEquals(highDict, Merge.pickDictionaryWinner(highDict, lowDict))
    }
}
