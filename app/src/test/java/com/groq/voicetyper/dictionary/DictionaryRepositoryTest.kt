package com.groq.voicetyper.dictionary

import com.groq.voicetyper.dictionary.data.CustomDictionaryDao
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
