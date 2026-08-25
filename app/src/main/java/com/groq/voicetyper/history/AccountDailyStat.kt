package com.groq.voicetyper.history

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "account_stats_daily",
    primaryKeys = ["accountKey", "day"]
)
data class AccountDailyStat(
    val accountKey: String,
    val day: String,
    val wordCount: Long = 0L,
    @ColumnInfo(defaultValue = "0") val count: Long = 0L,
    @ColumnInfo(defaultValue = "0") val chars: Long = 0L,
    val dictationMs: Long = 0L
)

@Dao
interface AccountDailyDao {
    @Query("SELECT * FROM account_stats_daily WHERE accountKey = :accountKey ORDER BY day ASC")
    fun getAll(accountKey: String): Flow<List<AccountDailyStat>>

    @Query("SELECT * FROM account_stats_daily WHERE accountKey = :accountKey ORDER BY day ASC")
    suspend fun getAllOnce(accountKey: String): List<AccountDailyStat>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<AccountDailyStat>)

    @Query("DELETE FROM account_stats_daily WHERE accountKey = :accountKey")
    suspend fun clearForAccount(accountKey: String)

    @Query("DELETE FROM account_stats_daily")
    suspend fun clearAll()
}
