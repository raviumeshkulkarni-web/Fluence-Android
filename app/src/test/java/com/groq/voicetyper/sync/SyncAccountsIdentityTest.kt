package com.groq.voicetyper.sync

import com.groq.voicetyper.sync.v1.AccountHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 1 — the process-wide ownership cache must be governed by the identity
 * proven by the access token, not by the persisted sign-in email.
 *
 * The defect this locks: `SyncAccounts.refresh` derived `cachedAccount` from
 * `SyncAuthSession.accountEmail` (a persisted string), while the sync pass
 * derived its partition from the token. When those two disagreed — which is
 * exactly the Google-email-rename case — the UI and repositories evaluated
 * `isForeign` against a different key than the one sync actually wrote, so an
 * account's OWN rows were classified as foreign and hidden.
 *
 * `refresh` needs a Context, so these tests exercise the Context-free surface:
 * the publish/clear lifecycle and the ownership predicates that depend on it.
 */
class SyncAccountsIdentityTest {

    private val persistedStyleEmail = "old.name@example.com"
    private val authenticatedEmail = "new.name@example.com"

    private fun hashOf(email: String) = AccountHash.of(email)!!

    @Test
    fun publish_authenticated_marks_the_identity_token_verified() {
        SyncAccounts.clearAuthentication()
        SyncAccounts.publishAuthenticated(authenticatedEmail)

        assertTrue("a token-verified identity must be flagged", SyncAccounts.tokenVerified)
        assertEquals(authenticatedEmail, SyncAccounts.cachedAccount)
        assertEquals(hashOf(authenticatedEmail), SyncAccounts.currentAccountHash.value)
    }

    @Test
    fun ownership_predicates_follow_the_token_identity_not_the_persisted_email() {
        SyncAccounts.clearAuthentication()
        SyncAccounts.publishAuthenticated(authenticatedEmail)

        // The account's own rows, stamped with the token-derived hash.
        assertFalse(
            "own row must not be foreign",
            SyncAccounts.isForeign(hashOf(authenticatedEmail)),
        )
        assertTrue(
            SyncAccounts.belongsToCurrentAccount(hashOf(authenticatedEmail)),
        )

        // Rows stamped under the PRE-RENAME hash are a different partition and
        // must read as foreign rather than being silently claimed.
        assertTrue(
            "pre-rename hash must be foreign",
            SyncAccounts.isForeign(hashOf(persistedStyleEmail)),
        )
        assertFalse(
            SyncAccounts.belongsToCurrentAccount(hashOf(persistedStyleEmail)),
        )
    }

    @Test
    fun the_governing_hash_is_the_token_hash_even_when_the_email_differs() {
        SyncAccounts.clearAuthentication()
        SyncAccounts.publishAuthenticated(authenticatedEmail)

        // This is the exact regression: the two identities differ, and the cache
        // must expose the token-derived one. If it exposed the persisted email
        // instead, an authenticated pass would write partition H_new while the
        // UI hid those same rows as foreign.
        assertTrue(authenticatedEmail != persistedStyleEmail)
        assertEquals(
            hashOf(authenticatedEmail),
            SyncAccounts.currentAccountHash.value,
        )
    }

    @Test
    fun clear_authentication_resets_the_verified_flag_and_identity() {
        SyncAccounts.publishAuthenticated(authenticatedEmail)
        SyncAccounts.clearAuthentication()

        assertFalse("sign-out must drop the verified flag", SyncAccounts.tokenVerified)
        assertNull(SyncAccounts.cachedAccount)
        assertNull(SyncAccounts.currentAccountHash.value)
    }

    @Test
    fun signed_out_state_treats_stamped_rows_as_foreign_so_delete_paths_skip_them() {
        SyncAccounts.clearAuthentication()

        // Deliberate, pre-existing semantics: while signed out there is no active
        // account, so EVERY stamped row counts as foreign. That is what makes
        // delete paths skip them (see SyncAccounts' own doc: "delete paths must
        // skip them") and it preserves local-first behaviour for users who are
        // never signed in.
        assertTrue(
            "a stamped row must read as foreign while signed out",
            SyncAccounts.isForeign(hashOf(authenticatedEmail)),
        )
        assertFalse(
            "unowned rows stay readable while signed out",
            SyncAccounts.belongsToCurrentAccount(hashOf(authenticatedEmail)),
        )
        // A never-stamped row is nobody's foreign row.
        assertFalse(SyncAccounts.isForeign(null))
        assertTrue(SyncAccounts.belongsToCurrentAccount(null))
    }

    @Test
    fun unowned_rows_remain_readable_while_signed_in() {
        SyncAccounts.clearAuthentication()
        SyncAccounts.publishAuthenticated(authenticatedEmail)

        // A null syncAccount is an unstamped/legacy row. It must stay readable —
        // the dictionary/snippet domains deliberately share unowned rows. This
        // test exists to pin the contrast with the Agents/Styles domains, which
        // must NOT reuse this null-passes predicate.
        assertTrue(SyncAccounts.belongsToCurrentAccount(null))
        assertFalse(SyncAccounts.isForeign(null))
    }

    @Test
    fun a_republished_identity_replaces_the_previous_one() {
        SyncAccounts.clearAuthentication()
        SyncAccounts.publishAuthenticated("first@example.com")
        SyncAccounts.publishAuthenticated("second@example.com")

        assertEquals("second@example.com", SyncAccounts.cachedAccount)
        assertFalse(SyncAccounts.isForeign(hashOf("second@example.com")))
        assertTrue(SyncAccounts.isForeign(hashOf("first@example.com")))
    }
}
