package com.groq.voicetyper.history

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.groq.voicetyper.sync.SyncAccounts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

object HistoryRepository {
    @Volatile
    private var dao: TranscriptionHistoryDao? = null
    @Volatile
    private var statsDao: StatsDao? = null
    @Volatile
    private var db: FluenceDatabase? = null

    fun init(context: Context) {
        val database = FluenceDatabase.getInstance(context)
        db = database
        dao = database.transcriptionHistoryDao()
        statsDao = database.statsDao()
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
        val day = StatsCalculator.utcDateOf(entry.timestamp)
        try {
            val database = FluenceDatabase.getInstance(context.applicationContext)
            database.withTransaction {
                dao?.insert(entry)
                val stats = statsDao
                if (stats != null) {
                    if (stats.increment(day, words, durationMs) == 0) {
                        stats.insert(DailyStat(day = day, wordCount = words, dictationMs = durationMs))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HistoryRepository", "Failed to save transcription to history", e)
        }
    }

    suspend fun delete(entry: TranscriptionEntry) {
        val database = db ?: return
        val historyDao = dao ?: return
        val stats = statsDao
        database.withTransaction {
            val persisted = historyDao.getRowById(entry.id) ?: return@withTransaction
            deleteResolved(historyDao, persisted)
            if (stats != null) rebuildStats(stats, historyDao)
        }
    }

    suspend fun deleteByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        val database = db ?: return
        val historyDao = dao ?: return
        val stats = statsDao
        database.withTransaction {
            deleteBySplit(historyDao, historyDao.getRowsByIds(ids))
            if (stats != null) rebuildStats(stats, historyDao)
        }
    }

    suspend fun clearAll() {
        val database = db ?: return
        val historyDao = dao ?: return
        val stats = statsDao
        database.withTransaction {
            deleteBySplit(historyDao, historyDao.getAllRows())
            if (stats != null) rebuildStats(stats, historyDao)
        }
    }

    /** Rebuilds `stats_daily` from live rows (deletedAt IS NULL), matching Windows `compute_stats`. */
    internal suspend fun rebuildStats(statsDao: StatsDao, historyDao: TranscriptionHistoryDao) {
        statsDao.clear()
        statsDao.insertAll(StatsCalculator.dailyAggregates(historyDao.getAllLiveRows()))
    }

    /** §14: never-uploaded rows (server_file_id NULL) are hard-deleted; uploaded rows are tombstoned. Foreign-account rows are skipped (§29 #3b). */
    internal suspend fun deleteResolved(historyDao: TranscriptionHistoryDao, persisted: TranscriptionEntry) {
        if (SyncAccounts.isForeign(persisted.syncAccount)) return
        if (persisted.serverFileId == null) {
            historyDao.delete(persisted)
        } else {
            historyDao.markTombstonedById(persisted.id, System.currentTimeMillis())
        }
    }

    /** §14: one transaction — split rows by server_file_id, hard-delete unsynced / tombstone uploaded. Foreign-account rows are skipped (§29 #3b). */
    internal suspend fun deleteBySplit(historyDao: TranscriptionHistoryDao, rows: List<TranscriptionEntry>) {
        val owned = rows.filter { !SyncAccounts.isForeign(it.syncAccount) }
        val unsyncedIds = owned.filter { it.serverFileId == null }.map { it.id }
        if (unsyncedIds.isNotEmpty()) {
            historyDao.deleteByIds(unsyncedIds)
        }
        owned.filter { it.serverFileId != null }
            .forEach { historyDao.markTombstonedById(it.id, System.currentTimeMillis()) }
    }
}