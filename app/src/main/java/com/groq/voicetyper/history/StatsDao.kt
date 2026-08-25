package com.groq.voicetyper.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsDao {
    @Query("UPDATE stats_daily SET wordCount = wordCount + :words, count = count + :count, chars = chars + :chars, dictationMs = dictationMs + :ms WHERE day = :day")
    suspend fun increment(day: String, words: Long, count: Long, chars: Long, ms: Long): Int

    @Insert
    suspend fun insert(stat: DailyStat)

    @Insert
    suspend fun insertAll(stats: List<DailyStat>)

    @Query("DELETE FROM stats_daily")
    suspend fun clear()

    @Query("SELECT * FROM stats_daily")
    fun getAll(): Flow<List<DailyStat>>

    @Query("SELECT * FROM stats_daily")
    suspend fun getAllOnce(): List<DailyStat>
}
