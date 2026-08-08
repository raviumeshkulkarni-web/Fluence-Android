package com.groq.voicetyper.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stats_daily")
data class DailyStat(
    @PrimaryKey val day: String,
    val wordCount: Long,
    val dictationMs: Long
)
