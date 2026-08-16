package com.groq.voicetyper.sync.engine

import com.groq.voicetyper.sync.wire.InvalidReason
import com.groq.voicetyper.sync.wire.ParseResult
import com.groq.voicetyper.sync.wire.RecordType
import com.groq.voicetyper.sync.wire.WireRecord
import com.groq.voicetyper.sync.wire.parse
import com.groq.voicetyper.sync.wire.tombstone
import com.groq.voicetyper.sync.wire.tuplesEqual
import com.groq.voicetyper.sync.wire.uuidBasename
import java.util.TreeMap

object SyncEngine {

    /**
     * Run one full sync pass for `account` and one record [kind] (§30.1). Rows
     * of other kinds and groups of other kinds are inert for the pass. Pure
     * reconciliation — all side effects go through the traits.
     */
    fun run(
        kind: RecordType,
        account: String?,
        local: LocalStore,
        drive: DriveStore,
        token: TokenProvider,
    ): SyncOutcome {
        if (!token.hasValidToken()) {
            throw SyncError.AuthRequired
        }
        val outcome = SyncOutcome()

        drive.findOrCreateFolder()
        val listing = drive.listFiles()
        val listedNames: Map<String, String> = listing.associate { it.fileId to it.name }

        val groups = TreeMap<String, Group>()
        for (file in listing) {
            val uuid = uuidBasename(file.name) ?: continue
            val content = drive.getContent(file.fileId) ?: continue
            when (val parsed = parse(content, uuid)) {
                is ParseResult.Ok -> groups.getOrPut(uuid) { Group() }.files.add(
                    GroupedFile(file.fileId, file.name, parsed.record)
                )
                is ParseResult.Err -> groups.getOrPut(uuid) { Group() }.invalid.add(
                    file.fileId to parsed.reason
                )
            }
        }
        for (group in groups.values) {
            group.files.sortBy { it.fileId }
            group.invalid.sortBy { it.first }
        }

        val rows = local.listRows(account).sortedBy { it.uuid }
        val known: Set<String> = rows.map { it.uuid }.toSet()

        val localActions = mutableListOf<SyncAction>()
        val pushActions = mutableListOf<SyncAction>()

        for (row in rows) {
            if (row.rtype != kind) continue // §30: other kinds are inert for this pass
            if (row.isLatched()) continue
            val group = groups[row.uuid]
            if (group == null) {
                if (row.isTombstoned()) {
                    if (row.serverFileId == null) {
                        localActions.add(SyncAction.HardDelete(row.uuid))
                    }
                } else if (row.serverFileId == null && row.syncAccount == null) {
                    pushActions.add(SyncAction.Create(row.uuid, row.toWire()))
                }
            } else {
                when (classify(group, row)) {
                    GroupVerdict.HealthyLive -> {
                        if (row.isTombstoned()) {
                            pushActions.addAll(patchLiveFiles(group, row.toWire()))
                        } else if (row.serverFileId == null && row.syncAccount == null) {
                            pushActions.add(SyncAction.Create(row.uuid, row.toWire()))
                        }
                    }
                    GroupVerdict.HealthyDeleted -> {
                        val deletedAt = groupDeletedAt(group)
                        if (row.isTombstoned()) {
                            pushActions.addAll(patchLiveFiles(group, row.toWire()))
                        } else {
                            val t = tombstone(row.toWire(), deletedAt)
                            localActions.add(SyncAction.MarkTombstoned(row.uuid, deletedAt))
                            pushActions.addAll(patchLiveFiles(group, t))
                        }
                    }
                    GroupVerdict.Divergent -> {
                        localActions.add(
                            SyncAction.Quarantine(row.uuid, divergentReason(group, hasRow = true))
                        )
                    }
                    GroupVerdict.Absent -> {}
                }
            }
        }

        for ((uuid, group) in groups) {
            if (known.contains(uuid)) continue
            if (local.findRow(uuid) != null) continue
            val firstFile = group.files.firstOrNull()
            if (firstFile != null && firstFile.record.rtype != kind) continue
            when (classify(group, null)) {
                GroupVerdict.HealthyLive -> {
                    val record = group.files[0].record
                    localActions.add(SyncAction.ImportLive(importLiveRow(record, account)))
                }
                GroupVerdict.HealthyDeleted -> {
                    val record = group.files[0].record
                    val row = importTombstoneRow(record, groupDeletedAt(group), account)
                    pushActions.addAll(patchLiveFiles(group, row.toWire()))
                    localActions.add(SyncAction.ImportTombstone(row))
                }
                GroupVerdict.Divergent -> {
                    localActions.add(
                        SyncAction.ImportQuarantined(
                            importPlaceholderRow(uuid, kind, account, divergentReason(group, hasRow = false))
                        )
                    )
                }
                GroupVerdict.Absent -> {}
            }
        }

        for (action in localActions) {
            when (action) {
                is SyncAction.ImportLive -> {
                    local.import(action.row)
                    outcome.imported += 1
                }
                is SyncAction.ImportTombstone -> {
                    local.import(action.row)
                    outcome.imported += 1
                }
                is SyncAction.ImportQuarantined -> {
                    local.import(action.row)
                    outcome.imported += 1
                }
                is SyncAction.MarkTombstoned -> {
                    local.markTombstoned(action.uuid, action.deletedAt)
                    outcome.tombstonedLocal += 1
                }
                is SyncAction.Quarantine -> {
                    local.quarantine(action.uuid, action.reason)
                    outcome.quarantined += 1
                }
                is SyncAction.HardDelete -> {
                    local.hardDelete(action.uuid)
                    outcome.hardDeleted += 1
                }
                is SyncAction.Create,
                is SyncAction.Reupload,
                is SyncAction.PatchTombstone -> error("push actions are not applied in the local phase")
            }
        }

        val reuploads = mutableListOf<SyncAction>()
        absenceCandidates(kind, rows, listedNames)?.let { candidates ->
            val relist = drive.listFiles()
            val relisted: Map<String, String> = relist.associate { it.fileId to it.name }
            for (row in candidates) {
                if (isAbsent(row, relisted)) {
                    reuploads.add(SyncAction.Reupload(row.uuid, row.toWire()))
                }
            }
        }

        val reuploadedUuids = mutableSetOf<String>()
        val patchedFileIds = mutableSetOf<String>()
        for (action in pushActions + reuploads) {
            when (action) {
                is SyncAction.Create -> {
                    val fileId = try {
                        drive.createFile(fileName(action.uuid), action.record)
                    } catch (e: SyncError) {
                        when (e) {
                            // AuthRequired/Fatal abort the pass (§23).
                            is SyncError.AuthRequired, is SyncError.Fatal -> throw e
                            // Rejected: permanent client rejection, counted;
                            // the pass continues and surfaces non-success.
                            is SyncError.Rejected -> outcome.rejectedFailures += 1
                            // Retryable: counted, pass continues (§7).
                            is SyncError.Retryable -> outcome.retryableFailures += 1
                        }
                        continue
                    }
                    local.setServerFileId(action.uuid, fileId)
                    local.setSyncState(action.uuid, SYNC_STATE_CLEAN)
                    outcome.created += 1
                }
                is SyncAction.Reupload -> {
                    val fileId = try {
                        drive.createFile(fileName(action.uuid), action.record)
                    } catch (e: SyncError) {
                        when (e) {
                            // AuthRequired/Fatal abort the pass (§23).
                            is SyncError.AuthRequired, is SyncError.Fatal -> throw e
                            // Rejected: permanent client rejection, counted;
                            // the pass continues and surfaces non-success.
                            is SyncError.Rejected -> outcome.rejectedFailures += 1
                            // Retryable: counted, pass continues (§7).
                            is SyncError.Retryable -> outcome.retryableFailures += 1
                        }
                        continue
                    }
                    local.setServerFileId(action.uuid, fileId)
                    local.setSyncState(action.uuid, SYNC_STATE_CLEAN)
                    reuploadedUuids.add(action.uuid)
                    outcome.reuploaded += 1
                }
                is SyncAction.PatchTombstone -> {
                    try {
                        drive.updateContent(action.fileId, action.record)
                    } catch (e: SyncError) {
                        when (e) {
                            // AuthRequired/Fatal abort the pass (§23).
                            is SyncError.AuthRequired, is SyncError.Fatal -> throw e
                            // Rejected: permanent client rejection, counted;
                            // the pass continues and surfaces non-success.
                            is SyncError.Rejected -> outcome.rejectedFailures += 1
                            // Retryable: counted, pass continues (§7).
                            is SyncError.Retryable -> outcome.retryableFailures += 1
                        }
                        continue
                    }
                    patchedFileIds.add(action.fileId)
                    outcome.patches += 1
                }
                is SyncAction.ImportLive,
                is SyncAction.ImportTombstone,
                is SyncAction.ImportQuarantined,
                is SyncAction.MarkTombstoned,
                is SyncAction.Quarantine,
                is SyncAction.HardDelete -> error("local actions never reach PUSH")
            }
        }

        val fixups = mutableListOf<Pair<String, String>>()
        for (row in local.listRows(account)) {
            val group = groups[row.uuid]
            val verdict = if (group != null) classify(group, row) else GroupVerdict.Absent
            val derived = derivedSyncState(
                row,
                verdict,
                groupFullyTombstoned(row, group, reuploadedUuids, patchedFileIds)
            )
            if (row.syncState != derived) {
                fixups.add(row.uuid to derived)
            }
        }
        for ((uuid, state) in fixups) {
            local.setSyncState(uuid, state)
        }
        for (row in local.listRows(account)) {
            val group = groups[row.uuid]
            val verdict = if (group != null) classify(group, row) else GroupVerdict.Absent
            val derived = derivedSyncState(
                row,
                verdict,
                groupFullyTombstoned(row, group, reuploadedUuids, patchedFileIds)
            )
            assert(row.syncState == derived) { "sync_state invariant violated for row ${row.uuid}" }
        }

        return outcome
    }

    fun derivedSyncState(
        row: LocalRow,
        verdict: GroupVerdict,
        groupFullyTombstoned: Boolean,
    ): String {
        if (row.isLatched()) return SYNC_STATE_QUARANTINED
        if (row.isTombstoned()) {
            return if (groupFullyTombstoned) SYNC_STATE_CLEAN else SYNC_STATE_DIRTY
        }
        return when (verdict) {
            GroupVerdict.Divergent -> SYNC_STATE_QUARANTINED
            GroupVerdict.HealthyDeleted -> SYNC_STATE_DIRTY
            GroupVerdict.Absent, GroupVerdict.HealthyLive ->
                if (row.serverFileId != null) SYNC_STATE_CLEAN else SYNC_STATE_LOCAL
        }
    }
}

private class Group(
    val files: MutableList<GroupedFile> = mutableListOf(),
    val invalid: MutableList<Pair<String, InvalidReason>> = mutableListOf(),
)

private fun absenceCandidates(
    kind: RecordType,
    rows: List<LocalRow>,
    listedNames: Map<String, String>,
): List<LocalRow>? {
    val candidates = rows
        .filter { it.rtype == kind }
        .filter { !it.isLatched() && isAbsent(it, listedNames) }
    return candidates.ifEmpty { null }
}

private fun isAbsent(row: LocalRow, listedNames: Map<String, String>): Boolean {
    val sfi = row.serverFileId ?: return false
    val name = listedNames[sfi] ?: return true
    return name != fileName(row.uuid)
}

private fun classify(group: Group, row: LocalRow?): GroupVerdict {
    if (group.invalid.isNotEmpty()) return GroupVerdict.Divergent
    val t0 = group.files[0].record.content()
    if (group.files.drop(1).any { !tuplesEqual(it.record.content(), t0) }) {
        return GroupVerdict.Divergent
    }
    if (row != null && !tuplesEqual(row.content(), t0)) {
        return GroupVerdict.Divergent
    }
    return if (group.files.any { it.record.deletedAt != null }) {
        GroupVerdict.HealthyDeleted
    } else {
        GroupVerdict.HealthyLive
    }
}

private fun divergentReason(group: Group, hasRow: Boolean): QuarantineReason {
    group.invalid.firstOrNull()?.let { (_, reason) -> return quarantineReasonOf(reason) }
    val t0 = group.files[0].record.content()
    val filesDisagree = group.files.drop(1).any { !tuplesEqual(it.record.content(), t0) }
    return if (filesDisagree) {
        QuarantineReason.Collision
    } else if (hasRow) {
        QuarantineReason.ContentDeviation
    } else {
        QuarantineReason.Collision
    }
}

private fun quarantineReasonOf(reason: InvalidReason): QuarantineReason = when (reason) {
    InvalidReason.MalformedJson,
    InvalidReason.BadTimestamp,
    InvalidReason.BadMode,
    InvalidReason.NonIntegral,
    InvalidReason.MissingTypeField,
    InvalidReason.BadKind -> QuarantineReason.CorruptFile

    InvalidReason.UnknownSchemaVersion -> QuarantineReason.UnknownSchemaVersion
    InvalidReason.IdNameMismatch -> QuarantineReason.IdNameMismatch
    InvalidReason.UnknownType -> QuarantineReason.UnknownType
}

private fun groupDeletedAt(group: Group): Long =
    group.files.firstOrNull { it.record.deletedAt != null }?.record?.deletedAt
        ?: error("HealthyDeleted verdict implies a tombstoned file")

private fun patchLiveFiles(group: Group, record: WireRecord): List<SyncAction> =
    group.files
        .filter { it.record.deletedAt == null }
        .map { SyncAction.PatchTombstone(it.fileId, record) }

private fun fileName(uuid: String): String = "$uuid.json"

private fun importLiveRow(record: WireRecord, account: String?): LocalRow = LocalRow(
    uuid = record.id,
    timestampMs = record.createdAt,
    text = record.text,
    mode = record.mode,
    durationMs = record.durationMs,
    provider = record.provider,
    model = record.model,
    language = record.language,
    deletedAt = null,
    serverFileId = null,
    syncAccount = account,
    syncState = SYNC_STATE_LOCAL,
    quarantineReason = null,
    rtype = record.rtype,
    spoken = record.spoken,
    corrected = record.corrected,
    kind = record.kind,
    trigger = record.trigger,
    expansion = record.expansion,
    settingsKey = record.settingsKey,
    settingsValue = record.settingsValue,
)

private fun importTombstoneRow(
    record: WireRecord,
    deletedAt: Long,
    account: String?,
): LocalRow {
    val row = importLiveRow(record, account)
    return row.copy(deletedAt = deletedAt, syncState = SYNC_STATE_CLEAN)
}

private fun importPlaceholderRow(
    uuid: String,
    kind: RecordType,
    account: String?,
    reason: QuarantineReason,
): LocalRow = LocalRow(
    uuid = uuid,
    timestampMs = 0,
    text = "",
    mode = "",
    durationMs = 0,
    provider = "",
    model = null,
    language = null,
    deletedAt = null,
    serverFileId = null,
    syncAccount = account,
    syncState = SYNC_STATE_QUARANTINED,
    quarantineReason = reason.asStr,
    rtype = kind,
    spoken = null,
    corrected = null,
    kind = null,
    trigger = null,
    expansion = null,
    settingsKey = null,
    settingsValue = null,
)

private fun groupFullyTombstoned(
    row: LocalRow,
    group: Group?,
    reuploadedUuids: Set<String>,
    patchedFileIds: Set<String>,
): Boolean {
    val g = group ?: return reuploadedUuids.contains(row.uuid)
    return g.files.all { it.record.deletedAt != null || patchedFileIds.contains(it.fileId) }
}