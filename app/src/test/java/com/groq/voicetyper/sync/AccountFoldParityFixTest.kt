package com.groq.voicetyper.sync

import com.groq.voicetyper.sync.v1.AccountHash
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F10 — the email fold asymmetry.
 *
 * Windows compared account keys with `key.trim().eq_ignore_ascii_case(...)`
 * (ASCII-only) while Android compared with `trim().lowercase()` (full Unicode).
 * For a non-ASCII local part — `ÜSER@example.com` vs `üser@example.com` —
 * Windows called that two identities and REPAIRED/re-bound, while Android called
 * it one identity. One account, two behaviors.
 *
 * The rule is now full-Unicode case folding on BOTH sides, in ONE place per
 * platform, and it is the same rule the partition hash already used:
 *
 *   Android : `AccountHash.normalizeAccountKey` = `email.trim().lowercase()`
 *   Windows : `metadata::normalize_account_key` = `email.trim().to_lowercase()`
 *
 * SCOPE NOTE: this fix touches the COMPARISON only. The repair-vs-abort gate
 * (Windows repairs a mismatched key and proceeds; Android aborts RETRYABLE) is
 * an explicit owner decision and is deliberately untouched.
 */
class AccountFoldParityFixTest {

    private val originalLocale: Locale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun the_fold_rule_is_trim_plus_full_unicode_lowercase() {
        assertEquals("üser@example.com", AccountHash.normalizeAccountKey("  ÜSER@example.com  "))
        assertEquals("üser@example.com", AccountHash.normalizeAccountKey("üser@example.com"))
        assertEquals("isser@example.com", AccountHash.normalizeAccountKey("ISSER@example.com"))
        // ß is NOT case-mapped to "ss" by Rust's to_lowercase or Kotlin's
        // lowercase(). Asserted explicitly so a future "improvement" cannot
        // silently re-partition accounts that already sync.
        assertEquals("straße@example.com", AccountHash.normalizeAccountKey("Straße@example.com"))
    }

    @Test
    fun a_non_ascii_local_part_is_one_identity_not_a_divergence() {
        // This is the exact case Windows' eq_ignore_ascii_case called divergent.
        assertNull(
            "ÜSER@ and üser@ are the same account once both platforms fold identically",
            identityDivergenceOutcome("ÜSER@example.com", "üser@example.com"),
        )
        assertNull(
            identityDivergenceOutcome("  ÜsEr@Example.com ", "üser@example.com"),
        )
        assertNull(identityDivergenceOutcome("ÉLÈVE@example.com", "élève@example.com"))
    }

    @Test
    fun the_hash_and_the_sign_in_gate_fold_identically() {
        // The comparison and the partition key must never disagree, or a pass
        // could validate one identity and write under another.
        for (persisted in listOf("ÜSER@example.com", "user@example.com", " User@Example.com ")) {
            val verified = persisted.trim()
            val equal = AccountHash.normalizeAccountKey(persisted) ==
                AccountHash.normalizeAccountKey(verified)
            assertEquals(
                "hash inputs must fold the same way the gate folds them, for '$persisted'",
                equal,
                identityDivergenceOutcome(persisted, verified) == null,
            )
        }
    }

    @Test
    fun the_fold_is_locale_independent() {
        // Kotlin's `lowercase()` is Locale.ROOT-scoped. Under a Turkish default
        // locale a locale-sensitive fold would map "I" to a dotless "ı" and
        // re-key the account partition for a user who never changed anything.
        Locale.setDefault(Locale("tr", "TR"))
        assertEquals("isser@example.com", AccountHash.normalizeAccountKey("ISSER@example.com"))
        assertNull(identityDivergenceOutcome("ISSER@example.com", "isser@example.com"))
    }

    @Test
    fun ascii_case_and_padding_still_fold() {
        for (persisted in listOf("USER@EXAMPLE.COM", "User@Example.Com", "\tuser@example.com\n")) {
            assertNull("persisted='$persisted'", identityDivergenceOutcome(persisted, "user@example.com"))
        }
    }

    @Test
    fun a_genuinely_different_account_is_still_divergent() {
        val err = identityDivergenceOutcome("someone.else@example.com", "user@example.com")
        assertEquals(
            "the repair-vs-abort gate itself is unchanged: still Retryable",
            "com.groq.voicetyper.sync.v1.SyncError\$Retryable",
            err?.javaClass?.name,
        )
        assertNotEquals(null, err)
    }

    @Test
    fun a_missing_persisted_identity_still_requires_reauthentication() {
        assertEquals(
            "com.groq.voicetyper.sync.v1.SyncError\$AuthRequired",
            identityDivergenceOutcome(null, "user@example.com")?.javaClass?.name,
        )
    }

    @Test
    fun the_hash_is_the_full_64_hex_digest_of_the_folded_email() {
        val hash = AccountHash.of("ÜSER@example.com")
        assertEquals(64, hash!!.length)
        assertEquals(
            "casing/whitespace must not produce a different partition key",
            hash,
            AccountHash.of("  üser@EXAMPLE.com "),
        )
        assertNull(AccountHash.of(null))
        assertNull(AccountHash.of("   "))
    }
}