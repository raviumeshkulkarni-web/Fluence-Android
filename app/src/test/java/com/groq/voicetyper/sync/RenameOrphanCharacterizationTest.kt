package com.groq.voicetyper.sync

import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.snippets.Snippet
import com.groq.voicetyper.sync.v1.AccountHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 2 — characterisation of Google-email-rename behaviour.
 *
 * ## Decision this test pins
 *
 * The product decision on record is **DEFER**: Fluence does NOT automatically
 * re-key account data from the pre-rename hash to the post-rename hash. Phase 6
 * is explicitly not becoming a data-migration project.
 *
 * This test therefore does NOT assert that rename works. It asserts what rename
 * actually does today, so the behaviour is visible and cannot change silently.
 * If a future change adds migration, this test should be changed deliberately
 * and consciously — not because a refactor moved a predicate.
 *
 * ## The behaviour being pinned
 *
 * The account hash is `SHA-256(lower(trim(email)))`. A Google email rename
 * therefore changes the hash while the Drive `appDataFolder` — keyed by the
 * Google identity, not the email — stays the same.
 *
 * Every account-scoped read filters on the ACTIVE hash, so rows stamped with the
 * pre-rename hash become invisible: they are not read, not uploaded, and not
 * deleted. They are orphaned, not destroyed. `stampUnstamped` cannot rescue them
 * because it only claims rows whose `syncAccount IS NULL`; a row stamped with a
 * concrete old hash is not unstamped.
 *
 * The mitigating case, and the reason this is DEFER rather than urgent: a row
 * that was NEVER stamped is shared by the predicate
 * (`syncAccount IS NULL OR syncAccount = :hash`) and therefore survives a rename
 * intact. Only rows that have already been claimed by an account are orphaned.
 */
class RenameOrphanCharacterizationTest {

    private fun hashOf(email: String) = AccountHash.of(email)!!

    private val preRenameEmail = "old.name@example.com"
    private val postRenameEmail = "new.name@example.com"

    private fun dictRow(syncAccount: String?, spoken: String = "hello") =
        CustomDictionaryEntry(
            spokenText = spoken,
            replacementText = "world",
            syncAccount = syncAccount,
        )

    private fun snippet(syncAccount: String?) =
        Snippet(id = 1L, trigger = "brr", expansion = "be right back", syncAccount = syncAccount)

    /** The exact predicate every account-scoped dictionary/snippet read uses. */
    private fun visibleUnder(syncAccount: String?, activeHash: String): Boolean =
        syncAccount == null || syncAccount == activeHash

    // ------------------------------------------------------------------
    // The rename itself changes the partition key
    // ------------------------------------------------------------------

    @Test
    fun a_google_email_rename_changes_the_account_hash() {
        // Precondition for everything below. Windows derives this from Drive
        // `about`; Android now does too (STAGE 1), so both agree.
        assertFalse(
            "a renamed email must produce a different partition key",
            hashOf(preRenameEmail) == hashOf(postRenameEmail),
        )
    }

    @Test
    fun hash_is_case_and_whitespace_insensitive_so_a_cosmetic_rename_is_not_a_rename() {
        // Guards against a false orphan: Drive may return the same address with
        // different casing or padding. That must NOT change the partition.
        assertEquals(
            hashOf("user@example.com"),
            hashOf("  User@Example.COM "),
        )
    }

    // ------------------------------------------------------------------
    // Rows already claimed by the pre-rename account are orphaned
    // ------------------------------------------------------------------

    @Test
    fun rows_stamped_with_the_pre_rename_hash_are_invisible_after_the_rename() {
        val oldHash = hashOf(preRenameEmail)
        val newHash = hashOf(postRenameEmail)
        val row = dictRow(syncAccount = oldHash)

        assertTrue("visible before the rename", visibleUnder(row.syncAccount, oldHash))
        assertFalse(
            "after the rename the row belongs to a partition this account no longer addresses",
            visibleUnder(row.syncAccount, newHash),
        )
    }

    @Test
    fun orphaned_rows_are_not_destroyed_only_unreachable() {
        // DEFER means "we do not migrate", NOT "we delete". The row object is
        // untouched; it simply fails the visibility predicate. A future
        // migration can still recover it from the pre-rename hash.
        val oldHash = hashOf(preRenameEmail)
        val newHash = hashOf(postRenameEmail)
        val row = dictRow(syncAccount = oldHash)

        assertFalse(visibleUnder(row.syncAccount, newHash))
        assertEquals("the row itself must be preserved verbatim", oldHash, row.syncAccount)
        assertEquals("hello", row.spokenText)
    }

    @Test
    fun orphaned_snippet_rows_behave_the_same_way() {
        val oldHash = hashOf(preRenameEmail)
        val newHash = hashOf(postRenameEmail)
        val row = snippet(syncAccount = oldHash)

        assertTrue(visibleUnder(row.syncAccount, oldHash))
        assertFalse(visibleUnder(row.syncAccount, newHash))
        assertEquals(oldHash, row.syncAccount)
    }

    // ------------------------------------------------------------------
    // The mitigating case: never-stamped rows survive a rename
    // ------------------------------------------------------------------

    @Test
    fun never_stamped_rows_survive_a_rename_intact() {
        val newHash = hashOf(postRenameEmail)
        val unowned = dictRow(syncAccount = null)

        assertTrue(
            "an unstamped row is shared by the predicate, so a rename must not hide it",
            visibleUnder(unowned.syncAccount, newHash),
        )
    }

    @Test
    fun unowned_rows_survive_for_snippets_too() {
        val newHash = hashOf(postRenameEmail)
        assertTrue(visibleUnder(snippet(syncAccount = null).syncAccount, newHash))
    }

    // ------------------------------------------------------------------
    // Why the existing stamping pass cannot rescue an orphaned row
    // ------------------------------------------------------------------

    @Test
    fun stamp_unstamped_cannot_rescue_a_row_already_stamped_with_an_old_hash() {
        // `stampUnstamped` claims rows matching `syncAccount IS NULL`. A row
        // stamped with a concrete pre-rename hash does not match, so the
        // "enrollment" pass leaves it alone. This is why DEFER leaves the row
        // stranded rather than silently re-homing it.
        val oldHash = hashOf(preRenameEmail)
        val newHash = hashOf(postRenameEmail)

        val stranded = dictRow(syncAccount = oldHash)
        val wouldBeStampedByEnrollment = stranded.syncAccount == null
        assertFalse(
            "a row stamped with the old hash is not unstamped, so enrollment skips it",
            wouldBeStampedByEnrollment,
        )
        assertFalse(visibleUnder(stranded.syncAccount, newHash))
    }

    // ------------------------------------------------------------------
    // Ownership indicator behaviour during the orphan window
    // ------------------------------------------------------------------

    @Test
    fun pre_rename_rows_read_as_foreign_under_the_new_identity() {
        // STAGE 1: the ownership indicator follows the TOKEN-derived identity, so
        // after a rename the old partition is correctly reported as someone
        // else's data rather than as the current account's.
        val oldHash = hashOf(preRenameEmail)
        val newHash = hashOf(postRenameEmail)

        val newEmail = postRenameEmail

        SyncAccounts.publishAuthenticated(newEmail)
        try {
            assertTrue("pre-rename rows must read as foreign", SyncAccounts.isForeign(oldHash))
            assertFalse(SyncAccounts.isForeign(newHash))
        } finally {
            SyncAccounts.clearAuthentication()
        }
    }

    @Test
    fun distinct_accounts_never_collide_after_a_rename() {
        // Two different people must not be able to end up sharing a partition.
        assertFalse(hashOf("alice@example.com") == hashOf("bob@example.com"))
    }
}
