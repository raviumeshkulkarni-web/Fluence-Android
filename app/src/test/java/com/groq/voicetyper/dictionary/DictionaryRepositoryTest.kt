package com.groq.voicetyper.dictionary

import android.content.Context
import com.groq.voicetyper.autolearn.SuggestionRepository
import com.groq.voicetyper.dictionary.data.CustomDictionaryDao
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DictionaryRepositoryTest {

    private val existing = CustomDictionaryEntry(
        id = 7,
        spokenText = "groq",
        replacementText = "Groq",
        isEnabled = true
    )

    private val tombstoned = CustomDictionaryEntry(
        id = 7,
        spokenText = "groq",
        replacementText = "Groq",
        isEnabled = true,
        deletedAt = 123456789L,
        syncState = "dirty",
        serverFileId = "DRIVE-FILE-123"
    )

    @Test
    fun `new entry without an existing phrase is inserted`() {
        assertEquals(
            DictionaryRepository.SaveAction.INSERT,
            DictionaryRepository.resolveSaveAction(null, 0L)
        )
    }

    @Test
    fun `editing an existing row updates in place`() {
        assertEquals(
            DictionaryRepository.SaveAction.UPDATE,
            DictionaryRepository.resolveSaveAction(existing, 7L)
        )
        assertEquals(
            DictionaryRepository.SaveAction.UPDATE,
            DictionaryRepository.resolveSaveAction(null, 7L)
        )
    }

    @Test
    fun `adding or renaming onto an existing phrase preserves that row`() {
        assertEquals(
            DictionaryRepository.SaveAction.PRESERVE,
            DictionaryRepository.resolveSaveAction(existing, 0L)
        )
        assertEquals(
            DictionaryRepository.SaveAction.PRESERVE,
            DictionaryRepository.resolveSaveAction(existing, 99L)
        )
    }

    @Test
    fun `re-adding a phrase when existing entry is tombstoned revives it via update`() {
        assertEquals(
            DictionaryRepository.SaveAction.UPDATE,
            DictionaryRepository.resolveSaveAction(tombstoned, 0L)
        )
    }

    @Test
    fun `deleteEntry with serverFileId tombstones the entry`() = runBlocking {
        val dao = mockk<CustomDictionaryDao>(relaxed = true)
        val entry = CustomDictionaryEntry(
            id = 10,
            spokenText = "brb",
            replacementText = "be right back",
            serverFileId = "DRIVE-FILE-456",
            syncState = "clean"
        )

        val slot = slot<CustomDictionaryEntry>()
        coEvery { dao.update(capture(slot)) } returns Unit

        DictionaryRepository.deleteEntryResolved(dao, entry)

        coVerify(exactly = 1) { dao.update(any()) }
        coVerify(exactly = 0) { dao.delete(any()) }
        assertNotNull(slot.captured.deletedAt)
        assertEquals("dirty", slot.captured.syncState)
        assertEquals("DRIVE-FILE-456", slot.captured.serverFileId)
    }

    @Test
    fun `deleteEntry without serverFileId hard deletes the entry`() = runBlocking {
        val dao = mockk<CustomDictionaryDao>(relaxed = true)
        val entry = CustomDictionaryEntry(
            id = 11,
            spokenText = "np",
            replacementText = "no problem",
            serverFileId = null,
            syncState = "local"
        )

        DictionaryRepository.deleteEntryResolved(dao, entry)

        coVerify(exactly = 1) { dao.delete(entry) }
        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun `deleteById with serverFileId tombstones the entry`() = runBlocking {
        val dao = mockk<CustomDictionaryDao>(relaxed = true)
        val entry = CustomDictionaryEntry(
            id = 12,
            spokenText = "omw",
            replacementText = "on my way",
            serverFileId = "DRIVE-FILE-789",
            syncState = "clean"
        )
        coEvery { dao.getById(12L) } returns entry

        val slot = slot<CustomDictionaryEntry>()
        coEvery { dao.update(capture(slot)) } returns Unit

        DictionaryRepository.deleteByIdResolved(dao, 12L)

        coVerify(exactly = 1) { dao.update(any()) }
        coVerify(exactly = 0) { dao.deleteById(any()) }
        assertNotNull(slot.captured.deletedAt)
        assertEquals("dirty", slot.captured.syncState)
    }

    @Test
    fun `deleteById without serverFileId hard deletes the entry`() = runBlocking {
        val dao = mockk<CustomDictionaryDao>(relaxed = true)
        val entry = CustomDictionaryEntry(
            id = 13,
            spokenText = "idk",
            replacementText = "I don't know",
            serverFileId = null,
            syncState = "local"
        )
        coEvery { dao.getById(13L) } returns entry

        DictionaryRepository.deleteByIdResolved(dao, 13L)

        coVerify(exactly = 1) { dao.delete(entry) }
        coVerify(exactly = 0) { dao.update(any()) }
    }

    // ------------------------------------------------------------------
    // H3 rename (delete-old + create-new) — mirrors Windows
    // update_dictionary_entry_internal. A spoken-text move changes the sync
    // business key, so the old row is retired (tombstone when pushed,
    // hard-delete when never pushed) and the renamed entry is inserted with
    // a fresh syncId. Same-key edits stay in place.
    // ------------------------------------------------------------------

    private fun renameBase(
        id: Long = 5L,
        spoken: String = "h3renx alpha",
        everPushed: Boolean = true
    ) = CustomDictionaryEntry(
        id = id,
        spokenText = spoken,
        replacementText = "Alpha",
        isEnabled = true,
        syncId = "11111111-1111-4111-8111-111111111111",
        createdAt = 100L,
        updatedAt = 100L,
        deviceId = "dev",
        dirty = false,
        everPushed = everPushed,
        syncAccount = null,
        syncState = "clean",
        serverFileId = if (everPushed) "DRIVE-1" else null
    )

    @Test
    fun `renaming a pushed entry tombstones the old row and inserts a fresh identity`() = runBlocking {
        val dao = mockk<CustomDictionaryDao>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val base = renameBase()
        coEvery { dao.getBySpokenTextUnstamped("h3renx beta") } returns null
        coEvery { dao.getById(5L) } returns base
        val tombstone = slot<CustomDictionaryEntry>()
        val inserted = slot<CustomDictionaryEntry>()
        coEvery { dao.update(capture(tombstone)) } returns Unit
        coEvery { dao.insert(capture(inserted)) } returns 42L

        val result = DictionaryRepository.saveEntryResolved(dao, context, "h3renx beta", "Beta", true, 5L)

        assertEquals(DictionaryRepository.SaveResult.UPDATED, result)
        // Old row retired as a tombstone under the OLD key...
        assertEquals("h3renx alpha", tombstone.captured.spokenText)
        assertNotNull(tombstone.captured.deletedAt)
        assertEquals("11111111-1111-4111-8111-111111111111", tombstone.captured.syncId)
        assertEquals(true, tombstone.captured.dirty)
        // ...and the renamed entry is a NEW row with a fresh wire identity.
        assertEquals("h3renx beta", inserted.captured.spokenText)
        assertNull(inserted.captured.deletedAt)
        assertNotEquals("11111111-1111-4111-8111-111111111111", inserted.captured.syncId)
        assertEquals(true, inserted.captured.dirty)
        assertEquals(false, inserted.captured.everPushed)
    }

    @Test
    fun `renaming a never-pushed entry hard deletes the old row`() = runBlocking {
        val dao = mockk<CustomDictionaryDao>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val base = renameBase(everPushed = false)
        coEvery { dao.getBySpokenTextUnstamped("h3renx beta") } returns null
        coEvery { dao.getById(5L) } returns base
        coEvery { dao.insert(any()) } returns 42L

        val result = DictionaryRepository.saveEntryResolved(dao, context, "h3renx beta", "Beta", true, 5L)

        assertEquals(DictionaryRepository.SaveResult.UPDATED, result)
        coVerify(exactly = 1) { dao.delete(base) }
        coVerify(exactly = 0) { dao.update(any()) }
        coVerify(exactly = 1) { dao.insert(match { it.spokenText == "h3renx beta" && it.deletedAt == null }) }
    }

    @Test
    fun `same-key edit stays in place with the same syncId`() = runBlocking {
        val dao = mockk<CustomDictionaryDao>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val base = renameBase()
        coEvery { dao.getBySpokenTextUnstamped("h3renx alpha") } returns null
        coEvery { dao.getById(5L) } returns base
        val updated = slot<CustomDictionaryEntry>()
        coEvery { dao.update(capture(updated)) } returns Unit

        val result = DictionaryRepository.saveEntryResolved(dao, context, "h3renx alpha", "Alpha 2", true, 5L)

        assertEquals(DictionaryRepository.SaveResult.UPDATED, result)
        assertEquals(5L, updated.captured.id)
        assertEquals("11111111-1111-4111-8111-111111111111", updated.captured.syncId)
        assertNull(updated.captured.deletedAt)
        assertEquals("Alpha 2", updated.captured.replacementText)
        coVerify(exactly = 0) { dao.insert(any()) }
    }

    @Test
    fun `saveEntryResolved refuses rule text over the Windows caps`() = runBlocking {
        // dictionary.rs:84-85 / 309-321 parity: over-cap rule text is
        // refused (PRESERVED = nothing changed), never trimmed silently.
        val overCapDao = mockk<CustomDictionaryDao>(relaxed = true)
        val context = mockk<Context>(relaxed = true)

        assertEquals(
            DictionaryRepository.SaveResult.PRESERVED,
            DictionaryRepository.saveEntryResolved(overCapDao, context, "x".repeat(201), "ok")
        )
        assertEquals(
            DictionaryRepository.SaveResult.PRESERVED,
            DictionaryRepository.saveEntryResolved(overCapDao, context, "ok", "y".repeat(1001))
        )
        coVerify(exactly = 0) { overCapDao.insert(any()) }
        coVerify(exactly = 0) { overCapDao.update(any()) }

        // At-cap rows take the regular path.
        val atCapDao = mockk<CustomDictionaryDao>(relaxed = true)
        assertEquals(
            DictionaryRepository.SaveResult.INSERTED,
            DictionaryRepository.saveEntryResolved(atCapDao, context, "a".repeat(200), "b".repeat(1000))
        )
        coVerify(exactly = 1) { atCapDao.insert(any()) }
    }

    @Test
    fun `applyCorrectionToExistingResolved activates the stored rule`() = runBlocking {
        // Audit finding 1: a disabled rule with the SAME replacement text
        // must still be re-enabled on accept; a tombstoned rule is revived.
        val dao = mockk<CustomDictionaryDao>(relaxUnitFun = true)
        val context = mockk<Context>(relaxed = true)
        val disabled = CustomDictionaryEntry(
            id = 7,
            spokenText = "shunade",
            replacementText = "Sinead",
            isEnabled = false
        )

        val changed = DictionaryRepository.applyCorrectionToExistingResolved(dao, disabled, context, "Sinead")
        assertEquals(DictionaryRepository.ApplyOutcome.APPLIED, changed)

        val revived = DictionaryRepository.applyCorrectionToExistingResolved(
            dao,
            disabled.copy(replacementText = "Sinead x", deletedAt = 123L),
            context,
            "Sinead"
        )
        assertEquals(DictionaryRepository.ApplyOutcome.APPLIED, revived)

        val updates = mutableListOf<CustomDictionaryEntry>()
        coVerify(exactly = 2) { dao.update(withArg { updates += it }) }
        assertEquals(true, updates[0].isEnabled)
        assertEquals("Sinead", updates[0].replacementText)
        // Revive: the tombstone is actually cleared, not just "updated"
        // (reviewer A claim 11 - the old test only pinned "an update ran").
        assertEquals(null, updates[1].deletedAt)
        assertEquals(true, updates[1].isEnabled)
        assertEquals(true, updates[1].everPushed)
        assertEquals("Sinead", updates[1].replacementText)
    }

    @Test
    fun `applyCorrectionToExistingResolved refuses over-cap text without writes`() = runBlocking {
        // Reviewer A blocker A: the cap guard must be observable so accept
        // can keep the suggestion pending instead of deleting it unapplied.
        val dao = mockk<CustomDictionaryDao>(relaxUnitFun = true)
        val context = mockk<Context>(relaxed = true)

        assertEquals(
            DictionaryRepository.ApplyOutcome.REFUSED_CAP,
            DictionaryRepository.applyCorrectionToExistingResolved(dao, tombstoned, context, "z".repeat(1001))
        )
        // Legacy pre-cap rows are refused on the spoken side too.
        assertEquals(
            DictionaryRepository.ApplyOutcome.REFUSED_CAP,
            DictionaryRepository.applyCorrectionToExistingResolved(
                dao, tombstoned.copy(spokenText = "w".repeat(201)), context, "ok"
            )
        )
        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun `deleteEntry dismisses the pair only when the delete lands`() = runBlocking {
        // Reviewer A blocker B parity check at the public seam: the
        // ownership-guarded delete controls deny memory (reviewer A claim 3).
        val context = mockk<Context>(relaxed = true)
        val entry = CustomDictionaryEntry(id = 3, spokenText = "shunade", replacementText = "Sinead")
        mockkObject(DictionaryRepository)
        mockkObject(SuggestionRepository)
        every { DictionaryRepository.getDao(any()) } returns mockk(relaxed = true)
        every { SuggestionRepository.getDao(any()) } returns mockk(relaxed = true)
        coEvery { DictionaryRepository.deleteEntryResolved(any(), any(), any()) } returns false

        DictionaryRepository.deleteEntry(context, entry)
        coVerify(exactly = 0) { SuggestionRepository.dismissPairForDeletedEntry(any(), any(), any()) }

        coEvery { DictionaryRepository.deleteEntryResolved(any(), any(), any()) } returns true
        DictionaryRepository.deleteEntry(context, entry)
        coVerify(exactly = 1) { SuggestionRepository.dismissPairForDeletedEntry(any(), any(), any()) }

        unmockkAll()
    }
}
