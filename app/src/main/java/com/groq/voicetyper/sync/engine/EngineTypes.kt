package com.groq.voicetyper.sync.engine

import com.groq.voicetyper.sync.wire.RecordContent
import com.groq.voicetyper.sync.wire.RecordType
import com.groq.voicetyper.sync.wire.WireRecord

const val SYNC_STATE_LOCAL: String = "local"
const val SYNC_STATE_CLEAN: String = "clean"
const val SYNC_STATE_DIRTY: String = "dirty"
const val SYNC_STATE_QUARANTINED: String = "quarantined"

data class LocalRow(
    val uuid: String,
    val timestampMs: Long,
    val text: String,
    val mode: String,
    val durationMs: Long,
    val provider: String,
    val model: String?,
    val language: String?,
    val deletedAt: Long?,
    val serverFileId: String?,
    val syncAccount: String?,
    val syncState: String,
    val quarantineReason: String?,
    // §30 record kind + content fields (spec §30.1).
    val rtype: RecordType = RecordType.History,
    val spoken: String? = null,
    val corrected: String? = null,
    val kind: String? = null,
    val trigger: String? = null,
    val expansion: String? = null,
    val settingsKey: String? = null,
    val settingsValue: String? = null,
) {
    fun content(): RecordContent = when (rtype) {
        RecordType.History -> RecordContent.History(
            com.groq.voicetyper.sync.wire.ContentTuple(
                createdAt = timestampMs,
                text = text,
                mode = mode,
                durationMs = durationMs,
                provider = provider,
                model = model,
                language = language,
            )
        )
        RecordType.Dictionary -> RecordContent.Dictionary(
            com.groq.voicetyper.sync.wire.DictionaryTuple(
                createdAt = timestampMs,
                spoken = spoken ?: "",
                corrected = corrected ?: "",
                kind = kind ?: "",
            )
        )
        RecordType.Snippet -> RecordContent.Snippet(
            com.groq.voicetyper.sync.wire.SnippetTuple(
                createdAt = timestampMs,
                trigger = trigger ?: "",
                expansion = expansion ?: "",
            )
        )
        RecordType.Settings -> RecordContent.Settings(
            com.groq.voicetyper.sync.wire.SettingsTuple(
                createdAt = timestampMs,
                key = settingsKey ?: "",
                value = settingsValue ?: "",
            )
        )
    }

    fun isTombstoned(): Boolean = deletedAt != null

    fun isLatched(): Boolean = quarantineReason != null

    fun toWire(): WireRecord = WireRecord(
        v = 1,
        id = uuid,
        createdAt = timestampMs,
        deletedAt = deletedAt,
        rtype = rtype,
        text = text,
        mode = mode,
        durationMs = durationMs,
        provider = provider,
        model = model,
        language = language,
        spoken = spoken,
        corrected = corrected,
        kind = kind,
        trigger = trigger,
        expansion = expansion,
        settingsKey = settingsKey,
        settingsValue = settingsValue,
    )
}

data class FileMeta(
    val fileId: String,
    val name: String,
)

data class GroupedFile(
    val fileId: String,
    val name: String,
    val record: WireRecord,
)

enum class GroupVerdict {
    Absent,
    HealthyLive,
    HealthyDeleted,
    Divergent,
}

enum class QuarantineReason(val asStr: String) {
    ContentDeviation("content_deviation"),
    CorruptFile("corrupt_file"),
    UnknownSchemaVersion("unknown_schema_version"),
    IdNameMismatch("id_name_mismatch"),
    UnknownType("unknown_type"),
    Collision("collision"),
}

sealed class SyncAction {
    data class ImportLive(val row: LocalRow) : SyncAction()
    data class ImportTombstone(val row: LocalRow) : SyncAction()
    data class ImportQuarantined(val row: LocalRow) : SyncAction()
    data class MarkTombstoned(val uuid: String, val deletedAt: Long) : SyncAction()
    data class Quarantine(val uuid: String, val reason: QuarantineReason) : SyncAction()
    data class HardDelete(val uuid: String) : SyncAction()
    data class Create(val uuid: String, val record: WireRecord) : SyncAction()
    data class Reupload(val uuid: String, val record: WireRecord) : SyncAction()
    data class PatchTombstone(val fileId: String, val record: WireRecord) : SyncAction()
}

data class SyncOutcome(
    var imported: Int = 0,
    var created: Int = 0,
    var reuploaded: Int = 0,
    var patches: Int = 0,
    var tombstonedLocal: Int = 0,
    var quarantined: Int = 0,
    var hardDeleted: Int = 0,
    var retryableFailures: Int = 0,
)

sealed class SyncError(message: String? = null) : Exception(message) {
    class Retryable(message: String) : SyncError(message)
    class Fatal(message: String) : SyncError(message)
    object AuthRequired : SyncError("authentication required")
}