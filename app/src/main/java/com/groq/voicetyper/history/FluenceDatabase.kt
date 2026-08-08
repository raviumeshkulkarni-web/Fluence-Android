package com.groq.voicetyper.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.groq.voicetyper.autolearn.data.SuggestionDao
import com.groq.voicetyper.autolearn.data.SuggestionEntry
import com.groq.voicetyper.dictionary.data.CustomDictionaryDao
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry

@Database(
    entities = [TranscriptionEntry::class, CustomDictionaryEntry::class, SuggestionEntry::class, DailyStat::class],
    version = 4,
    exportSchema = false
)
abstract class FluenceDatabase : RoomDatabase() {
    abstract fun transcriptionHistoryDao(): TranscriptionHistoryDao
    abstract fun customDictionaryDao(): CustomDictionaryDao
    abstract fun suggestionDao(): SuggestionDao
    abstract fun statsDao(): StatsDao

    companion object {
        @Volatile
        private var INSTANCE: FluenceDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `custom_dictionary` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`spokenText` TEXT NOT NULL, " +
                    "`replacementText` TEXT NOT NULL, " +
                    "`isEnabled` INTEGER NOT NULL DEFAULT 1)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_dictionary_spokenText` ON `custom_dictionary` (`spokenText`)"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `suggestion_history` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`spokenText` TEXT NOT NULL, " +
                    "`correctedText` TEXT NOT NULL, " +
                    "`frequency` INTEGER NOT NULL DEFAULT 1, " +
                    "`status` TEXT NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL, " +
                    "`lastSeenAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_suggestion_history_spokenText_correctedText` ON `suggestion_history` (`spokenText`, `correctedText`)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stats_daily` (" +
                    "`day` TEXT NOT NULL, " +
                    "`wordCount` INTEGER NOT NULL, " +
                    "`dictationMs` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`day`))"
                )
                val perDay = HashMap<String, LongArray>()
                db.query("SELECT `text`, `durationMs`, `timestamp` FROM `transcription_history`").use { cursor ->
                    while (cursor.moveToNext()) {
                        val text = cursor.getString(0) ?: ""
                        val durationMs = cursor.getLong(1)
                        val timestamp = cursor.getLong(2)
                        val words = StatsCalculator.wordCountOf(text).toLong()
                        val ms = StatsCalculator.effectiveDurationMs(text, durationMs)
                        val day = StatsCalculator.localDateOf(timestamp)
                        val agg = perDay.getOrPut(day) { longArrayOf(0L, 0L) }
                        agg[0] += words
                        agg[1] += ms
                    }
                }
                perDay.forEach { (day, agg) ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO `stats_daily` (`day`, `wordCount`, `dictationMs`) VALUES (?, ?, ?)",
                        arrayOf(day, agg[0], agg[1])
                    )
                }
            }
        }

        fun getInstance(context: Context): FluenceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FluenceDatabase::class.java,
                    "fluence_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .allowMainThreadQueries()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}