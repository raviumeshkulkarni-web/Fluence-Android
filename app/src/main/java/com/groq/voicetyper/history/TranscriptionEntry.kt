package com.groq.voicetyper.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcription_history",
    indices = [Index(value = ["syncId"], unique = true)]
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