package com.groq.voicetyper.history

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

object HistoryRepository {
    private var dao: TranscriptionHistoryDao? = null

    fun init(context: Context) {
        dao = FluenceDatabase.getInstance(context).transcriptionHistoryDao()
    }

    fun getAll(): Flow<List<TranscriptionEntry>> = dao!!.getAll()

    fun getById(id: Long): Flow<TranscriptionEntry?> = dao!!.getById(id)

    fun search(query: String): Flow<List<TranscriptionEntry>> = dao!!.search(query)

    suspend fun save(text: String, provider: String, model: String, language: String, durationMs: Long, isAgentMode: Boolean) {
        dao!!.insert(TranscriptionEntry(text = text, provider = provider, model = model, language = language, durationMs = durationMs, isAgentMode = isAgentMode, timestamp = System.currentTimeMillis()))
        CoroutineScope(Dispatchers.IO).launch {
            try {
                cleanupOldEntries(30)
            } catch (e: Exception) {
                Log.e("HistoryRepository", "Failed to cleanup old entries", e)
            }
        }
    }

    suspend fun delete(entry: TranscriptionEntry) = dao!!.delete(entry)

    suspend fun deleteByIds(ids: List<Long>) = dao!!.deleteByIds(ids)

    suspend fun clearAll() {
        dao!!.deleteAll()
    }

    suspend fun cleanupOldEntries(olderThanDays: Int = 30) {
        val cutoff = System.currentTimeMillis() - (olderThanDays * 24L * 60 * 60 * 1000)
        dao!!.deleteOlderThan(cutoff)
    }
}