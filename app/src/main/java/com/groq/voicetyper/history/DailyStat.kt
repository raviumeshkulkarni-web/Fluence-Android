package com.groq.voicetyper.history

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stats_daily")
data class DailyStat(
    @PrimaryKey val day: String,
    val wordCount: Long,
    @ColumnInfo(defaultValue = "0") val count: Long = 0,
    @ColumnInfo(defaultValue = "0") val chars: Long = 0,
    val dictationMs: Long
)
