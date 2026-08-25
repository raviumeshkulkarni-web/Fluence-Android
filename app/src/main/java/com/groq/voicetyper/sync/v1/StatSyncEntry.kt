package com.groq.voicetyper.sync.v1

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "stat_sync", indices = [Index(value = ["eventId"], unique = true)])
data class StatSyncEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: String,
    val day: String,
    val wordCount: Int,
    val durationMs: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val deviceId: String? = null,
    val accountHash: String? = null,
    val dirty: Boolean = false,
    val everPushed: Boolean = false,
    val chars: Int = 0,
    val timestampMs: Long = 0
)
