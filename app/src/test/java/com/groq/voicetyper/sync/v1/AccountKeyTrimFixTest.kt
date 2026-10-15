package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Audit follow-up (reviewer R-2): the account-key fold must trim the SAME
 * character set on both platforms, because the trimmed string is hashed into
 * `account_hash`, which is the key for the account's entire local partition.
 *
 * Windows `metadata.rs::normalize_account_key` uses Rust `str::trim`, i.e. the
 * Unicode `White_Space` property. Kotlin's `String.trim()` uses
 * `Char.isWhitespace()`, and the two sets differ in BOTH directions:
 *
 *  - Kotlin MISSES NEL U+0085 (Java's `Character.isWhitespace` excludes it),
 *    so a NEL-padded address would hash differently here than on Windows.
 *  - Kotlin EXTRA-trims the C0 separators U+001C-U+001F, which Unicode does not
 *    consider whitespace.
 *
 * Either direction produces a different SHA-256, hence a different partition
 * directory, hence permanent silent divergence of settings ownership,
 * dictionary rows and stats attribution — total, not per-key.
 */
class AccountKeyTrimFixTest {

    private fun n() = 0x85.toChar()          // NEL — Rust trims, Kotlin did not
    private fun c0() = 0x1C.toChar()        // FILE SEPARATOR — Kotlin trimmed, Rust does not

    @Test
    fun nel_is_trimmed_so_both_platforms_hash_the_same_key() {
        val padded = "${n()}user@example.com${n()}"
        assertEquals(
            "NEL must be trimmed, otherwise Android and Windows derive " +
                "different account_hash values for the same account",
            "user@example.com", AccountHash.normalizeAccountKey(padded)
        )
    }

    @Test
    fun c0_separators_are_not_trimmed_so_both_platforms_hash_the_same_key() {
        val padded = "${c0()}user@example.com${c0()}"
        assertEquals(
            "U+001C is NOT Unicode White_Space, so it must survive the trim; " +
                "Kotlin's String.trim() would silently strip it and re-key the partition",
            "${c0()}user@example.com${c0()}", AccountHash.normalizeAccountKey(padded)
        )
    }

    @Test
    fun ordinary_and_ideographic_space_still_trim_as_before() {
        // The realistic paste cases must keep working after the predicate swap.
        assertEquals("user@example.com", AccountHash.normalizeAccountKey("  user@example.com  "))
        assertEquals("user@example.com", AccountHash.normalizeAccountKey(" user@example.com "))
        assertEquals("user@example.com", AccountHash.normalizeAccountKey("　user@example.com　"))
    }

    @Test
    fun case_folding_remains_locale_independent() {
        // Turkish locale must not fold I -> dotless ı and re-key a partition.
        assertEquals("user@example.com", AccountHash.normalizeAccountKey("USER@Example.COM"))
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            assertEquals("user@example.com", AccountHash.normalizeAccountKey("USER@Example.COM"))
        } finally {
            java.util.Locale.setDefault(prev)
        }
    }

    @Test
    fun the_hash_actually_changes_when_the_trim_set_changes() {
        // Premise guard: prove the trim really is load-bearing for the hash, so
        // the tests above are not asserting something cosmetic.
        val nel = AccountHash.of("${n()}user@example.com${n()}")
        val plain = AccountHash.of("user@example.com")
        assertEquals(plain, nel)
        val c0Hash = AccountHash.of("${c0()}user@example.com${c0()}")
        assertNotEquals("a preserved C0 separator MUST change the key", plain, c0Hash)
    }
}