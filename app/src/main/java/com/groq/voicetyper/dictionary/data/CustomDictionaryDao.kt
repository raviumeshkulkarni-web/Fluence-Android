package com.groq.voicetyper.dictionary.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomDictionaryDao {

    @Query("SELECT * FROM custom_dictionary ORDER BY id DESC")
    fun getAll(): Flow<List<CustomDictionaryEntry>>

    @Query("SELECT * FROM custom_dictionary WHERE isEnabled = 1")
    fun getAllEnabledSync(): List<CustomDictionaryEntry>

    @Query("SELECT * FROM custom_dictionary WHERE isEnabled = 1")
    fun getAllEnabled(): Flow<List<CustomDictionaryEntry>>

    @Query("SELECT * FROM custom_dictionary WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CustomDictionaryEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CustomDictionaryEntry): Long

    @Update
    suspend fun update(entry: CustomDictionaryEntry)

    @Delete
    suspend fun delete(entry: CustomDictionaryEntry)

    @Query("DELETE FROM custom_dictionary WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM custom_dictionary")
    suspend fun deleteAll()
}
