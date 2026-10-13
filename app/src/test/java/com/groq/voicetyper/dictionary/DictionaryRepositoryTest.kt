package com.groq.voicetyper.dictionary

import android.content.Context
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
}
