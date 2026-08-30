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
