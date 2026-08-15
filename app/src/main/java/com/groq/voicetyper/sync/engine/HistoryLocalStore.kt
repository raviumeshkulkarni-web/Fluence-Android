package com.groq.voicetyper.sync.engine

import com.groq.voicetyper.history.FluenceDatabase
import com.groq.voicetyper.history.TranscriptionEntry
import com.groq.voicetyper.history.TranscriptionHistoryDao
import com.groq.voicetyper.sync.wire.RecordType
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shared local-mutation mutex (mirrors Windows `LOCAL_MUTATION_MUTEX`, §30.2).
 * Every store mutation (import, tombstone, stamp) and the engine's row reads
 * serialize through it so user edits and a running pass never interleave a
 * read-modify-write.
 */
internal object SyncMutationGate {
    val mutex: Mutex = Mutex()
}

/** Wire `mode` for `history` records (spec §30.1). */
private const val WIRE_MODE_TRANSCRIPTION = "transcription"
private const val WIRE_MODE_AGENT = "agent"

/**
 * Room-backed LocalStore for the history kind (§30.5, §30.4 Android).
 *
 * Maps [TranscriptionEntry] rows (one per wire UUID, `syncId` unique) to
 * engine rows. Rows created before sync have `syncId = NULL`; the store
 * assigns each a UUID on first mapping (like Windows `HistorySyncStore`),
 * so the wire identity always exists before the engine sees a row. There is
 * no `mode` column on Android, so the wire `mode` (`transcription` |
 * `agent`) maps onto the existing `isAgentMode` column: `agent` ⇔ true,
 * `transcription` ⇔ false. The bijection preserves the mode through the
 * local round-trip, so imported records re-export the same mode they came
 * in with.
 */
class HistoryLocalStore(
    private val db: FluenceDatabase,
    private val dao: TranscriptionHistoryDao,
) : LocalStore {

    private fun <T> guarded(block: suspend () -> T): T = runBlocking {
        SyncMutationGate.mutex.withLock { withContext(Dispatchers.IO) { block() } }
    }

    private suspend fun backfillSyncIds() {
        for (row in dao.getRowsWithoutSyncId()) {
            dao.assignSyncId(row.id, UUID.randomUUID().toString())
        }
    }

    override fun listRows(account: String?): List<LocalRow> = guarded {
        backfillSyncIds()
        val rows = if (account == null) {
            dao.getSyncRowsUnstamped()
        } else {
            dao.getSyncRows(account)
        }
        rows.mapNotNull(::toLocal).sortedBy { it.uuid }
    }

    override fun findRow(uuid: String): LocalRow? = guarded {
        dao.getBySyncId(uuid)?.let(::toLocal)
    }

    override fun import(row: LocalRow) {
        guarded {
            val entry = fromLocal(row) ?: return@guarded
            val uuid = entry.syncId ?: return@guarded
            val existing = dao.getBySyncId(uuid)
            if (existing != null) {
                dao.update(entry.copy(id = existing.id))
            } else {
                dao.insert(entry)
            }
        }
    }

    override fun markTombstoned(uuid: String, deletedAt: Long) {
        guarded { dao.markTombstonedBySyncId(uuid, deletedAt) }
    }

    override fun setServerFileId(uuid: String, fileId: String) {
        guarded { dao.setServerFileIdAndStateBySyncId(uuid, fileId) }
    }

    override fun setSyncState(uuid: String, state: String) {
        guarded { dao.updateSyncStateBySyncId(uuid, state) }
    }

    override fun quarantine(uuid: String, reason: QuarantineReason) {
        guarded { dao.quarantineBySyncId(uuid, reason.asStr) }
    }

    override fun clearQuarantine(uuid: String) {
        guarded {
            dao.clearQuarantineBySyncId(uuid)
            dao.updateSyncStateBySyncId(uuid, SYNC_STATE_LOCAL)
        }
    }

    override fun hardDelete(uuid: String) {
        guarded { dao.hardDeleteBySyncId(uuid) }
    }

    private fun toLocal(entry: TranscriptionEntry): LocalRow? {
        val uuid = entry.syncId ?: return null // unmapped rows never reach the engine
        return LocalRow(
            uuid = uuid,
            timestampMs = entry.timestamp,
            text = entry.text,
            mode = if (entry.isAgentMode) WIRE_MODE_AGENT else WIRE_MODE_TRANSCRIPTION,
            durationMs = entry.durationMs,
            provider = entry.provider,
            model = entry.model,
            language = entry.language,
            deletedAt = entry.deletedAt,
            serverFileId = entry.serverFileId,
            syncAccount = entry.syncAccount,
            syncState = entry.syncState,
            quarantineReason = entry.quarantineReason,
            rtype = RecordType.History,
        )
    }

    private fun fromLocal(row: LocalRow): TranscriptionEntry? {
        if (row.rtype != RecordType.History) return null
        return TranscriptionEntry(
            text = row.text,
            provider = row.provider,
            model = row.model,
            language = row.language,
            durationMs = row.durationMs,
            isAgentMode = row.mode == WIRE_MODE_AGENT,
            timestamp = row.timestampMs,
            syncId = row.uuid,
            deletedAt = row.deletedAt,
            syncState = row.syncState,
            serverFileId = row.serverFileId,
            syncAccount = row.syncAccount,
            quarantineReason = row.quarantineReason,
        )
    }
}