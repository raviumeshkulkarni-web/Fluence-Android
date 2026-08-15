package com.groq.voicetyper.history

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionHistoryDao {
    @Query("SELECT * FROM transcription_history WHERE deletedAt IS NULL ORDER BY timestamp DESC")
    fun getAll(): Flow<List<TranscriptionEntry>>

    @Query("SELECT * FROM transcription_history WHERE id = :id AND deletedAt IS NULL")
    fun getById(id: Long): Flow<TranscriptionEntry?>

    @Query("SELECT * FROM transcription_history WHERE text LIKE '%' || :query || '%' AND deletedAt IS NULL ORDER BY timestamp DESC")
    fun search(query: String): Flow<List<TranscriptionEntry>>

    @Insert
    suspend fun insert(entry: TranscriptionEntry)

    @Update
    suspend fun update(entry: TranscriptionEntry): Int

    @Delete
    suspend fun delete(entry: TranscriptionEntry)

    @Query("DELETE FROM transcription_history")
    suspend fun deleteAll()

    @Query("DELETE FROM transcription_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM transcription_history WHERE deletedAt IS NULL")
    fun getCount(): Flow<Int>

    @Query("SELECT * FROM transcription_history WHERE id = :id")
    suspend fun getRowById(id: Long): TranscriptionEntry?

    @Query("SELECT * FROM transcription_history WHERE id IN (:ids)")
    suspend fun getRowsByIds(ids: List<Long>): List<TranscriptionEntry>

    @Query("SELECT * FROM transcription_history")
    suspend fun getAllRows(): List<TranscriptionEntry>

    @Query("SELECT * FROM transcription_history WHERE deletedAt IS NULL")
    suspend fun getAllLiveRows(): List<TranscriptionEntry>

    @Query("SELECT * FROM transcription_history WHERE syncAccount IS NULL OR syncAccount = :stamp")
    suspend fun getSyncRows(stamp: String): List<TranscriptionEntry>

    @Query("SELECT * FROM transcription_history WHERE syncAccount IS NULL")
    suspend fun getSyncRowsUnstamped(): List<TranscriptionEntry>

    @Query("SELECT * FROM transcription_history WHERE syncId = :syncId")
    suspend fun getBySyncId(syncId: String): TranscriptionEntry?

    @Query("UPDATE transcription_history SET deletedAt = :deletedAt, syncState = 'dirty' WHERE id = :id AND deletedAt IS NULL")
    suspend fun markTombstonedById(id: Long, deletedAt: Long): Int

    @Query("UPDATE transcription_history SET deletedAt = :deletedAt, syncState = 'dirty' WHERE syncId = :syncId AND deletedAt IS NULL")
    suspend fun markTombstonedBySyncId(syncId: String, deletedAt: Long): Int

    @Query("UPDATE transcription_history SET quarantineReason = :reason, syncState = 'quarantined' WHERE syncId = :syncId")
    suspend fun quarantineBySyncId(syncId: String, reason: String): Int

    @Query("UPDATE transcription_history SET quarantineReason = NULL WHERE syncId = :syncId")
    suspend fun clearQuarantineBySyncId(syncId: String): Int

    @Query("UPDATE transcription_history SET serverFileId = :serverFileId, syncState = 'clean' WHERE syncId = :syncId")
    suspend fun setServerFileIdAndStateBySyncId(syncId: String, serverFileId: String): Int

    @Query("DELETE FROM transcription_history WHERE syncId = :syncId")
    suspend fun hardDeleteBySyncId(syncId: String): Int

    @Query("UPDATE transcription_history SET syncState = :syncState WHERE syncId = :syncId")
    suspend fun updateSyncStateBySyncId(syncId: String, syncState: String): Int

    @Query("SELECT * FROM transcription_history WHERE syncId IS NULL")
    suspend fun getRowsWithoutSyncId(): List<TranscriptionEntry>

    @Query("UPDATE transcription_history SET syncId = :syncId WHERE id = :id AND syncId IS NULL")
    suspend fun assignSyncId(id: Long, syncId: String): Int
}