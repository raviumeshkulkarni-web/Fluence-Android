package com.groq.voicetyper.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcription_history",
    indices = [
        Index(value = ["syncId"], unique = true),
        // Windows parity (idx_history_timestamp_ms): every live-history read
        // is WHERE deletedAt IS NULL ORDER BY timestamp — without this the
        // query is a full SCAN + sort on every emission, growing with the
        // now-unbounded table.
        Index(value = ["deletedAt", "timestamp"])
    ]
)
data class TranscriptionEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val provider: String,
    val model: String? = null,
    val language: String? = null,
    val durationMs: Long,
    val isAgentMode: Boolean,
    val timestamp: Long,
    val syncId: String? = null,
    val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "local") val syncState: String = "local",
    val serverFileId: String? = null,
    val syncAccount: String? = null,
    val quarantineReason: String? = null
)