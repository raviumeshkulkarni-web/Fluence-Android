package com.groq.voicetyper.sync.engine

import android.content.Context
import com.groq.voicetyper.snippets.Snippet
import com.groq.voicetyper.snippets.SnippetPreferences
import com.groq.voicetyper.sync.wire.RecordType
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Snippet JSON-document LocalStore (spec §30.4/§30.5).
 *
 * Maps [Snippet] entries (one per wire UUID) to engine rows, mirroring
 * Windows `SnippetSyncStore`. The collection has no unique constraint, so
 * imports map directly. Legacy entries (`uuid`/`created_at` null) are
 * backfilled on first mapping. All writes serialize through
 * [SyncMutationGate].
 */
class SnippetLocalStore(
    private val context: Context,
) : LocalStore {

    private fun <T> guarded(block: suspend () -> T): T = runBlocking {
        SyncMutationGate.mutex.withLock { withContext(Dispatchers.IO) { block() } }
    }

    private fun backfill() {
        val all = SnippetPreferences.allEntries(context)
        var changed = false
        val updated = all.map { snippet ->
            if (snippet.uuid == null || snippet.createdAt == null) {
                changed = true
                snippet.copy(
                    uuid = snippet.uuid ?: UUID.randomUUID().toString(),
                    createdAt = snippet.createdAt ?: System.currentTimeMillis(),
                )
            } else {
                snippet
            }
        }
        if (changed) SnippetPreferences.saveAll(context, updated)
    }

    override fun listRows(account: String?): List<LocalRow> = guarded {
        backfill()
        SnippetPreferences.allEntries(context)
            .filter { account == null || it.syncAccount == null || it.syncAccount == account }
            .mapNotNull(::toLocal)
            .sortedBy { it.uuid }
    }

    override fun findRow(uuid: String): LocalRow? = guarded {
        SnippetPreferences.allEntries(context)
            .firstOrNull { it.uuid == uuid }
            ?.let(::toLocal)
    }

    override fun import(row: LocalRow) {
        guarded {
            if (row.rtype != RecordType.Snippet) return@guarded
            backfill()
            val all = SnippetPreferences.allEntries(context).toMutableList()
            val existing = all.firstOrNull { it.uuid == row.uuid }
            val nextId = (all.maxOfOrNull { it.id } ?: 0L) + 1L
            val snippet = Snippet(
                id = existing?.id ?: nextId,
                trigger = row.trigger ?: "",
                expansion = row.text,
                uuid = row.uuid,
                createdAt = row.timestampMs,
                deletedAt = row.deletedAt,
                syncState = row.syncState,
                serverFileId = row.serverFileId,
                syncAccount = row.syncAccount,
                quarantineReason = row.quarantineReason,
            )
            if (existing != null) {
                all[all.indexOf(existing)] = snippet
            } else {
                all.add(snippet)
            }
            SnippetPreferences.saveAll(context, all)
        }
    }

    override fun markTombstoned(uuid: String, deletedAt: Long) {
        applyToUuid(uuid) { it.copy(deletedAt = deletedAt, syncState = SYNC_STATE_DIRTY) }
    }

    override fun setServerFileId(uuid: String, fileId: String) {
        applyToUuid(uuid) { it.copy(serverFileId = fileId, syncState = SYNC_STATE_CLEAN) }
    }

    override fun setSyncState(uuid: String, state: String) {
        applyToUuid(uuid) { it.copy(syncState = state) }
    }

    override fun quarantine(uuid: String, reason: QuarantineReason) {
        applyToUuid(uuid) {
            it.copy(quarantineReason = reason.asStr, syncState = SYNC_STATE_QUARANTINED)
        }
    }

    override fun clearQuarantine(uuid: String) {
        applyToUuid(uuid) {
            it.copy(quarantineReason = null, syncState = SYNC_STATE_LOCAL)
        }
    }

    override fun hardDelete(uuid: String) {
        guarded {
            backfill()
            val all = SnippetPreferences.allEntries(context).toMutableList()
            all.removeAll { it.uuid == uuid }
            SnippetPreferences.saveAll(context, all)
        }
    }

    private fun applyToUuid(uuid: String, transform: (Snippet) -> Snippet) {
        guarded {
            val all = SnippetPreferences.allEntries(context).toMutableList()
            val index = all.indexOfFirst { it.uuid == uuid }
            if (index < 0) return@guarded
            all[index] = transform(all[index])
            SnippetPreferences.saveAll(context, all)
        }
    }

    private fun toLocal(snippet: Snippet): LocalRow? {
        val uuid = snippet.uuid ?: return null
        return LocalRow(
            uuid = uuid,
            timestampMs = snippet.createdAt ?: 0L,
            text = snippet.expansion,
            mode = "",
            durationMs = 0,
            provider = "",
            model = null,
            language = null,
            deletedAt = snippet.deletedAt,
            serverFileId = snippet.serverFileId,
            syncAccount = snippet.syncAccount,
            syncState = snippet.syncState ?: SYNC_STATE_LOCAL,
            quarantineReason = snippet.quarantineReason,
            rtype = RecordType.Snippet,
            trigger = snippet.trigger,
        )
    }
}