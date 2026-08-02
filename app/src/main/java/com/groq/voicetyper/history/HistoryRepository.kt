package com.groq.voicetyper.history

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

object HistoryRepository {
    private var dao: TranscriptionHistoryDao? = null

    fun init(context: Context) {
        dao = FluenceDatabase.getInstance(context).transcriptionHistoryDao()
    }

    fun getAll(): Flow<List<TranscriptionEntry>> = dao?.getAll() ?: emptyFlow()

    fun getById(id: Long): Flow<TranscriptionEntry?> = dao?.getById(id) ?: emptyFlow()

    fun search(query: String): Flow<List<TranscriptionEntry>> = dao?.search(query) ?: emptyFlow()

    suspend fun save(text: String, provider: String, model: String, language: String, durationMs: Long, isAgentMode: Boolean) {
        dao?.insert(TranscriptionEntry(text = text, provider = provider, model = model, language = language, durationMs = durationMs, isAgentMode = isAgentMode, timestamp = System.currentTimeMillis()))
        try {
            cleanupToNewest(30)
        } catch (e: Exception) {
            Log.e("HistoryRepository", "Failed to cap history to newest entries", e)
        }
    }

    suspend fun delete(entry: TranscriptionEntry) = dao?.delete(entry)

    suspend fun deleteByIds(ids: List<Long>) = dao?.deleteByIds(ids)

    suspend fun clearAll() {
        dao?.deleteAll()
    }

    suspend fun cleanupToNewest(keep: Int = 30) {
        dao?.deleteAllExceptNewest(keep)
    }
}