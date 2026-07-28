package com.groq.voicetyper.autolearn.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SuggestionDao {

    @Query("SELECT * FROM suggestion_history WHERE status = 'PENDING' ORDER BY frequency DESC, lastSeenAt DESC")
    fun getPendingSuggestions(): Flow<List<SuggestionEntry>>

    @Query("SELECT * FROM suggestion_history WHERE spokenText = :spoken AND correctedText = :corrected LIMIT 1")
    suspend fun findByPair(spoken: String, corrected: String): SuggestionEntry?

    @Query("INSERT INTO suggestion_history (spokenText, correctedText, frequency, status, createdAt, lastSeenAt) VALUES (:spoken, :corrected, 1, 'PENDING', :now, :now) ON CONFLICT(spokenText, correctedText) DO UPDATE SET frequency = suggestion_history.frequency + 1, lastSeenAt = :now, status = 'PENDING'")
    suspend fun upsertCandidate(spoken: String, corrected: String, now: Long = System.currentTimeMillis())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SuggestionEntry): Long

    @Update
    suspend fun update(entry: SuggestionEntry)

    @Delete
    suspend fun delete(entry: SuggestionEntry)

    @Query("DELETE FROM suggestion_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM suggestion_history")
    suspend fun deleteAll()
}
