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
import com.groq.voicetyper.sync.cache.SyncFileCache
import com.groq.voicetyper.sync.cache.SyncFileCacheDao
import com.groq.voicetyper.sync.v1.StatSyncDao
import com.groq.voicetyper.sync.v1.StatSyncEntry
import com.groq.voicetyper.sync.v1.SyncMetadata
import com.groq.voicetyper.sync.v1.SyncMetadataDao

@Database(
    entities = [TranscriptionEntry::class, CustomDictionaryEntry::class, SuggestionEntry::class, DailyStat::class, SyncFileCache::class, DictationIncrement::class, DeviceMetaEntry::class, AccountDailyStat::class, StatSyncEntry::class, SyncMetadata::class],
    version = 13,
    exportSchema = false
)
abstract class FluenceDatabase : RoomDatabase() {
    abstract fun transcriptionHistoryDao(): TranscriptionHistoryDao
    abstract fun customDictionaryDao(): CustomDictionaryDao
    abstract fun suggestionDao(): SuggestionDao
    abstract fun statsDao(): StatsDao
    abstract fun syncFileCacheDao(): SyncFileCacheDao
    abstract fun dictationIncrementDao(): DictationIncrementDao
    abstract fun deviceMetaDao(): DeviceMetaDao
    abstract fun accountDailyDao(): AccountDailyDao
    abstract fun statSyncDao(): StatSyncDao
    abstract fun syncMetadataDao(): SyncMetadataDao

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
                        val day = StatsCalculator.utcDateOf(timestamp)
                        val agg = perDay.getOrPut(day) { longArrayOf(0L, 0L) }
                        agg[0] += words
                        agg[1] += durationMs
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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `transcription_history_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`text` TEXT NOT NULL, " +
                    "`provider` TEXT NOT NULL, " +
                    "`model` TEXT, " +
                    "`language` TEXT, " +
                    "`durationMs` INTEGER NOT NULL, " +
                    "`isAgentMode` INTEGER NOT NULL, " +
                    "`timestamp` INTEGER NOT NULL, " +
                    "`syncId` TEXT, " +
                    "`deletedAt` INTEGER, " +
                    "`syncState` TEXT NOT NULL DEFAULT 'local', " +
                    "`serverFileId` TEXT, " +
                    "`syncAccount` TEXT, " +
                    "`quarantineReason` TEXT)"
                )
                db.execSQL(
                    "INSERT INTO `transcription_history_new` (`id`, `text`, `provider`, `model`, `language`, `durationMs`, `isAgentMode`, `timestamp`, `syncId`, `deletedAt`, `syncState`, `serverFileId`, `syncAccount`, `quarantineReason`) " +
                    "SELECT `id`, `text`, `provider`, `model`, `language`, `durationMs`, `isAgentMode`, `timestamp`, NULL, NULL, 'local', NULL, NULL, NULL FROM `transcription_history`"
                )
                db.execSQL("DROP TABLE `transcription_history`")
                db.execSQL("ALTER TABLE `transcription_history_new` RENAME TO `transcription_history`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_transcription_history_syncId` ON `transcription_history` (`syncId`)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // §30.4: `custom_dictionary` gains the sync columns (non-destructive,
                // mirrors §5). Existing rows keep their values; syncId stays NULL
                // (assigned lazily on first sync mapping), syncState defaults 'local'.
                db.execSQL("ALTER TABLE `custom_dictionary` ADD COLUMN `syncId` TEXT")
                db.execSQL("ALTER TABLE `custom_dictionary` ADD COLUMN `createdAt` INTEGER")
                db.execSQL("ALTER TABLE `custom_dictionary` ADD COLUMN `deletedAt` INTEGER")
                db.execSQL("ALTER TABLE `custom_dictionary` ADD COLUMN `syncState` TEXT NOT NULL DEFAULT 'local'")
                db.execSQL("ALTER TABLE `custom_dictionary` ADD COLUMN `serverFileId` TEXT")
                db.execSQL("ALTER TABLE `custom_dictionary` ADD COLUMN `syncAccount` TEXT")
                db.execSQL("ALTER TABLE `custom_dictionary` ADD COLUMN `quarantineReason` TEXT")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_dictionary_syncId` ON `custom_dictionary` (`syncId`)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // §31: change-detection cache for incremental sync. Non-
                // destructive; the table is empty on first upgrade and only
                // populated by the engine's next pass.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_file_cache` (" +
                    "`fileId` TEXT NOT NULL, " +
                    "`md5` TEXT, " +
                    "`recordJson` TEXT NOT NULL, " +
                    "PRIMARY KEY(`fileId`))"
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Part C: `stats_daily` becomes monotonic and gains `count` /
                // `chars` dimensions. Existing rows keep their values (never
                // recomputed); the new columns default to 0 and only future
                // dictations increment them.
                db.execSQL("ALTER TABLE `stats_daily` ADD COLUMN `count` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `stats_daily` ADD COLUMN `chars` INTEGER NOT NULL DEFAULT 0")
                // Part C/V4: per-dictation counter rows keyed by the dictation
                // UUID so a dictation's counters apply to `stats_daily` exactly
                // once even if the history row is re-inserted or re-imported.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `dictation_increments` (" +
                    "`dictationId` TEXT NOT NULL, " +
                    "`day` TEXT NOT NULL, " +
                    "`words` INTEGER NOT NULL, " +
                    "`count` INTEGER NOT NULL, " +
                    "`chars` INTEGER NOT NULL, " +
                    "`ms` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`dictationId`))"
                )
                // Part C: device identity and sync state stored in the excluded
                // `fluence_database` (never in SharedPreferences, which would be
                // restored by auto-backup).
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `device_meta` (" +
                    "`key` TEXT NOT NULL, " +
                    "`value` TEXT NOT NULL, " +
                    "PRIMARY KEY(`key`))"
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `account_stats_daily` (" +
                    "`accountKey` TEXT NOT NULL, " +
                    "`day` TEXT NOT NULL, " +
                    "`wordCount` INTEGER NOT NULL DEFAULT 0, " +
                    "`count` INTEGER NOT NULL DEFAULT 0, " +
                    "`chars` INTEGER NOT NULL DEFAULT 0, " +
                    "`dictationMs` INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY(`accountKey`, `day`))"
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Frozen v1.2: account-level union-dedup stats events and
                // per-account sync metadata. Non-destructive; both tables are
                // empty on first upgrade and populated by the commit path /
                // one-time backfill.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stat_sync` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`eventId` TEXT NOT NULL, " +
                    "`day` TEXT NOT NULL, " +
                    "`wordCount` INTEGER NOT NULL, " +
                    "`durationMs` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, " +
                    "`deletedAt` INTEGER, " +
                    "`deviceId` TEXT, " +
                    "`accountHash` TEXT, " +
                    "`dirty` INTEGER NOT NULL, " +
                    "`everPushed` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_stat_sync_eventId` ON `stat_sync` (`eventId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_metadata` (" +
                    "`accountHash` TEXT NOT NULL, " +
                    "`deviceId` TEXT NOT NULL, " +
                    "`maxSeen` INTEGER NOT NULL, " +
                    "`backfillDone` INTEGER NOT NULL, " +
                    "`lastRevDictionary` TEXT, " +
                    "`lastRevSnippets` TEXT, " +
                    "`lastRevStats` TEXT, " +
                    "`lastRevSettings` TEXT, " +
                    "PRIMARY KEY(`accountHash`))"
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Frozen v1.2: LWW metadata columns for custom_dictionary.
                // Non-destructive; existing rows keep NULL/defaults and are
                // stamped lazily by the sync engine's enrollment step.
                db.execSQL("ALTER TABLE `custom_dictionary` ADD COLUMN `updatedAt` INTEGER")
                db.execSQL("ALTER TABLE `custom_dictionary` ADD COLUMN `deviceId` TEXT")
                db.execSQL("ALTER TABLE `custom_dictionary` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `custom_dictionary` ADD COLUMN `everPushed` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stat_sync ADD COLUMN chars INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE stat_sync ADD COLUMN timestampMs INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Foreign-account rows remain local, so dictionary identity
                // must not be globally unique across accounts.
                db.execSQL("DROP INDEX IF EXISTS `index_custom_dictionary_spokenText`")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_custom_dictionary_spokenText_syncAccount` " +
                        "ON `custom_dictionary` (`spokenText`, `syncAccount`)"
                )
            }
        }

        fun getInstance(context: Context): FluenceDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    FluenceDatabase::class.java,
                    "fluence_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                    .allowMainThreadQueries()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
