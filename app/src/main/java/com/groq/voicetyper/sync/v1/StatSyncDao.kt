package com.groq.voicetyper.sync.v1

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface StatSyncDao {
    @Query("SELECT * FROM stat_sync WHERE accountHash = :hash")
    suspend fun getByAccount(hash: String): List<StatSyncEntry>

    @Query("SELECT * FROM stat_sync WHERE accountHash = :hash")
    fun observeByAccount(hash: String): kotlinx.coroutines.flow.Flow<List<StatSyncEntry>>

    @Query("SELECT * FROM stat_sync WHERE accountHash IS NULL")
    suspend fun getUnstamped(): List<StatSyncEntry>
    @Query("SELECT * FROM stat_sync WHERE accountHash = :hash AND dirty = 1")
    suspend fun getDirtyByAccount(hash: String): List<StatSyncEntry>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: StatSyncEntry): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: StatSyncEntry): Long
    @Query("UPDATE stat_sync SET accountHash = :hash, dirty = 1 WHERE accountHash IS NULL")
    suspend fun stampUnstamped(hash: String): Int
    @Query("DELETE FROM stat_sync WHERE eventId = :eventId")
    suspend fun deleteByEventId(eventId: String)
    @Query("SELECT * FROM stat_sync WHERE eventId = :eventId LIMIT 1")
    suspend fun getByEventId(eventId: String): StatSyncEntry?
    @Query("SELECT * FROM stat_sync")
    suspend fun getAll(): List<StatSyncEntry>
    @Query("UPDATE stat_sync SET dirty = 0, everPushed = 1 WHERE accountHash = :hash")
    suspend fun clearDirtyByAccount(hash: String): Int

    @Query("UPDATE stat_sync SET dirty = 0, everPushed = 1 WHERE accountHash = :hash AND eventId IN (:ids)")
    suspend fun clearDirtyByEventIds(hash: String, ids: List<String>): Int
    @Query("SELECT MAX(updatedAt) FROM stat_sync WHERE accountHash = :hash")
    suspend fun maxUpdatedAtByAccount(hash: String): Long?
}
