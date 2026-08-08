package com.groq.voicetyper.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsDao {
    @Query("UPDATE stats_daily SET wordCount = wordCount + :words, dictationMs = dictationMs + :ms WHERE day = :day")
    suspend fun increment(day: String, words: Long, ms: Long): Int

    @Insert
    suspend fun insert(stat: DailyStat)

    @Query("SELECT * FROM stats_daily")
    fun getAll(): Flow<List<DailyStat>>
}
