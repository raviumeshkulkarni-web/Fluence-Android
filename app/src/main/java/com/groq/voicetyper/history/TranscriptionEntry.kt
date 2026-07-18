package com.groq.voicetyper.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcription_history")
data class TranscriptionEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val provider: String,
    val model: String,
    val language: String,
    val durationMs: Long,
    val isAgentMode: Boolean,
    val timestamp: Long
)