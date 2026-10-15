package com.groq.voicetyper.sync.v1

import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.snippets.Snippet
import com.groq.voicetyper.snippets.SnippetPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the two quarantine data-loss findings.
 *
 * **#2 — dictionary.** `RoomDictionaryV1Store.applyMergedAndClearDirty`
 * hard-deleted every clean row whose `syncId` was absent from the merge.
 * Quarantined rows are EXCLUDED from the merge input by `excludeQuarantined`,
 * so their `syncId` can never be present; and `quarantineBySyncId` does not
 * set `dirty`, so a quarantined CLEAN row matched both halves of the
 * condition and was hard-deleted — destroying exactly the rows the quarantine
 * exists to hold. The sibling `decideDictionarySupersededDeletions` already
 * carried `row.quarantineReason == null`; the primary path did not. Windows
 * keeps them (`stores.rs` "F3: quarantined rows are held for recovery; the
 * wholesale replacement must not wipe them", pinned by
 * `save_merged_keeps_quarantined_dict_row`).
 *
 * **#3 — snippets.** `decideSnippetRetention` read snippets UNFILTERED and
 * then dropped any quarantined+clean+absent-from-merge row, and
 * `SnippetPreferences.saveAll` persisted the reduced set. The comment
 * justifying the missing guard ("snippets have no quarantine exclusion in
 * their load path") became false in commit 54d0277, which added
 * `it.quarantineReason == null` to `PrefsSnippetV1Store.loadByAccount`.
 */
class QuarantineFixTest {

    private val hash = "a".repeat(64)

    // ------------------------------------------------------------------
    // #2 — dictionary hard-delete selection
    // ------------------------------------------------------------------

    private fun dictRow(
        id: Long,
        syncId: String?,
        dirty: Boolean = false,
        quarantined: Boolean = false,
        spoken: String = "word-$id"
    ) = CustomDictionaryEntry(
        id = id,
        spokenText = spoken,
        replacementText = "x",
        syncId = syncId,
        syncAccount = hash,
        dirty = dirty,
        everPushed = true,
        quarantineReason = if (quarantined) "collision" else null
    )

    @Test
    fun quarantinedCleanDictionaryRowSurvivesApplyMergedAndClearDirty() {
        // The audit scenario verbatim: quarantined, CLEAN, non-dirty, and its
        // syncId can never be in the merge because the merge input excludes it.
        val quarantined = dictRow(id = 1L, syncId = "q-1", quarantined = true)
        val mergedIds = emptySet<String>()

        assertTrue(
            "a quarantined row is held for recovery and must never be hard-deleted",
            decideDictionaryHardDeletes(listOf(quarantined), mergedIds).isEmpty()
        )
    }

    @Test
    fun cleanLosingDictionaryRowIsStillHardDeleted() {
        // The guard must not disable the rule it protects: a clean row that
        // lost the merge has no business surviving locally.
        val loser = dictRow(id = 2L, syncId = "loser")
        assertEquals(listOf("loser"), decideDictionaryHardDeletes(listOf(loser), setOf("winner")))
    }

    @Test
    fun dirtyMidPassDictionaryRowRidesTheNextPut() {
        // H5 invariant, unchanged by the quarantine guard.
        val dirty = dictRow(id = 3L, syncId = "dirty-1", dirty = true)
        assertTrue(decideDictionaryHardDeletes(listOf(dirty), emptySet()).isEmpty())
    }

    @Test
    fun quarantinedRowThatAlsoWonTheMergeIsNotSelected() {
        val row = dictRow(id = 4L, syncId = "q-2", quarantined = true)
        assertTrue(decideDictionaryHardDeletes(listOf(row), setOf("q-2")).isEmpty())
    }

    @Test
    fun unstampedDictionaryRowIsNeverSelected() {
        // No syncId to delete by; `stampUnstamped` gives it one.
        val unstamped = dictRow(id = 5L, syncId = null)
        assertTrue(decideDictionaryHardDeletes(listOf(unstamped), emptySet()).isEmpty())
    }

    @Test
    fun onlyLosingCleanUnquarantinedRowsAreSelected() {
        val existing = listOf(
            dictRow(id = 1L, syncId = "kept-in-merge"),
            dictRow(id = 2L, syncId = "loser"),
            dictRow(id = 3L, syncId = "q-kept", quarantined = true),
            dictRow(id = 4L, syncId = "dirty-kept", dirty = true)
        )
        assertEquals(
            listOf("loser"),
            decideDictionaryHardDeletes(existing, setOf("kept-in-merge", "q-kept", "dirty-kept"))
        )
    }

    // ------------------------------------------------------------------
    // #3 — snippet retention
    // ------------------------------------------------------------------

    private fun snippet(
        id: Long,
        uuid: String?,
        dirty: Boolean = false,
        quarantined: Boolean = false,
        trigger: String = "trig-$id"
    ) = Snippet(
        id = id,
        trigger = trigger,
        expansion = "exp",
        uuid = uuid,
        createdAt = 100L,
        updatedAt = 200L,
        syncAccount = hash,
        dirty = dirty,
        quarantineReason = if (quarantined) "corrupt" else null
    )

    @Test
    fun quarantinedSnippetSurvivesRetention() {
        // Quarantined + clean + current-account + absent from the merge: the
        // exact shape retention used to drop.
        val quarantined = snippet(id = 1L, uuid = "s-q", quarantined = true)
        val kept = decideSnippetRetention(listOf(quarantined), mergedIds = emptySet(), currentAccountHash = hash)
        assertEquals(listOf("s-q"), kept.map { it.uuid })
    }

    @Test
    fun cleanLosingSnippetIsStillDropped() {
        // The guard must not disable the rule: a clean current-account row the
        // merge no longer holds was tombstoned elsewhere.
        val gone = snippet(id = 2L, uuid = "s-gone")
        val kept = decideSnippetRetention(listOf(gone), mergedIds = emptySet(), currentAccountHash = hash)
        assertTrue(kept.isEmpty())
    }

    // ------------------------------------------------------------------
    // #3 — end to end through the production store + persistence
    // ------------------------------------------------------------------

    @Test
    fun quarantinedSnippetSurvivesAFullApplyPass() = runBlocking {
        val prefs = FakePrefsStore()
        val ctx = prefs.context()
        val store = PrefsSnippetV1Store(ctx)

        val quarantined = snippet(id = 1L, uuid = "s-quarantined", quarantined = true)
        val plain = snippet(id = 2L, uuid = "s-plain")
        SnippetPreferences.saveAll(ctx, listOf(quarantined, plain))

        // Precondition that made this a data-loss bug: the quarantined row is
        // excluded from the merge input, so the merge can never carry its id.
        assertTrue(store.loadByAccount(hash).none { it.syncId == "s-quarantined" })
        assertEquals(listOf("s-plain"), store.loadByAccount(hash).map { it.syncId })

        store.applyMergedAndClearDirty(hash, "device-b", merged = listOf(snippetRecord("s-plain")))

        val after = SnippetPreferences.allEntries(ctx).associateBy { it.uuid }
        assertNotNull(
            "the apply pass must not delete the row the quarantine holds",
            after["s-quarantined"]
        )
        assertEquals("exp", after["s-quarantined"]!!.expansion)
        assertEquals("corrupt", after["s-quarantined"]!!.quarantineReason)
        assertNotNull("the merged winner must still be applied", after["s-plain"])
        assertEquals(false, after["s-plain"]!!.dirty)
    }

    @Test
    fun quarantinedSnippetAbsentFromAnEmptyMergeIsNotErased() = runBlocking {
        val prefs = FakePrefsStore()
        val ctx = prefs.context()
        val store = PrefsSnippetV1Store(ctx)

        SnippetPreferences.saveAll(ctx, listOf(snippet(id = 7L, uuid = "s-only", quarantined = true)))

        store.applyMergedAndClearDirty(hash, "device-b", merged = emptyList())

        assertEquals(
            listOf("s-only"),
            SnippetPreferences.allEntries(ctx).map { it.uuid }
        )
    }

    private fun snippetRecord(syncId: String) = SnippetRecord(
        syncId = syncId,
        businessKey = SnippetRecord.businessKeyOf("trig-2"),
        trigger = "trig-2",
        expansion = "exp",
        isEnabled = true,
        updatedAt = 500L,
        deletedAt = null,
        deviceId = "device-b"
    )
}
