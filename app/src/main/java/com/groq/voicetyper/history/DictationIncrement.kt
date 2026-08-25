package com.groq.voicetyper.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "dictation_increments")
data class DictationIncrement(
    @PrimaryKey val dictationId: String,
    val day: String,
    val words: Long,
    val count: Long,
    val chars: Long,
    val ms: Long
)
