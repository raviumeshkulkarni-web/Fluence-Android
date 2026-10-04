package com.groq.voicetyper.sync

import com.groq.voicetyper.dictionary.DictionaryRepository
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 — D1 ownership policy, pinned.
 *
 * D1 was decided as: **preserve current behaviour** on all three questions.
 *  - D1a: unowned records stay VISIBLE to any signed-in account.
 *  - D1b: the five synced settings values stay DEVICE-GLOBAL (A and B share them).
 *  - D1c: `default_id` and `package_overrides` stay DEVICE-LOCAL.
 *
 * These tests exist to make the decision explicit and regression-proof, not to
 * assert new behaviour. If a future change flips any of these, the product
 * decision has changed and this file must be updated deliberately.
 *
 * Consequence of D1a that matters: because an unowned row is visible, it must
 * also be actionable — "visible implies deletable". Foreign-owned rows are the
 * only ones refused. The delete/toggle seams in `DictionaryRepository` therefore
 * gate on the same predicate the read path uses.
 */
class OwnershipPolicyTest {

    private fun row(syncAccount: String?) = CustomDictionaryEntry(
        spokenText = "hello",
        replacementText = "world",
        syncAccount = syncAccount
    )

    private val hashA = "aaaa"
    private val hashB = "bbbb"

    // ---- D1a: unowned stays visible (no regression) ----

    @Test
    fun d1a_unowned_remains_visible_to_any_account() {
        assertTrue(DictionaryRepository.belongsToCurrentAccount(row(null), hashA))
        assertTrue(DictionaryRepository.belongsToCurrentAccount(row(null), hashB))
        assertTrue(DictionaryRepository.belongsToCurrentAccount(row(null), null))
    }

    @Test
    fun d1a_own_account_row_is_visible_and_therefore_actionable() {
        // Visible to A, so A must be allowed to delete/toggle it.
        assertTrue(DictionaryRepository.belongsToCurrentAccount(row(hashA), hashA))
    }

    // ---- D1a consequence: only foreign-owned rows are refused ----

    @Test
    fun d1a_foreign_owned_row_is_refused_mutation() {
        // B must not delete or toggle A's row.
        assertFalse(DictionaryRepository.belongsToCurrentAccount(row(hashA), hashB))
    }

    @Test
    fun d1a_owned_row_refused_when_signed_out() {
        assertFalse(DictionaryRepository.belongsToCurrentAccount(row(hashA), null))
    }

    // ---- A → B → A ----

    @Test
    fun a_to_b_to_a_round_trip() {
        // As A: A's row visible, B's not.
        assertTrue(DictionaryRepository.belongsToCurrentAccount(row(hashA), hashA))
        assertFalse(DictionaryRepository.belongsToCurrentAccount(row(hashB), hashA))
        // As B: mirror image.
        assertFalse(DictionaryRepository.belongsToCurrentAccount(row(hashA), hashB))
        assertTrue(DictionaryRepository.belongsToCurrentAccount(row(hashB), hashB))
        // Back as A: A's row is intact and visible again.
        assertTrue(DictionaryRepository.belongsToCurrentAccount(row(hashA), hashA))
    }

    @Test
    fun unowned_row_survives_the_whole_round_trip() {
        // Unowned rows are never reassigned by any of this, so they stay visible
        // to A, to B, and when signed out. This is the property that makes
        // blanket adoption of unowned rows unsafe.
        repeat(3) {
            assertTrue(DictionaryRepository.belongsToCurrentAccount(row(null), hashA))
            assertTrue(DictionaryRepository.belongsToCurrentAccount(row(null), hashB))
            assertTrue(DictionaryRepository.belongsToCurrentAccount(row(null), null))
        }
    }

    // ---- D1b: settings stay device-global (documented, not enforced here) ----
    //
    // D1b is a PRODUCT decision, not an isolation guarantee: the five synced
    // settings values are stored once per device, so two accounts on one machine
    // share them. That is deliberate. There is intentionally no behavioural test
    // asserting it here — asserting a shared value is trivially true and would
    // only pad the count. If settings ever become per-account, the change is
    // visible in `PrefsSettingsV1Store` (global `mappings()`) versus a
    // hash-keyed store, not here.
}
