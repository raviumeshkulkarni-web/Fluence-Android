package com.groq.voicetyper.sync.v1

import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.snippets.Snippet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V1StoresDecisionTest {

    private fun rec(updatedAt: Long, deviceId: String = "d") = DictionaryRecord(
        syncId = "11111111-1111-4111-8111-111111111111",
        businessKey = "hello",
        spoken = "hello",
        corrected = "hi",
        isEnabled = true,
        updatedAt = updatedAt,
        deletedAt = null,
        deviceId = deviceId
    )

    @Test
    fun newerDirtyLocalWinsOverOlderMergedWinner() {
        // current is dirty and newer than rec -> should NOT apply
        assertFalse(decideDictionaryApply(currentUpdatedAt = 2000L, currentDeviceId = "d", currentDirty = true, rec = rec(1000L)))
    }

    @Test
    fun olderDirtyLocalLosesToNewerMerged() {
        // current is dirty but older than rec -> should apply
        assertTrue(decideDictionaryApply(currentUpdatedAt = 1000L, currentDeviceId = "d", currentDirty = true, rec = rec(2000L)))
    }

    @Test
    fun cleanLocalAlwaysAppliesEvenIfNewer() {
        // current is clean (not dirty) even if newer -> should apply (overwrite)
        assertTrue(decideDictionaryApply(currentUpdatedAt = 3000L, currentDeviceId = "d", currentDirty = false, rec = rec(1000L)))
    }

    @Test
    fun dirtyWithNullUpdatedAtTreatedAsZero() {
        assertTrue(decideDictionaryApply(currentUpdatedAt = null, currentDeviceId = "d", currentDirty = true, rec = rec(1L)))
        assertFalse(decideDictionaryApply(currentUpdatedAt = 5L, currentDeviceId = "d", currentDirty = true, rec = rec(0L)))
    }

    @Test
    fun equalTimestampsDirtyDoesNotWin() {
        // equal with same device -> not greater, so should apply (tie not won)
        assertTrue(decideDictionaryApply(currentUpdatedAt = 1000L, currentDeviceId = "d", currentDirty = true, rec = rec(1000L, deviceId = "d")))
    }

    @Test
    fun equalTimestampTieBreakLargerDeviceIdWins() {
        // equal timestamp but local device larger -> local wins, should NOT apply
        assertFalse(decideDictionaryApply(currentUpdatedAt = 1000L, currentDeviceId = "z", currentDirty = true, rec = rec(1000L, deviceId = "a")))
        // equal timestamp but local device smaller -> local loses, should apply
        assertTrue(decideDictionaryApply(currentUpdatedAt = 1000L, currentDeviceId = "a", currentDirty = true, rec = rec(1000L, deviceId = "z")))
    }
}

class SnippetRetentionTest {

    private val hash = "acct-1"

    private fun snippet(
        uuid: String?,
        syncAccount: String? = hash,
        dirty: Boolean = false,
        updatedAt: Long? = 1000L,
        id: Long = 1L
    ) = Snippet(
        id = id,
        trigger = "hello",
        expansion = "hi",
        uuid = uuid,
        createdAt = 900L,
        updatedAt = updatedAt,
        syncAccount = syncAccount,
        dirty = dirty
    )

    private fun mergedRec(syncId: String) = SnippetRecord(
        syncId = syncId,
        businessKey = "hello",
        trigger = "hello",
        expansion = "hi",
        isEnabled = true,
        updatedAt = 500L,
        deletedAt = null,
        deviceId = "d"
    )

    @Test
    fun foreignAccountRowSurvivesApplyEvenWhenAbsentFromMerge() {
        // Row stamped by a previous sign-in must never be dropped by this
        // account's pass — this was the F1 silent-delete defect.
        val local = listOf(snippet(uuid = "u-foreign", syncAccount = "other-account"))
        val kept = decideSnippetRetention(local, mergedIds = emptySet(), currentAccountHash = hash)
        assertEquals(listOf(local[0]), kept)
    }

    @Test
    fun dirtyMidPassRowPreservedEvenIfAbsentFromMerge() {
        val local = listOf(snippet(uuid = "u-new", dirty = true))
        val kept = decideSnippetRetention(local, mergedIds = emptySet(), currentAccountHash = hash)
        assertEquals(listOf(local[0]), kept)
    }

    @Test
    fun rowWithoutWireIdentityIsPreserved() {
        // Unstamped legacy/new row (uuid == null) has never been uploaded.
        val local = listOf(snippet(uuid = null, dirty = false))
        val kept = decideSnippetRetention(local, mergedIds = emptySet(), currentAccountHash = hash)
        assertEquals(listOf(local[0]), kept)
    }

    @Test
    fun tombstonedAbsentCleanCurrentAccountRowIsDeleted() {
        // Current-account clean row the merge no longer contains: a remote
        // tombstone won elsewhere -> drop it locally (dictionary parity).
        val local = listOf(
            snippet(uuid = "u-gone"),
            snippet(uuid = "u-kept", id = 2L)
        )
        val mergedIds = setOf("u-kept")
        val kept = decideSnippetRetention(local, mergedIds, hash)
        assertEquals(listOf("u-kept"), kept.map { it.uuid })
    }

    @Test
    fun newerDirtyLocalSnippetKeepsItsEditOverMergedWinner() {
        // Mid-pass edit: dirty local row newer than the remote winner defers.
        val current = snippet(uuid = "u-1", dirty = true, updatedAt = 2000L)
        assertFalse(decideSnippetApply(current, mergedRec("u-1")))
    }

    @Test
    fun olderDirtyLocalSnippetLosesToNewerMergedWinner() {
        val current = snippet(uuid = "u-1", dirty = true, updatedAt = 400L)
        assertTrue(decideSnippetApply(current, mergedRec("u-1")))
    }

    @Test
    fun cleanLocalSnippetAlwaysTakesMergedVersion() {
        val current = snippet(uuid = "u-1", dirty = false, updatedAt = 9999L)
        assertTrue(decideSnippetApply(current, mergedRec("u-1")))
    }
}

class SnippetSupersededDropTest {

    private val hash = "acct-1"

    private fun snippet(
        uuid: String?,
        trigger: String,
        syncAccount: String? = hash,
        dirty: Boolean = false,
        id: Long = 1L
    ) = Snippet(
        id = id,
        trigger = trigger,
        expansion = "hi",
        uuid = uuid,
        createdAt = 900L,
        updatedAt = 1000L,
        syncAccount = syncAccount,
        dirty = dirty
    )

    private fun rec(syncId: String, trigger: String) = SnippetRecord(
        syncId = syncId,
        businessKey = SnippetRecord.businessKeyOf(trigger),
        trigger = trigger,
        expansion = "hi",
        isEnabled = true,
        updatedAt = 500L,
        deletedAt = null,
        deviceId = "d"
    )

    @Test
    fun cleanGhostWithWinnerSyncIdButLoserTriggerIsDropped() {
        val kept = listOf(
            snippet(uuid = "s-1", trigger = "my email", id = 1L),
            snippet(uuid = "s-1", trigger = "my primary email", id = 2L)
        )
        val out = decideSnippetSupersededDrops(kept, listOf(rec("s-1", "my primary email")), hash)
        assertEquals(listOf(2L), out.map { it.id })
    }

    @Test
    fun dirtyGhostSurvivesForNextPut() {
        val kept = listOf(
            snippet(uuid = "s-1", trigger = "my email", dirty = true, id = 1L),
            snippet(uuid = "s-1", trigger = "my primary email", id = 2L)
        )
        val out = decideSnippetSupersededDrops(kept, listOf(rec("s-1", "my primary email")), hash)
        assertEquals(listOf(1L, 2L), out.map { it.id })
    }

    @Test
    fun foreignAccountAndIdentitylessRowsUntouched() {
        val kept = listOf(
            snippet(uuid = "s-1", trigger = "other", syncAccount = "other-account", id = 3L),
            snippet(uuid = null, trigger = "other", id = 4L)
        )
        val out = decideSnippetSupersededDrops(kept, listOf(rec("s-1", "mine")), hash)
        assertEquals(listOf(3L, 4L), out.map { it.id })
    }
}

class SettingsApplyDecisionTest {

    @Test
    fun midPassEditedKeyDefersToNextPutInsteadOfClobbering() {
        // Snapshot said "on", live value is now "off" -> user edited during
        // the GET→PUT window; applying the merged winner would lose the edit.
        assertFalse(decideSettingsApply(snapshotValue = "on", liveValue = "off"))
    }

    @Test
    fun cleanStateAppliesMergedWinner() {
        assertTrue(decideSettingsApply(snapshotValue = "on", liveValue = "on"))
    }

    @Test
    fun keyWithoutSnapshotAppliesMergedWinner() {
        assertNullSafeDefaults()
    }

    private fun assertNullSafeDefaults() {
        // Never-synced key (no snapshot) and unreadable live value both apply.
        assertTrue(decideSettingsApply(snapshotValue = null, liveValue = "on"))
        assertTrue(decideSettingsApply(snapshotValue = "on", liveValue = null))
        assertTrue(decideSettingsApply(snapshotValue = null, liveValue = null))
    }
}

class DictionaryLoadFilterTest {
    @Test
    fun quarantinedRowExcludedFromSyncStoreLoad() {
        val clean = CustomDictionaryEntry(
            id = 1,
            spokenText = "hello",
            replacementText = "hi",
            syncId = "u-clean",
            syncAccount = "hash"
        )
        val quarantined = clean.copy(id = 2, spokenText = "bad", syncId = "u-bad", quarantineReason = "collision")
        val out = excludeQuarantined(listOf(clean, quarantined))
        assertEquals(listOf("u-clean"), out.map { it.syncId })
        assertFalse(out.any { it.quarantineReason != null })
    }
}

class DictionarySupersededDeletionTest {

    private fun entry(id: Long, spoken: String, syncId: String?, dirty: Boolean = false) =
        CustomDictionaryEntry(
            id = id,
            spokenText = spoken,
            replacementText = "x",
            syncId = syncId,
            syncAccount = "hash",
            dirty = dirty
        )

    private fun rec(syncId: String, spoken: String) = DictionaryRecord(
        syncId = syncId,
        businessKey = DictionaryRecord.businessKeyOf(spoken),
        spoken = spoken,
        corrected = "x",
        isEnabled = true,
        updatedAt = 200L,
        deletedAt = null,
        deviceId = "d"
    )

    @Test
    fun cleanGhostWithWinnerSyncIdButLoserKeyIsSelected() {
        // Phase D (H3): "my email" was renamed to "my primary email", keeping
        // syncId "s-1". The merge holds only the winner; the stale row must
        // be selected for deletion or it stays visible forever.
        val existing = listOf(
            entry(id = 1L, spoken = "my email", syncId = "s-1"),
            entry(id = 2L, spoken = "my primary email", syncId = "s-1")
        )
        val out = decideDictionarySupersededDeletions(existing, listOf(rec("s-1", "my primary email")))
        assertEquals(listOf(1L), out.map { it.id })
    }

    @Test
    fun dirtyGhostSurvivesForNextPut() {
        // H5 invariant: a mid-pass edit of the stale row rides the next PUT
        // instead of being dropped by the collapse reconcile.
        val existing = listOf(
            entry(id = 1L, spoken = "my email", syncId = "s-1", dirty = true),
            entry(id = 2L, spoken = "my primary email", syncId = "s-1")
        )
        val out = decideDictionarySupersededDeletions(existing, listOf(rec("s-1", "my primary email")))
        assertTrue(out.isEmpty())
    }

    @Test
    fun survivorAndUnrelatedRowsAreNeverSelected() {
        val existing = listOf(
            entry(id = 2L, spoken = "my primary email", syncId = "s-1"),
            entry(id = 3L, spoken = "other", syncId = "s-9")
        )
        val out = decideDictionarySupersededDeletions(
            existing,
            listOf(rec("s-1", "my primary email"), rec("s-9", "other"))
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun rowAbsentFromMergeIsLeftToTheSyncIdLoop() {
        // syncId not in the merge at all: the pre-existing sync-id-keyed
        // hard-delete owns that case, not the supersede rule.
        val existing = listOf(entry(id = 1L, spoken = "gone", syncId = "s-gone"))
        val out = decideDictionarySupersededDeletions(existing, listOf(rec("s-1", "kept")))
        assertTrue(out.isEmpty())
    }

    @Test
    fun keyComparisonIsCaseInsensitive() {
        // Same business key modulo case/whitespace is the survivor, not a ghost.
        val existing = listOf(entry(id = 1L, spoken = "  MY EMAIL ", syncId = "s-1"))
        val out = decideDictionarySupersededDeletions(existing, listOf(rec("s-1", "my email")))
        assertTrue(out.isEmpty())
    }

    @Test
    fun quarantinedGhostIsNeverSelected() {
        // Quarantined rows are held for recovery and excluded from the merge
        // input — a reconcile that never saw them must not hard-delete them,
        // even when they match the supersede shape exactly.
        val quarantined = entry(id = 1L, spoken = "my email", syncId = "s-1").copy(
            quarantineReason = "collision"
        )
        val existing = listOf(
            quarantined,
            entry(id = 2L, spoken = "my primary email", syncId = "s-1")
        )
        val out = decideDictionarySupersededDeletions(existing, listOf(rec("s-1", "my primary email")))
        assertTrue(out.isEmpty())
    }
}
