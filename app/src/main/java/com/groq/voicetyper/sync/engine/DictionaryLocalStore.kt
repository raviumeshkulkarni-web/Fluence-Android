package com.groq.voicetyper.sync.engine

import com.groq.voicetyper.dictionary.data.CustomDictionaryDao
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.history.FluenceDatabase
import com.groq.voicetyper.sync.wire.RecordType
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Room-backed LocalStore for the dictionary kind (spec §30.4/§30.5, §10).
 *
 * Maps [CustomDictionaryEntry] rows (one per wire UUID, `syncId` unique) to
 * engine rows. Rows created before sync have `syncId = NULL`; the store
 * assigns each a UUID + `created_at` on first mapping (like Windows
 * `DictionarySyncStore.backfill_legacy_created_at`). `kind` is always
 * `correction` on Android (no kind column). User edits are serialized with
 * the engine through [SyncMutationGate]; imports that would collide with a
 * live user row absorb (identical, §10) or latch with `collision`
 * ([DictionaryImportPolicy]).
 */
class DictionaryLocalStore(
    private val db: FluenceDatabase,
    private val dao: CustomDictionaryDao,
) : LocalStore {

    private fun <T> guarded(block: suspend () -> T): T = runBlocking {
        SyncMutationGate.mutex.withLock { withContext(Dispatchers.IO) { block() } }
    }

    private suspend fun backfillSyncMeta() {
        for (row in dao.getRowsWithoutSyncId()) {
            dao.backfillSyncMeta(row.id, UUID.randomUUID().toString(), System.currentTimeMillis())
        }
    }

    override fun listRows(account: String?): List<LocalRow> = guarded {
        backfillSyncMeta()
        val rows = if (account == null) dao.getSyncRowsUnstamped() else dao.getSyncRows(account)
        rows.mapNotNull(::toLocal).sortedBy { it.uuid }
    }

    override fun findRow(uuid: String): LocalRow? = guarded {
        dao.getBySyncId(uuid)?.let(::toLocal)
    }

    override fun import(row: LocalRow) {
        guarded {
            val incoming = fromLocal(row) ?: return@guarded
            val uuid = incoming.syncId ?: return@guarded
            val existing = dao.getBySyncId(uuid)
            if (existing != null) {
                if (existing.quarantineReason != null) {
                    // Re-latch: keep the sentinel placeholder, refresh metadata
                    // (content cannot be stored — the spokenText collides with
                    // a live user row on every pass).
                    dao.update(
                        existing.copy(
                            createdAt = incoming.createdAt,
                            deletedAt = incoming.deletedAt,
                            syncAccount = incoming.syncAccount,
                            syncState = SYNC_STATE_QUARANTINED,
                            quarantineReason = QuarantineReason.Collision.asStr,
                        )
                    )
                } else {
                    dao.update(incoming.copy(id = existing.id))
                }
                return@guarded
            }

            val colliding = dao.getBySpokenText(incoming.spokenText)
            when (DictionaryImportPolicy.decide(colliding, incoming)) {
                DictionaryImportPolicy.Decision.Absorb -> {
                    // §10 duplicate-identical: the user's row adopts the wire
                    // identity; content is already identical.
                    dao.update(
                        colliding!!.copy(
                            syncId = uuid,
                            createdAt = incoming.createdAt,
                            deletedAt = null,
                            syncState = incoming.syncState,
                            serverFileId = incoming.serverFileId,
                            syncAccount = incoming.syncAccount,
                            quarantineReason = null,
                        )
                    )
                }
                DictionaryImportPolicy.Decision.Latch -> {
                    // Quarantined placeholder: the real content cannot coexist
                    // with the live user row (unique spokenText, §30.4). The
                    // latch keeps the wire identity so re-imports re-latch.
                    dao.insert(
                        CustomDictionaryEntry(
                            spokenText = "",
                            replacementText = "",
                            isEnabled = false,
                            syncId = uuid,
                            createdAt = incoming.createdAt,
                            deletedAt = incoming.deletedAt,
                            syncState = SYNC_STATE_QUARANTINED,
                            serverFileId = null,
                            syncAccount = incoming.syncAccount,
                            quarantineReason = QuarantineReason.Collision.asStr,
                        )
                    )
                }
                DictionaryImportPolicy.Decision.Upsert -> {
                    dao.insert(incoming)
                }
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

    private fun toLocal(entry: CustomDictionaryEntry): LocalRow? {
        val uuid = entry.syncId ?: return null // unmapped rows never reach the engine
        return LocalRow(
            uuid = uuid,
            timestampMs = entry.createdAt ?: 0L,
            text = "",
            mode = "",
            durationMs = 0,
            provider = "",
            model = null,
            language = null,
            deletedAt = entry.deletedAt,
            serverFileId = entry.serverFileId,
            syncAccount = entry.syncAccount,
            syncState = entry.syncState,
            quarantineReason = entry.quarantineReason,
            rtype = RecordType.Dictionary,
            spoken = entry.spokenText,
            corrected = entry.replacementText,
            kind = KIND_CORRECTION,
        )
    }

    private fun fromLocal(row: LocalRow): CustomDictionaryEntry? {
        if (row.rtype != RecordType.Dictionary) return null
        return CustomDictionaryEntry(
            spokenText = row.spoken ?: "",
            replacementText = row.corrected ?: "",
            isEnabled = true,
            syncId = row.uuid,
            createdAt = row.timestampMs,
            deletedAt = row.deletedAt,
            syncState = row.syncState,
            serverFileId = row.serverFileId,
            syncAccount = row.syncAccount,
            quarantineReason = row.quarantineReason,
        )
    }

    private companion object {
        const val KIND_CORRECTION = "correction"
    }
}