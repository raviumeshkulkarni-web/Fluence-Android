package com.groq.voicetyper.sync

import com.groq.voicetyper.sync.v1.SyncError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1 — identity divergence must fail closed, identically on both platforms.
 *
 * Android previously logged the divergence and continued under the token's
 * identity, while Windows refused to sync at all
 * (`scheduler.rs`: persisted identity missing -> `AuthRequired`; persisted !=
 * token-verified -> `Retryable` "refusing to sync under a stale key"). Two
 * platforms taking opposite actions on identical input left Android with no
 * defined outcome: it read and wrote account-scoped data under a partition key
 * the user had never re-confirmed, while Windows wrote nothing.
 *
 * The trigger is a Google email rename, where the persisted value legitimately
 * lags the token. The only resolutions are re-authentication or explicit user
 * reconciliation. There is deliberately no automatic re-keying: silently moving
 * a partition is how one account's payload becomes addressed by another's key.
 */
class IdentityDivergenceTest {

    private val verified = "user@example.com"

    // ------------------------------------------------------------------
    // Agreement -> the pass may proceed
    // ------------------------------------------------------------------

    @Test
    fun identical_identities_allow_the_pass() {
        assertNull(identityDivergenceOutcome(verified, verified))
    }

    @Test
    fun comparison_ignores_case_and_surrounding_whitespace() {
        // Mirrors Windows `persisted.trim().to_lowercase() !=
        // verified.trim().to_lowercase()`, so casing or padding differences in a
        // Google account name never abort a pass.
        for (persisted in listOf(
            "USER@EXAMPLE.COM",
            "User@Example.Com",
            "  user@example.com  ",
            "\tuser@example.com\n",
        )) {
            assertNull(
                "persisted='$persisted' must be treated as the same identity",
                identityDivergenceOutcome(persisted, verified),
            )
        }
    }

    // ------------------------------------------------------------------
    // Divergence -> abort, fail closed
    // ------------------------------------------------------------------

    @Test
    fun a_google_email_rename_aborts_the_pass_as_retryable() {
        val err = identityDivergenceOutcome("old-name@example.com", verified)
        assertNotNull("a rename must abort the pass", err)
        assertTrue(
            "a rename must be Retryable so the pass is retried after re-auth, was $err",
            err is SyncError.Retryable,
        )
        assertTrue(
            "the message should name the stale key, was: ${err!!.message}",
            err.message!!.contains("stale key"),
        )
    }

    @Test
    fun a_different_google_account_aborts_the_pass() {
        val err = identityDivergenceOutcome("someone.else@example.com", verified)
        assertTrue("switching accounts must abort, was $err", err is SyncError.Retryable)
    }

    @Test
    fun a_missing_persisted_identity_requires_reauthentication() {
        // Windows: `let Some(persisted) = ... else { return AuthRequired }`.
        // Android previously treated a null session email as "no divergence".
        val err = identityDivergenceOutcome(null, verified)
        assertTrue(
            "with no persisted identity there is nothing to reconcile against, was $err",
            err is SyncError.AuthRequired,
        )
    }

    @Test
    fun divergence_never_resolves_to_a_non_error_or_a_permissive_outcome() {
        // The gate has exactly three shapes. Anything that let the pass continue
        // on an unverified key, or surfaced as something the user cannot act on,
        // would reintroduce the divergence.
        val outcomes = listOf(
            identityDivergenceOutcome(null, verified),
            identityDivergenceOutcome("other@example.com", verified),
            identityDivergenceOutcome(verified, verified),
        )
        for (o in outcomes) {
            if (o != null) {
                assertTrue(
                    "unexpected error type $o",
                    o is SyncError.AuthRequired || o is SyncError.Retryable,
                )
            }
        }
        assertEquals("agreement must be the only non-error outcome", 1, outcomes.count { it == null })
    }

    @Test
    fun the_gate_is_pure_and_has_no_side_effects() {
        // Calling it repeatedly with the same inputs must give the same answer;
        // it decides, it does not act. Any re-keying or migration would live
        // here, and there is none.
        val first = identityDivergenceOutcome("old@example.com", verified)
        val second = identityDivergenceOutcome("old@example.com", verified)
        assertEquals(first?.message, second?.message)
        assertEquals(first?.javaClass, second?.javaClass)
    }
}
