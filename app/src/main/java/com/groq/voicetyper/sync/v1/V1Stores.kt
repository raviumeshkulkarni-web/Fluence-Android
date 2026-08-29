package com.groq.voicetyper.sync.v1

import android.content.Context
import com.groq.voicetyper.dictionary.data.CustomDictionaryDao
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.history.FluenceDatabase
import com.groq.voicetyper.history.StatsCalculator
import com.groq.voicetyper.snippets.Snippet
import androidx.room.withTransaction
import com.groq.voicetyper.snippets.SnippetPreferences
import com.groq.voicetyper.sync.auth.SyncAuthSession
import kotlinx.coroutines.runBlocking

/**
 * Concrete v1.2 store implementations over Room + SharedPreferences.
 * Settings live in [PrefsSettingsV1Store] (V1StoresSettings.kt).
 *
 * Invariants honored here:
 * - load == accountHash only.
 * - applyMergedAndClearDirty: upsert merge winners + dirty=0 + everPushed=1
 *   in ONE transaction per domain call.
 * - Stats backfill: single source = live transcription_history rows, else
 *   stats_daily aggregates; backfillDone lives in sync_metadata per accountHash.
 */
object V1Stores {

    fun dictionaryStore(context: Context): RoomDictionaryV1Store {
        val db = FluenceDatabase.getInstance(context.applicationContext)
        return RoomDictionaryV1Store(db)
    }

    fun statStore(context: Context): RoomStatV1Store {
        val db = FluenceDatabase.getInstance(context.applicationContext)
        return RoomStatV1Store(
            db,
            db.statSyncDao(),
            db.transcriptionHistoryDao(),
            db.statsDao(),
            db.syncMetadataDao()
        )
    }

    fun settingsStore(context: Context): PrefsSettingsV1Store =
        PrefsSettingsV1Store(context.applicationContext)

    fun snippetStore(context: Context): PrefsSnippetV1Store =
        PrefsSnippetV1Store(context.applicationContext)

    fun metadataDao(context: Context): SyncMetadataDao =
        FluenceDatabase.getInstance(context.applicationContext).syncMetadataDao()
}

object MutationClock {
    fun next(context: Context): Long {
        return try {
            val hash = try { AccountHash.of(SyncAuthSession(context.applicationContext).accountEmail) } catch (_: Exception) { null }
            val seen = if (hash != null) {
                try { runBlocking { V1Stores.metadataDao(context).getByHash(hash)?.maxSeen ?: 0L } } catch (_: Exception) { 0L }
            } else 0L
            maxOf(System.currentTimeMillis(), seen + 1L)
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}

fun decideDictionaryApply(currentUpdatedAt: Long?, currentDirty: Boolean, rec: com.groq.voicetyper.sync.v1.DictionaryRecord): Boolean {
    return !(currentDirty && (currentUpdatedAt ?: 0L) > rec.updatedAt)
}

/**
 * Pure retention decision for the snippet apply pass (dictionary-store
 * parity). Rows owned by a DIFFERENT account survive untouched (they belong
 * to a previous sign-in and must never be dropped by this account's pass);
 * rows with no wire identity or a pending local change survive even when
 * absent from the merge (unsynced new/edit — they ride the next PUT);
 * current-account clean rows survive only when the merge still holds them —
 * absence means a newer remote tombstone won elsewhere.
 */
fun decideSnippetRetention(local: List<Snippet>, mergedIds: Set<String>, currentAccountHash: String): List<Snippet> {
    return local.filter { s ->
        val sid = s.effectiveSyncId()
        when {
            s.syncAccount != null && s.syncAccount != currentAccountHash -> true
            sid == null || s.dirty -> true
            else -> sid in mergedIds
        }
    }
}

/**
 * Mid-pass dirty guard for one merged snippet (decideDictionaryApply
 * parity): a locally-dirty row newer than the merged winner keeps its local
 * content and dirty flag so it rides the next PUT instead of being clobbered.
 */
fun decideSnippetApply(current: Snippet, rec: SnippetRecord): Boolean {
    return !(current.dirty && (current.updatedAt ?: 0L) > rec.updatedAt)
}

/**
 * Contract defense: quarantined rows must be impossible to serialize via the
 * sync store even if a future writer stamps quarantineReason — they are
 * filtered out of every sync load.
 */
fun excludeQuarantined(rows: List<CustomDictionaryEntry>): List<CustomDictionaryEntry> =
    rows.filter { it.quarantineReason == null }

// ----------------------------------------------------------------------
// Dictionary
// ----------------------------------------------------------------------

class RoomDictionaryV1Store(private val db: FluenceDatabase) : V1SyncEngine.DictionaryV1Store {

    private val dao: CustomDictionaryDao = db.customDictionaryDao()
    private val metaDao: SyncMetadataDao = db.syncMetadataDao()

    override suspend fun loadByAccount(hash: String): List<V1SyncEngine.DictionaryLocal> =
        excludeQuarantined(dao.getAllByAccount(hash)).map { it.toLocal() }

    override suspend fun stampUnstamped(hash: String) {
        // Enrollment: claim rows with no account ownership, assign wire
        // identities to legacy rows, then give any stamped row lacking an LWW
        // timestamp a valid one (monotonic clock). Without this, a legacy row
        // would serialize updatedAt=0 and fail cross-platform validation.
        val claimed = dao.getSyncRowsUnstamped()
        if (claimed.isEmpty()) return
        val meta = metaDao.getByHash(hash)
        val fallbackDeviceId = meta?.deviceId.orEmpty()
        val nextTs = Clock.nextUpdatedAt(Clock.nowWallMs(), meta?.maxSeen ?: 0L)
        for (row in claimed) {
            val fixed = row.copy(
                syncAccount = hash,
                syncId = row.syncId ?: java.util.UUID.randomUUID().toString(),
                updatedAt = if ((row.updatedAt ?: 0L) <= 0L)
                    (row.createdAt?.takeIf { it > 0 } ?: nextTs) else row.updatedAt,
                deviceId = row.deviceId ?: fallbackDeviceId,
                dirty = true,
                everPushed = false
            )
            dao.update(fixed)
        }
    }

    private fun nowWallMs(): Long = System.currentTimeMillis()

    override suspend fun hasDirty(hash: String): Boolean =
        dao.getDirtyByAccount(hash).isNotEmpty()

    override suspend fun applyMergedAndClearDirty(
        hash: String,
        deviceId: String,
        merged: List<DictionaryRecord>
    ) = db.withTransaction {
        val existing = dao.getAllByAccount(hash)
        val bySyncId = existing.associateBy { it.syncId }
        // Windows parity: the account's row set is replaced by the merged
        // winners. Records that LOST the merge (superseded by a newer remote
        // tombstone or edit) must not linger locally as live ghosts.
        val mergedIds = merged.map { it.syncId }.toSet()
        for (row in existing) {
            if (row.syncId !in mergedIds) {
                dao.hardDeleteBySyncId(row.syncId ?: continue)
            }
        }
        val skipClear = mutableSetOf<String>()
        val appliedIds = mutableListOf<String>()
        for (rec in merged) {
            val current = bySyncId[rec.syncId]
            if (current != null) {
                if (!decideDictionaryApply(current.updatedAt, current.dirty, rec)) {
                    skipClear.add(rec.syncId)
                    continue
                }
                dao.update(
                    current.copy(
                        spokenText = rec.spoken,
                        replacementText = rec.corrected,
                        isEnabled = rec.isEnabled,
                        deletedAt = rec.deletedAt,
                        updatedAt = rec.updatedAt,
                        deviceId = rec.deviceId,
                        syncAccount = hash,
                        dirty = false,
                        everPushed = true
                    )
                )
                appliedIds.add(rec.syncId)
            } else {
                // Remote-won record new to this device. Replace a losing
                // current-account row in place so the account-scoped unique
                // key remains valid without silently ignoring the import.
                val collision = dao.getByBusinessKeyIncludingDeleted(rec.businessKey, hash)
                if (collision != null && collision.syncId != rec.syncId && collision.syncId != null) {
                    // Mid-pass LWW guard (mirrors decideDictionaryApply on the bySyncId
                    // path above): if a concurrent write made this collision row dirty
                    // and strictly newer than the remote winner since loadByAccount ran,
                    // the local state already wins on LWW — leave it dirty to ride next PUT.
                    if (collision.dirty && (collision.updatedAt ?: 0L) > rec.updatedAt) {
                        continue
                    }
                    dao.update(
                        collision.copy(
                            spokenText = rec.spoken,
                            replacementText = rec.corrected,
                            isEnabled = rec.isEnabled,
                            syncId = rec.syncId,
                            createdAt = rec.updatedAt,
                            deletedAt = rec.deletedAt,
                            updatedAt = rec.updatedAt,
                            deviceId = rec.deviceId,
                            syncAccount = hash,
                            dirty = false,
                            everPushed = true
                        )
                    )
                    appliedIds.add(rec.syncId)
                    continue
                }
                val insertedId = dao.insert(
                    CustomDictionaryEntry(
                        spokenText = rec.spoken,
                        replacementText = rec.corrected,
                        isEnabled = rec.isEnabled,
                        syncId = rec.syncId,
                        createdAt = rec.updatedAt,
                        deletedAt = rec.deletedAt,
                        updatedAt = rec.updatedAt,
                        syncAccount = hash,
                        deviceId = rec.deviceId,
                        dirty = false,
                        everPushed = true
                    )
                )
                if (insertedId != -1L || dao.getBySyncId(rec.syncId) != null) {
                    appliedIds.add(rec.syncId)
                }
            }
        }
        val toClear = appliedIds.filterNot { it in skipClear }
        if (toClear.isNotEmpty()) {
            dao.clearDirtyBySyncIds(hash, toClear)
        }
    }

    private fun CustomDictionaryEntry.toLocal() = V1SyncEngine.DictionaryLocal(
        syncId = syncId ?: "",
        businessKey = spokenText.trim().lowercase(),
        spoken = spokenText,
        corrected = replacementText,
        isEnabled = isEnabled,
        updatedAt = updatedAt ?: 0L,
        deletedAt = deletedAt,
        deviceId = deviceId ?: "",
        accountHash = syncAccount,
        dirty = dirty,
        everPushed = everPushed
    )
}

// ----------------------------------------------------------------------
// Stats
// ----------------------------------------------------------------------

class RoomStatV1Store(
    private val db: com.groq.voicetyper.history.FluenceDatabase,
    private val statDao: StatSyncDao,
    private val historyDao: com.groq.voicetyper.history.TranscriptionHistoryDao,
    private val statsDao: com.groq.voicetyper.history.StatsDao,
    private val metadataDao: SyncMetadataDao
) : V1SyncEngine.StatV1Store {

    override suspend fun loadByAccount(hash: String): List<V1SyncEngine.StatLocal> =
        statDao.getByAccount(hash).map { it.toLocal() }

    override suspend fun stampUnstamped(hash: String) {
        statDao.stampUnstamped(hash)
    }

    override suspend fun hasDirty(hash: String): Boolean =
        statDao.getDirtyByAccount(hash).isNotEmpty()

    override suspend fun applyMergedAndClearDirty(
        hash: String,
        deviceId: String,
        merged: List<StatRecord>
    ) = db.withTransaction {
        val toClear = mutableListOf<String>()
        for (rec in merged) {
            val before = statDao.getByEventId(rec.eventId)
            statDao.insertIgnore(
                StatSyncEntry(
                    eventId = rec.eventId,
                    day = rec.day,
                    wordCount = rec.wordCount,
                    durationMs = rec.durationMs,
                    updatedAt = rec.updatedAt,
                    deletedAt = rec.deletedAt,
                    deviceId = rec.deviceId,
                    accountHash = hash,
                    dirty = false,
                    everPushed = true,
                    chars = rec.chars,
                    timestampMs = rec.timestampMs
                )
            )
            if (before == null) {
                // newly inserted
                toClear.add(rec.eventId)
            } else if (!before.dirty) {
                // already clean - safe to mark pushed
                toClear.add(rec.eventId)
            }
            // else: existing dirty (mid-pass creation) -> keep dirty=true
        }
        if (toClear.isNotEmpty()) {
            statDao.clearDirtyByEventIds(hash, toClear.distinct())
        }
    }

    override suspend fun isBackfillDone(hash: String): Boolean =
        metadataDao.getByHash(hash)?.backfillDone == true

    override suspend fun setBackfillDone(hash: String, done: Boolean) {
        if (done) metadataDao.markBackfillDone(hash)
    }

    /**
     * One-time seed per accountHash. Single source: live transcription_history
     * rows when any exist, else stats_daily aggregates. UTC day bucketing;
     * deterministic eventIds make re-runs idempotent under union dedup.
     */
    override suspend fun backfillIfNeeded(hash: String, deviceId: String): Boolean {
        val now = System.currentTimeMillis()
        val liveRows = historyDao.getAllLiveRows()
        val records = if (liveRows.isNotEmpty()) {
            Backfill.fromTranscriptionRows(
                liveRows.map { row ->
                    Backfill.TranscriptionRowLite(
                        timestampMs = row.timestamp,
                        wordCount = StatsCalculator.wordCountOf(row.text),
                        durationMs = row.durationMs,
                        syncId = row.syncId ?: "",
                        chars = row.text.length
                    )
                },
                hash, deviceId, now
            )
        } else {
            Backfill.fromDailyStats(
                statsDao.getAllOnce().map { Backfill.DailyStatLite(it.day, it.wordCount.toInt(), it.dictationMs) },
                hash, deviceId, now
            )
        }
        var inserted = 0
        for (rec in records) {
            if (statDao.getByEventId(rec.eventId) == null) {
                statDao.insertIgnore(
                    StatSyncEntry(
                        eventId = rec.eventId,
                        day = rec.day,
                        wordCount = rec.wordCount,
                        durationMs = rec.durationMs,
                        updatedAt = rec.updatedAt,
                        deletedAt = null,
                        deviceId = deviceId,
                        accountHash = hash,
                        dirty = true,
                        everPushed = false,
                        chars = rec.chars,
                        timestampMs = rec.timestampMs
                    )
                )
                inserted++
            }
        }
        return records.isNotEmpty()
    }

    private fun StatSyncEntry.toLocal() = V1SyncEngine.StatLocal(
        eventId = eventId,
        day = day,
        wordCount = wordCount,
        durationMs = durationMs,
        updatedAt = updatedAt,
        deviceId = deviceId ?: "",
        deletedAt = deletedAt,
        accountHash = accountHash,
        dirty = dirty,
        everPushed = everPushed,
        timestampMs = timestampMs,
        chars = chars
    )
}

// ----------------------------------------------------------------------
// Snippets (SharedPreferences JSON document)
// ----------------------------------------------------------------------

class PrefsSnippetV1Store(private val context: Context) : V1SyncEngine.SnippetV1Store {

    override suspend fun loadByAccount(hash: String): List<V1SyncEngine.SnippetLocal> =
        SnippetPreferences.allEntries(context)
            .filter { it.syncAccount == hash }
            .map { s ->
                V1SyncEngine.SnippetLocal(
                    syncId = s.effectiveSyncId() ?: "",
                    businessKey = s.businessKey(),
                    trigger = s.trigger,
                    expansion = s.expansion,
                    isEnabled = s.isEnabled,
                    updatedAt = s.updatedAt ?: s.createdAt ?: 0L,
                    deletedAt = s.deletedAt,
                    deviceId = s.deviceId ?: "",
                    accountHash = s.syncAccount,
                    dirty = s.dirty,
                    everPushed = s.everPushed
                )
            }

    override suspend fun stampUnstamped(hash: String) {
        val all = SnippetPreferences.allEntries(context)
        val needsStamp = all.any { it.syncAccount == null }
        if (!needsStamp) return
        val now = System.currentTimeMillis()
        SnippetPreferences.saveAll(context, all.map { s ->
            if (s.syncAccount == null) {
                s.copy(
                    syncAccount = hash,
                    deviceId = s.deviceId ?: DeviceIdProvider.getDeviceId(context),
                    updatedAt = s.updatedAt ?: s.createdAt ?: now,
                    dirty = true,
                    everPushed = false
                )
            } else s
        })
    }

    override suspend fun hasDirty(hash: String): Boolean =
        SnippetPreferences.allEntries(context).any { it.syncAccount == hash && it.dirty }

    override suspend fun applyMergedAndClearDirty(
        hash: String,
        deviceId: String,
        merged: List<SnippetRecord>
    ) {
        val all = SnippetPreferences.allEntries(context)
        // Retention first (decideSnippetRetention): foreign-account rows and
        // unsynced/dirty rows survive untouched; current-account clean rows
        // absent from the merge were tombstoned elsewhere and are dropped.
        val kept = decideSnippetRetention(all, merged.map { it.syncId }.toSet(), hash).toMutableList()
        var nextFree = ((kept.maxOfOrNull { it.id } ?: 0L)) + 1L
        for (rec in merged) {
            val idx = kept.indexOfFirst { it.effectiveSyncId() == rec.syncId }
            if (idx >= 0) {
                val current = kept[idx]
                // Never restamp another account's row; defer to a newer dirty
                // local edit created during the GET→PUT window.
                if (current.syncAccount != null && current.syncAccount != hash) continue
                if (!decideSnippetApply(current, rec)) continue
                kept[idx] = current.copy(
                    trigger = rec.trigger,
                    expansion = rec.expansion,
                    isEnabled = rec.isEnabled,
                    deletedAt = rec.deletedAt,
                    updatedAt = rec.updatedAt,
                    deviceId = rec.deviceId,
                    syncAccount = hash,
                    dirty = false,
                    everPushed = true
                )
            } else {
                kept.add(
                    Snippet(
                        id = nextFree++,
                        trigger = rec.trigger,
                        expansion = rec.expansion,
                        isEnabled = rec.isEnabled,
                        uuid = rec.syncId,
                        createdAt = rec.updatedAt,
                        updatedAt = rec.updatedAt,
                        deletedAt = rec.deletedAt,
                        syncAccount = hash,
                        deviceId = rec.deviceId,
                        dirty = false,
                        everPushed = true
                    )
                )
            }
        }
        SnippetPreferences.saveAll(context, kept)
    }
}
