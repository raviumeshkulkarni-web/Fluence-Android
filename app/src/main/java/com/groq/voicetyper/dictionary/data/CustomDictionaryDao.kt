package com.groq.voicetyper.dictionary.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomDictionaryDao {

    @Query("SELECT * FROM custom_dictionary WHERE deletedAt IS NULL ORDER BY id DESC")
    fun getAll(): Flow<List<CustomDictionaryEntry>>

    @Query("SELECT * FROM custom_dictionary WHERE isEnabled = 1 AND deletedAt IS NULL")
    fun getAllEnabledSync(): List<CustomDictionaryEntry>

    @Query("SELECT * FROM custom_dictionary WHERE isEnabled = 1 AND deletedAt IS NULL")
    fun getAllEnabled(): Flow<List<CustomDictionaryEntry>>

    @Query("SELECT * FROM custom_dictionary WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CustomDictionaryEntry?

    @Query("SELECT * FROM custom_dictionary WHERE spokenText = :spokenText LIMIT 1")
    suspend fun getBySpokenText(spokenText: String): CustomDictionaryEntry?

    // Canonical dictionary identity (spec §30.4): spoken is matched after trim +
    // case-folding, mirroring the case-insensitive application regex `(?i)\b…\b`
    // and the Windows `spoken_collides` rule. Used wherever a collision against a
    // *live* row must be detected so duplicate-identical rows absorb (§10) and
    // genuine conflicts latch instead of silently diverging between platforms.
    @Query("SELECT * FROM custom_dictionary WHERE deletedAt IS NULL AND LOWER(spokenText) = LOWER(:spokenText) LIMIT 1")
    suspend fun getBySpokenTextIgnoreCase(spokenText: String): CustomDictionaryEntry?

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

    // ── Frozen v1.2 store queries ────────────────────────────────────────

    @Query("SELECT * FROM custom_dictionary WHERE syncAccount = :hash")
    suspend fun getAllByAccount(hash: String): List<CustomDictionaryEntry>

    @Query("UPDATE custom_dictionary SET syncAccount = :hash, dirty = 1, everPushed = 0 WHERE syncAccount IS NULL")
    suspend fun stampAllUnstamped(hash: String): Int

    @Query("SELECT * FROM custom_dictionary WHERE syncAccount = :hash AND dirty = 1")
    suspend fun getDirtyByAccount(hash: String): List<CustomDictionaryEntry>

    /** Business-key lookup; the key is always lower(trim(spokenText)). */
    @Query("SELECT * FROM custom_dictionary WHERE lower(trim(spokenText)) = :businessKey AND syncAccount = :hash AND deletedAt IS NULL LIMIT 1")
    suspend fun getByBusinessKey(businessKey: String, hash: String): CustomDictionaryEntry?

    @Query("UPDATE custom_dictionary SET dirty = 0, everPushed = 1 WHERE syncAccount = :hash")
    suspend fun clearDirtyByAccount(hash: String): Int

    @Query("UPDATE custom_dictionary SET dirty = 0, everPushed = 1 WHERE syncAccount = :hash AND syncId IN (:ids)")
    suspend fun clearDirtyBySyncIds(hash: String, ids: List<String>): Int
}
