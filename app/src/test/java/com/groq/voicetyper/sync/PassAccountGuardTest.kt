package com.groq.voicetyper.sync

import com.groq.voicetyper.sync.v1.SyncError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 1 regression coverage: a sync pass must never use credentials belonging
 * to a different account than the one it started for.
 *
 * Drive partitions storage by the token's account, so a token minted after an
 * account switch would write the pass's payload into the other account's
 * `appDataFolder`. Refreshing under the pass's OWN account must keep working,
 * because tokens legitimately expire mid-pass.
 */
class PassAccountGuardTest {

    private fun assertRetryable(passAccount: String?, current: String?) {
        try {
            PassAccountGuard.requireUnchanged(passAccount, current)
            fail("expected SyncError.Retryable for pass=$passAccount current=$current")
        } catch (e: SyncError.Retryable) {
            assertTrue(
                "message should not leak either account's identity",
                !e.message.orEmpty().contains("@")
            )
        }
    }

    // ---- same account: refresh must stay ALLOWED (no availability regression) ----

    @Test
    fun same_account_allows_refresh() {
        PassAccountGuard.requireUnchanged("a@example.com", "a@example.com")
    }

    @Test
    fun same_account_repeated_checks_remain_allowed() {
        val pass = "a@example.com"
        repeat(3) { PassAccountGuard.requireUnchanged(pass, "a@example.com") }
    }

    // ---- account switch mid-pass: must fail CLOSED ----

    @Test
    fun switched_to_other_account_fails_closed() {
        assertRetryable("a@example.com", "b@example.com")
    }

    @Test
    fun sign_out_mid_pass_fails_closed() {
        // signOut() clears accountEmail, so the active account becomes null.
        assertRetryable("a@example.com", null)
    }

    @Test
    fun account_change_during_mint_is_caught_by_the_second_check() {
        // Models: check passes, then the account changes while the mint is in
        // flight, then the re-check runs.
        val pass = "a@example.com"
        PassAccountGuard.requireUnchanged(pass, "a@example.com")
        assertRetryable(pass, "b@example.com")
    }

    @Test
    fun switch_back_to_original_account_is_still_allowed() {
        // The guard binds to the pass's account, not to "whoever is newest".
        PassAccountGuard.requireUnchanged("a@example.com", "a@example.com")
    }

    // ---- signed-out / never-signed-in behaviour must be unaffected ----

    @Test
    fun never_signed_in_pass_is_not_guarded() {
        // A pass only ever runs with an account (runV12Pass throws AuthRequired
        // otherwise), so this documents the guard is inert rather than a
        // behaviour change for local-only users.
        PassAccountGuard.requireUnchanged(null, null)
    }

    @Test
    fun case_and_whitespace_differences_count_as_a_different_account() {
        // Account identity is normalised upstream by AccountHash; the guard
        // compares the raw email, so a cosmetic difference must not be treated
        // as "unchanged".
        assertRetryable("a@example.com", "A@example.com")
        assertRetryable("a@example.com", " a@example.com")
    }

    @Test
    fun failure_is_retryable_not_auth_required() {
        // AuthRequired would prompt the user to reconnect, which is wrong for a
        // routine account switch: the next pass simply runs for the new account.
        try {
            PassAccountGuard.requireUnchanged("a@example.com", "b@example.com")
            fail("expected throw")
        } catch (e: SyncError) {
            assertTrue(
                "must be Retryable, was ${e::class.simpleName}",
                e is SyncError.Retryable
            )
            assertEquals(true, e is SyncError.Retryable)
        }
    }
}
