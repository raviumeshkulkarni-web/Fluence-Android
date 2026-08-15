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

    @Query("SELECT * FROM custom_dictionary WHERE spokenText = :spokenText LIMIT 1")
    suspend fun getBySpokenText(spokenText: String): CustomDictionaryEntry?

    // IGNORE (not the default ABORT): a concurrent save of the same spokenText
    // (e.g. double-tap Add/Save) no longer throws SQLiteConstraintException;
    // the loser's insert is ignored and saveEntry re-queries the winning row.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: CustomDictionaryEntry): Long

    @Update
    suspend fun update(entry: CustomDictionaryEntry)

    @Delete
    suspend fun delete(entry: CustomDictionaryEntry)

    @Query("DELETE FROM custom_dictionary WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM custom_dictionary")
    suspend fun deleteAll()

    // ── sync seams (spec §30.4, Phase 8) ──────────────────────────────────

    @Query("SELECT * FROM custom_dictionary")
    suspend fun getAllRows(): List<CustomDictionaryEntry>

    @Query("SELECT * FROM custom_dictionary WHERE syncAccount IS NULL")
    suspend fun getSyncRowsUnstamped(): List<CustomDictionaryEntry>

    @Query("SELECT * FROM custom_dictionary WHERE syncAccount IS NULL OR syncAccount = :stamp")
    suspend fun getSyncRows(stamp: String): List<CustomDictionaryEntry>

    @Query("SELECT * FROM custom_dictionary WHERE syncId = :syncId LIMIT 1")
    suspend fun getBySyncId(syncId: String): CustomDictionaryEntry?

    @Query("SELECT * FROM custom_dictionary WHERE syncId IS NULL")
    suspend fun getRowsWithoutSyncId(): List<CustomDictionaryEntry>

    @Query("UPDATE custom_dictionary SET syncId = :syncId, createdAt = :createdAt WHERE id = :id AND syncId IS NULL")
    suspend fun backfillSyncMeta(id: Long, syncId: String, createdAt: Long): Int

    @Query("UPDATE custom_dictionary SET deletedAt = :deletedAt, syncState = 'dirty' WHERE syncId = :syncId AND deletedAt IS NULL")
    suspend fun markTombstonedBySyncId(syncId: String, deletedAt: Long): Int

    @Query("UPDATE custom_dictionary SET serverFileId = :serverFileId, syncState = 'clean' WHERE syncId = :syncId")
    suspend fun setServerFileIdAndStateBySyncId(syncId: String, serverFileId: String): Int

    @Query("UPDATE custom_dictionary SET syncState = :syncState WHERE syncId = :syncId")
    suspend fun updateSyncStateBySyncId(syncId: String, syncState: String): Int

    @Query("UPDATE custom_dictionary SET quarantineReason = :reason, syncState = 'quarantined' WHERE syncId = :syncId")
    suspend fun quarantineBySyncId(syncId: String, reason: String): Int

    @Query("UPDATE custom_dictionary SET quarantineReason = NULL WHERE syncId = :syncId")
    suspend fun clearQuarantineBySyncId(syncId: String): Int

    @Query("DELETE FROM custom_dictionary WHERE syncId = :syncId")
    suspend fun hardDeleteBySyncId(syncId: String): Int
}
