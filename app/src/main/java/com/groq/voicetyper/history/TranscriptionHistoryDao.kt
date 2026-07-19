package com.groq.voicetyper.history

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptionHistoryDao {
    @Query("SELECT * FROM transcription_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<TranscriptionEntry>>

    @Query("SELECT * FROM transcription_history WHERE text LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun search(query: String): Flow<List<TranscriptionEntry>>

    @Insert
    suspend fun insert(entry: TranscriptionEntry)

    @Delete
    suspend fun delete(entry: TranscriptionEntry)

    @Query("DELETE FROM transcription_history")
    suspend fun deleteAll()

    @Query("DELETE FROM transcription_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM transcription_history WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("SELECT COUNT(*) FROM transcription_history")
    fun getCount(): Flow<Int>
}