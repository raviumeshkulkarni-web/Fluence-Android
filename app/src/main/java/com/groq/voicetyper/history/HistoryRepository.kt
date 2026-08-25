package com.groq.voicetyper.history

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.groq.voicetyper.sync.SyncAccounts
import com.groq.voicetyper.sync.stats.DayCounters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

object HistoryRepository {
    @Volatile
    private var dao: TranscriptionHistoryDao? = null
    @Volatile
    private var statsDao: StatsDao? = null
    @Volatile
    private var db: FluenceDatabase? = null
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        val database = FluenceDatabase.getInstance(context)
        db = database
        dao = database.transcriptionHistoryDao()
        statsDao = database.statsDao()
        appContext = context.applicationContext
    }

    fun getAll(): Flow<List<TranscriptionEntry>> = dao?.getAll() ?: emptyFlow()

    fun getById(id: Long): Flow<TranscriptionEntry?> = dao?.getById(id) ?: emptyFlow()

    fun search(query: String): Flow<List<TranscriptionEntry>> = dao?.search(query) ?: emptyFlow()

    fun getStats(): Flow<List<DailyStat>> = statsDao?.getAll() ?: emptyFlow()

    /**
     * Unified, account-wide daily statistics.
     *
     * Signed in: sourced from the account's union-dedup stats events
     * (`stat_sync`) — the same set every device converges to after a sync, so
     * Windows + Android contributions sum naturally (X + Y). Events are
     * written at dictation-commit time, so today's local dictations appear
     * immediately, before the next sync runs.
     *
     * Signed out: platform-local `stats_daily` only.
     *
     * Transcription history itself is never consulted for these totals and
     * never leaves the device; clearing history cannot reduce them.
     */
    fun observeUnifiedStats(accountKey: String?): Flow<Map<String, DayCounters>> {
        val database = db ?: return emptyFlow()
        val stats = database.statsDao()
        val statSyncDao = database.statSyncDao()

        return if (!accountKey.isNullOrEmpty()) {
            val hash = com.groq.voicetyper.sync.v1.AccountHash.of(accountKey)
            if (hash == null) {
                stats.getAll().map { list ->
                    list.associate {
                        it.day to DayCounters(words = it.wordCount, count = it.count, chars = it.chars, ms = it.dictationMs)
                    }
                }
            } else {
                // Room's invalidation tracker re-emits whenever stat_sync
                // changes (dictation commit or sync apply), so fresh local
                // dictations and remote contributions appear automatically.
                statSyncDao.observeByAccount(hash).map { rows ->
                    aggregateAccountEvents(rows)
                }
            }
        } else {
            stats.getAll().map { list ->
                list.associate {
                    it.day to DayCounters(words = it.wordCount, count = it.count, chars = it.chars, ms = it.dictationMs)
                }
            }
        }
    }

    /** Sum the account's union-dedup events into per-day counters. */
    private fun aggregateAccountEvents(rows: List<com.groq.voicetyper.sync.v1.StatSyncEntry>): Map<String, DayCounters> {
        val map = HashMap<String, DayCounters>()
        for (row in rows) {
            if (row.deletedAt != null) continue
            val cur = map[row.day] ?: DayCounters()
            map[row.day] = cur.copy(
                words = cur.words + row.wordCount,
                count = cur.count + 1,
                chars = cur.chars + row.chars,
                ms = cur.ms + row.durationMs
            )
        }
        return map
    }

    /**
     * Account-wide statistics from the merged v1.2 stat_sync ledger.
     * Returns null if the user is not signed in.
     */
    suspend fun getAccountStats(): Map<String, DayCounters>? {
        val database = db ?: return null
        val context = appContext ?: return null
        val hash = com.groq.voicetyper.sync.v1.AccountHash.of(
            com.groq.voicetyper.sync.auth.SyncAuthSession(context).accountEmail
        ) ?: return null
        return aggregateAccountEvents(database.statSyncDao().getByAccount(hash))
    }

    suspend fun save(context: Context, text: String, provider: String, model: String, language: String, durationMs: Long, isAgentMode: Boolean): Boolean {
        if (dao == null || statsDao == null) {
            init(context.applicationContext)
        }
        val timestamp = System.currentTimeMillis()
        val syncId = UUID.randomUUID().toString()
        val entry = TranscriptionEntry(text = text, provider = provider, model = model, language = language, durationMs = durationMs, isAgentMode = isAgentMode, timestamp = timestamp, syncId = syncId)
        val words = StatsCalculator.wordCountOf(text).toLong()
        val chars = text.length.toLong()
        val day = StatsCalculator.utcDateOf(timestamp)
        try {
            val database = FluenceDatabase.getInstance(context.applicationContext)
            database.withTransaction {
                dao?.insert(entry)
                val stats = statsDao
                val increments = database.dictationIncrementDao()
                if (stats != null && DictationIncrementGate.shouldApply(
                        increments.exists(syncId),
                        increments.insertIgnore(DictationIncrement(dictationId = syncId, day = day, words = words, count = 1L, chars = chars, ms = durationMs))
                    )
                ) {
                    if (stats.increment(day, words, 1L, chars, durationMs) == 0) {
                        stats.insert(DailyStat(day = day, wordCount = words, count = 1L, chars = chars, dictationMs = durationMs))
                    }
                }
                // Frozen v1.2: every completed dictation contributes exactly one
                // account-level stats event. The eventId is deterministic per
                // dictation UUID, so a duplicated commit collapses under union
                // dedup — exactly-once counting by construction. Safe offline;
                // the event rides the next successful sync. Transcription
                // history itself NEVER leaves this device.
                val statSyncDao = database.statSyncDao()
                if (statSyncDao.getByEventId(v12EventId(syncId)) == null) {
                    statSyncDao.insertIgnore(
                        com.groq.voicetyper.sync.v1.StatSyncEntry(
                            eventId = v12EventId(syncId),
                            day = day,
                            wordCount = words.toInt(),
                            durationMs = durationMs,
                            updatedAt = timestamp,
                            deletedAt = null,
                            deviceId = com.groq.voicetyper.sync.v1.DeviceIdProvider.getDeviceId(context.applicationContext),
                            accountHash = com.groq.voicetyper.sync.v1.AccountHash.of(
                                com.groq.voicetyper.sync.auth.SyncAuthSession(context.applicationContext).accountEmail
                            ),
                            dirty = true,
                            everPushed = false,
                            chars = chars.toInt(),
                            timestampMs = timestamp
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("HistoryRepository", "Failed to save transcription to history", e)
            return false
        }
        return true
    }

    /** Deterministic per-dictation event id (stable across retries/restarts). */
    private fun v12EventId(dictationSyncId: String): String =
        java.util.UUID.nameUUIDFromBytes("fluence-stat-v1:$dictationSyncId".toByteArray()).toString()

    suspend fun delete(entry: TranscriptionEntry) {
        val database = db ?: return
        val historyDao = dao ?: return
        database.withTransaction {
            val persisted = historyDao.getRowById(entry.id) ?: return@withTransaction
            deleteResolved(historyDao, persisted)
        }
    }

    suspend fun deleteByIds(ids: List<Long>) {
        if (ids.isEmpty()) return
        val database = db ?: return
        val historyDao = dao ?: return
        database.withTransaction {
            deleteBySplit(historyDao, historyDao.getRowsByIds(ids))
        }
    }

    suspend fun clearAll() {
        val database = db ?: return
        val historyDao = dao ?: return
        database.withTransaction {
            deleteBySplit(historyDao, historyDao.getAllRows())
        }
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