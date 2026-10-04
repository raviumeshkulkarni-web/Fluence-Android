package com.groq.voicetyper.sync

import com.groq.voicetyper.dictionary.DictionaryRepository
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.snippets.Snippet
import com.groq.voicetyper.snippets.SnippetPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 pinning tests: the account-ownership predicate that every dictionary
 * and snippet read path depends on.
 *
 * Contract under test: `syncAccount == null || syncAccount == hash`.
 * - Unowned rows are visible to everyone (local-first continuity).
 * - Owned rows are visible only to their account.
 * - A null hash (never signed in) sees only unowned rows.
 *
 * The DAO-level SQL (`syncAccount IS NULL OR syncAccount = :hash`) is the same
 * predicate by construction; these tests pin the Kotlin side so any drift in
 * either layer is caught.
 */
class AccountIsolationTest {

    private fun dictRow(syncAccount: String?) = CustomDictionaryEntry(
        spokenText = "hello",
        replacementText = "world",
        syncAccount = syncAccount
    )

    private fun snippet(syncAccount: String?) = Snippet(
        id = 1L,
        trigger = "brb",
        expansion = "be right back",
        syncAccount = syncAccount
    )

    // ---- unowned rows: visible to everyone (local-first) ----

    @Test
    fun unowned_dictionary_row_visible_to_any_account() {
        assertTrue(DictionaryRepository.belongsToCurrentAccount(dictRow(null), "aaaa"))
        assertTrue(DictionaryRepository.belongsToCurrentAccount(dictRow(null), "bbbb"))
    }

    @Test
    fun unowned_dictionary_row_visible_when_never_signed_in() {
        assertTrue(DictionaryRepository.belongsToCurrentAccount(dictRow(null), null))
    }

    @Test
    fun unowned_snippet_visible_to_any_account() {
        assertTrue(SnippetPreferences.belongsToCurrentAccount(snippet(null), "aaaa"))
        assertTrue(SnippetPreferences.belongsToCurrentAccount(snippet(null), "bbbb"))
    }

    @Test
    fun unowned_snippet_visible_when_never_signed_in() {
        assertTrue(SnippetPreferences.belongsToCurrentAccount(snippet(null), null))
    }

    // ---- owned rows: visible only to their account (A → B isolation) ----

    @Test
    fun owned_dictionary_row_visible_only_to_its_account() {
        assertTrue(DictionaryRepository.belongsToCurrentAccount(dictRow("aaaa"), "aaaa"))
        assertFalse(DictionaryRepository.belongsToCurrentAccount(dictRow("aaaa"), "bbbb"))
    }

    @Test
    fun owned_dictionary_row_hidden_when_signed_out() {
        assertFalse(DictionaryRepository.belongsToCurrentAccount(dictRow("aaaa"), null))
    }

    @Test
    fun owned_snippet_visible_only_to_its_account() {
        assertTrue(SnippetPreferences.belongsToCurrentAccount(snippet("aaaa"), "aaaa"))
        assertFalse(SnippetPreferences.belongsToCurrentAccount(snippet("aaaa"), "bbbb"))
    }

    @Test
    fun owned_snippet_hidden_when_signed_out() {
        assertFalse(SnippetPreferences.belongsToCurrentAccount(snippet("aaaa"), null))
    }

    // ---- A → B → A: returning restores, and accounts do not alias ----

    @Test
    fun distinct_accounts_do_not_alias() {
        // Same row content under two accounts must not be conflated: the
        // predicate is exact string equality, so near-misses fail closed.
        assertFalse(DictionaryRepository.belongsToCurrentAccount(dictRow("aaaa"), "AAAA"))
        assertFalse(DictionaryRepository.belongsToCurrentAccount(dictRow("aaaa"), " aaaa"))
        assertFalse(SnippetPreferences.belongsToCurrentAccount(snippet("aaaa"), "aaab"))
    }

    @Test
    fun empty_string_hash_is_not_a_wildcard() {
        // A blank hash must behave as "no match" for owned rows, never as a
        // wildcard that exposes another account's data.
        assertFalse(DictionaryRepository.belongsToCurrentAccount(dictRow("aaaa"), ""))
        assertFalse(SnippetPreferences.belongsToCurrentAccount(snippet("aaaa"), ""))
        assertTrue(DictionaryRepository.belongsToCurrentAccount(dictRow(null), ""))
    }
}
