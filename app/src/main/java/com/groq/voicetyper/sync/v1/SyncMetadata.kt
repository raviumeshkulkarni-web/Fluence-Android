package com.groq.voicetyper.sync.v1

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-account sync metadata (frozen v1.1 §8, amendments #1).
 * One row per accountHash. Holds maxSeen (wall clock monotonic),
 * backfillDone, and per-domain lastRev for If-Match.
 */
@Entity(tableName = "sync_metadata")
data class SyncMetadata(
    @PrimaryKey val accountHash: String,
    val deviceId: String,
    val maxSeen: Long = 0L,
    val backfillDone: Boolean = false,
    val lastRevDictionary: String? = null,
    val lastRevSnippets: String? = null,
    val lastRevStats: String? = null,
    val lastRevSettings: String? = null,
)
