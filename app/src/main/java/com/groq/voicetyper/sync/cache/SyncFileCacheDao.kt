package com.groq.voicetyper.sync.cache

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SyncFileCacheDao {
    @Query("SELECT * FROM sync_file_cache")
    suspend fun all(): List<SyncFileCache>

    @Upsert
    suspend fun upsert(entry: SyncFileCache)

    @Query("DELETE FROM sync_file_cache WHERE fileId = :fileId")
    suspend fun remove(fileId: String)

    @Query("DELETE FROM sync_file_cache WHERE fileId NOT IN (:keepFileIds)")
    suspend fun prune(keepFileIds: List<String>)

    @Query("DELETE FROM sync_file_cache")
    suspend fun clearAll()
}
