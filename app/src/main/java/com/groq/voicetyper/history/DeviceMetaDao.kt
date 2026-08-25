package com.groq.voicetyper.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceMetaDao {
    @Query("SELECT value FROM device_meta WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT value FROM device_meta WHERE key = :key")
    fun observe(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: DeviceMetaEntry)
}
