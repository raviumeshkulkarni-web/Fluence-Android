package com.groq.voicetyper.sync.cache

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Row of the sync change-detection cache (§31). One row per Drive file;
 * `recordJson` is the canonical `WireRecord.toJson()` for the content seen
 * under [md5]. Never the source of truth — a missing or stale row only costs
 * a re-download.
 */
@Entity(tableName = "sync_file_cache")
data class SyncFileCache(
    @PrimaryKey val fileId: String,
    val md5: String?,
    val recordJson: String,
)
