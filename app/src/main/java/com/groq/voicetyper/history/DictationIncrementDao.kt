package com.groq.voicetyper.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DictationIncrementDao {
    @Query("SELECT EXISTS(SELECT 1 FROM dictation_increments WHERE dictationId = :dictationId)")
    suspend fun exists(dictationId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(increment: DictationIncrement): Long
}
