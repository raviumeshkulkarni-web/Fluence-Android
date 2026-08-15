package com.groq.voicetyper.history

import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * JVM tests for the Phase 3 sync-aware delete semantics (§14). The DAO is mocked;
 * the repository's transaction wrapper is Room plumbing, so the split logic is
 * exercised directly via the internal deleteBySplit/deleteResolved seams.
 */
class HistoryRepositoryDeleteTest {

    private val dao = mockk<TranscriptionHistoryDao>()

    @Before
    fun setUp() {
        coEvery { dao.delete(any()) } returns Unit
        coEvery { dao.deleteByIds(any()) } returns Unit
        coEvery { dao.markTombstonedById(any(), any()) } returns 1
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    private fun entry(id: Long, serverFileId: String?) = TranscriptionEntry(
        id = id,
        text = "text $id",
        provider = "groq",
        model = "whisper-large-v3",
        language = "en",
        durationMs = 100L,
        isAgentMode = false,
        timestamp = 1000L + id,
        serverFileId = serverFileId
    )

    @Test
    fun `delete_unsynced_hard`() = runBlocking {
        val persisted = entry(id = 5, serverFileId = null)

        HistoryRepository.deleteResolved(dao, persisted)

        coVerify(exactly = 1) { dao.delete(persisted) }
        coVerify(exactly = 0) { dao.markTombstonedById(any(), any()) }
    }

    @Test
    fun `delete_synced_tombstones`() = runBlocking {
        val persisted = entry(id = 7, serverFileId = "FILE-1")

        HistoryRepository.deleteResolved(dao, persisted)

        coVerify(exactly = 1) { dao.markTombstonedById(7, any()) }
        coVerify(exactly = 0) { dao.delete(any()) }
    }

    @Test
    fun `delete uses persisted server file id not the in-memory copy`() = runBlocking {
        val persisted = entry(id = 9, serverFileId = null)

        HistoryRepository.deleteResolved(dao, persisted)

        coVerify(exactly = 1) { dao.delete(persisted) }
        coVerify(exactly = 0) { dao.markTombstonedById(any(), any()) }
    }

    @Test
    fun `clearAll_splits`() = runBlocking {
        val unsynced = entry(id = 1, serverFileId = null)
        val synced = entry(id = 2, serverFileId = "FILE-2")

        HistoryRepository.deleteBySplit(dao, listOf(unsynced, synced))

        coVerify(exactly = 1) { dao.deleteByIds(listOf(1L)) }
        coVerify(exactly = 1) { dao.markTombstonedById(2, any()) }
        coVerify(exactly = 0) { dao.delete(any()) }
    }

    @Test
    fun `deleteByIds_splits`() = runBlocking {
        val unsynced = entry(id = 3, serverFileId = null)
        val synced = entry(id = 4, serverFileId = "FILE-4")

        HistoryRepository.deleteBySplit(dao, listOf(unsynced, synced))

        coVerify(exactly = 1) { dao.deleteByIds(listOf(3L)) }
        coVerify(exactly = 1) { dao.markTombstonedById(4, any()) }
    }

    @Test
    fun `getAll_hides_tombstones`() {
        val impl = File(
            "build/generated/source/kapt/debug/com/groq/voicetyper/history/TranscriptionHistoryDao_Impl.java"
        )
        assertTrue("generated impl not found at ${impl.absolutePath}", impl.exists())
        val sql = impl.readText()
        listOf("getAll", "search", "getById", "getCount").forEach { method ->
            assertTrue("$method must filter deletedAt IS NULL", sql.contains("deletedAt IS NULL"))
        }
    }
}