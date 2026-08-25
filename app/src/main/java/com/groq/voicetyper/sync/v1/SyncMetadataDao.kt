package com.groq.voicetyper.sync.v1

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncMetadataDao {
    @Query("SELECT * FROM sync_metadata WHERE accountHash = :hash LIMIT 1")
    suspend fun getByHash(hash: String): SyncMetadata?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: SyncMetadata)

    @Query("UPDATE sync_metadata SET maxSeen = :maxSeen WHERE accountHash = :hash")
    suspend fun updateMaxSeen(hash: String, maxSeen: Long)

    @Query("UPDATE sync_metadata SET backfillDone = 1 WHERE accountHash = :hash")
    suspend fun markBackfillDone(hash: String)

    @Query("UPDATE sync_metadata SET lastRevDictionary = :rev WHERE accountHash = :hash")
    suspend fun setLastRevDictionary(hash: String, rev: String?)

    @Query("UPDATE sync_metadata SET lastRevSnippets = :rev WHERE accountHash = :hash")
    suspend fun setLastRevSnippets(hash: String, rev: String?)

    @Query("UPDATE sync_metadata SET lastRevStats = :rev WHERE accountHash = :hash")
    suspend fun setLastRevStats(hash: String, rev: String?)

    @Query("UPDATE sync_metadata SET lastRevSettings = :rev WHERE accountHash = :hash")
    suspend fun setLastRevSettings(hash: String, rev: String?)

    @Query("UPDATE sync_metadata SET lastRevDictionary = NULL, lastRevSnippets = NULL, lastRevStats = NULL, lastRevSettings = NULL WHERE accountHash != :currentHash")
    suspend fun clearOtherRevs(currentHash: String)

    @Query("SELECT * FROM sync_metadata")
    suspend fun getAll(): List<SyncMetadata>
}
