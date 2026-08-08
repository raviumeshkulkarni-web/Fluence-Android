package com.groq.voicetyper.history

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

object HistoryRepository {
    @Volatile
    private var dao: TranscriptionHistoryDao? = null
    @Volatile
    private var statsDao: StatsDao? = null

    fun init(context: Context) {
        val db = FluenceDatabase.getInstance(context)
        dao = db.transcriptionHistoryDao()
        statsDao = db.statsDao()
    }

    fun getAll(): Flow<List<TranscriptionEntry>> = dao?.getAll() ?: emptyFlow()

    fun getById(id: Long): Flow<TranscriptionEntry?> = dao?.getById(id) ?: emptyFlow()

    fun search(query: String): Flow<List<TranscriptionEntry>> = dao?.search(query) ?: emptyFlow()

    fun getStats(): Flow<List<DailyStat>> = statsDao?.getAll() ?: emptyFlow()

    suspend fun save(context: Context, text: String, provider: String, model: String, language: String, durationMs: Long, isAgentMode: Boolean) {
        if (dao == null || statsDao == null) {
            init(context.applicationContext)
        }
        val entry = TranscriptionEntry(text = text, provider = provider, model = model, language = language, durationMs = durationMs, isAgentMode = isAgentMode, timestamp = System.currentTimeMillis())
        val words = StatsCalculator.wordCountOf(text).toLong()
        val ms = StatsCalculator.effectiveDurationMs(text, durationMs)
        val day = StatsCalculator.localDateOf(entry.timestamp)
        try {
            val db = FluenceDatabase.getInstance(context.applicationContext)
            db.withTransaction {
                dao?.insert(entry)
                val stats = statsDao
                if (stats != null) {
                    if (stats.increment(day, words, ms) == 0) {
                        stats.insert(DailyStat(day = day, wordCount = words, dictationMs = ms))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HistoryRepository", "Failed to save transcription to history", e)
        }
        try {
            cleanupToNewest(50)
        } catch (e: Exception) {
            Log.e("HistoryRepository", "Failed to cap history to newest entries", e)
        }
    }

    suspend fun delete(entry: TranscriptionEntry) = dao?.delete(entry)

    suspend fun deleteByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        dao?.deleteByIds(ids)
    }

    suspend fun clearAll() {
        dao?.deleteAll()
    }

    suspend fun cleanupToNewest(keep: Int = 50) {
        dao?.deleteAllExceptNewest(keep)
    }
}