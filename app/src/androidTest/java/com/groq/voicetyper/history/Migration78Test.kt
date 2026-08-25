package com.groq.voicetyper.history

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hand-rolled 7 -> 8 migration test (mirrors Migration45Test/Migration56Test).
 * exportSchema=false blocks MigrationTestHelper, so this builds a real v7
 * SupportSQLiteDatabase (all five tables), seeds rows, stamps PRAGMA
 * user_version = 7, closes it, and reopens the real FluenceDatabase (v8, all
 * migrations registered). Room's runtime schema validation must pass.
 */
@RunWith(AndroidJUnit4::class)
class Migration78Test {

    private lateinit var context: Context
    private val dbName = "migration-7-8-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        createV7Database().close()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun createV7Database(): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createV7Schema(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        return helper.writableDatabase
    }

    private fun createV7Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE `transcription_history` (" +
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
            "CREATE UNIQUE INDEX `index_transcription_history_syncId` ON `transcription_history` (`syncId`)"
        )
        db.execSQL(
            "CREATE TABLE `custom_dictionary` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`spokenText` TEXT NOT NULL, " +
                "`replacementText` TEXT NOT NULL, " +
                "`isEnabled` INTEGER NOT NULL DEFAULT 1, " +
                "`syncId` TEXT, " +
                "`createdAt` INTEGER, " +
                "`deletedAt` INTEGER, " +
                "`syncState` TEXT NOT NULL DEFAULT 'local', " +
                "`serverFileId` TEXT, " +
                "`syncAccount` TEXT, " +
                "`quarantineReason` TEXT)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX `index_custom_dictionary_spokenText` ON `custom_dictionary` (`spokenText`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX `index_custom_dictionary_syncId` ON `custom_dictionary` (`syncId`)"
        )
        db.execSQL(
            "CREATE TABLE `suggestion_history` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`spokenText` TEXT NOT NULL, " +
                "`correctedText` TEXT NOT NULL, " +
                "`frequency` INTEGER NOT NULL DEFAULT 1, " +
                "`status` TEXT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, " +
                "`lastSeenAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX `index_suggestion_history_spokenText_correctedText` ON `suggestion_history` (`spokenText`, `correctedText`)"
        )
        db.execSQL(
            "CREATE TABLE `stats_daily` (" +
                "`day` TEXT NOT NULL, " +
                "`wordCount` INTEGER NOT NULL, " +
                "`dictationMs` INTEGER NOT NULL, " +
                "PRIMARY KEY(`day`))"
        )
        db.execSQL(
            "CREATE TABLE `sync_file_cache` (" +
                "`fileId` TEXT NOT NULL, " +
                "`md5` TEXT, " +
                "`recordJson` TEXT NOT NULL, " +
                "PRIMARY KEY(`fileId`))"
        )

        db.execSQL(
            "INSERT INTO `transcription_history` (`id`, `text`, `provider`, `model`, `language`, `durationMs`, `isAgentMode`, `timestamp`) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(1L, "Hello world", "groq", "whisper-large-v3", "en", 8400L, 0, 1713456000123L)
        )
        db.execSQL(
            "INSERT INTO `custom_dictionary` (`spokenText`, `replacementText`, `isEnabled`) VALUES (?, ?, ?)",
            arrayOf("fluence", "Fluence", 1)
        )
        db.execSQL(
            "INSERT INTO `suggestion_history` (`spokenText`, `correctedText`, `frequency`, `status`, `createdAt`, `lastSeenAt`) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf("helo", "hello", 1, "PENDING", 1000L, 2000L)
        )
        db.execSQL(
            "INSERT INTO `stats_daily` (`day`, `wordCount`, `dictationMs`) VALUES (?, ?, ?)",
            arrayOf("2024-04-18", 10L, 5000L)
        )
        db.execSQL(
            "INSERT INTO `sync_file_cache` (`fileId`, `md5`, `recordJson`) VALUES (?, ?, ?)",
            arrayOf("file-1", "abc123", "{\"v\":1}")
        )
        db.execSQL("PRAGMA user_version = 7")
    }

    private fun openRoomDatabase(): FluenceDatabase = Room.databaseBuilder(
        context,
        FluenceDatabase::class.java,
        dbName
    )
        .addMigrations(
            FluenceDatabase.MIGRATION_1_2,
            FluenceDatabase.MIGRATION_2_3,
            FluenceDatabase.MIGRATION_3_4,
            FluenceDatabase.MIGRATION_4_5,
            FluenceDatabase.MIGRATION_5_6,
            FluenceDatabase.MIGRATION_6_7,
            FluenceDatabase.MIGRATION_7_8,
            FluenceDatabase.MIGRATION_8_9
        )
        .allowMainThreadQueries()
        .build()

    @Test
    fun migration_7_8_keeps_all_rows_and_defaults_new_dimensions() = runBlocking {
        val db = openRoomDatabase()
        try {
            val history = db.transcriptionHistoryDao()
            val rows = history.getAll().first()
            assertEquals(1, rows.size)
            assertEquals("Hello world", rows[0].text)
            assertNull(rows[0].syncId)

            val dictionary = db.customDictionaryDao()
            assertEquals(1, dictionary.getAll().first().size)

            val suggestions = db.suggestionDao()
            assertEquals("hello", suggestions.findByPair("helo", "hello")?.correctedText)

            val stats = db.statsDao()
            val statsRows = stats.getAll().first()
            assertEquals(1, statsRows.size)
            assertEquals("2024-04-18", statsRows[0].day)
            assertEquals(10L, statsRows[0].wordCount)
            assertEquals(5000L, statsRows[0].dictationMs)
            assertEquals(0L, statsRows[0].count)
            assertEquals(0L, statsRows[0].chars)

            val cache = db.syncFileCacheDao()
            assertEquals(1, cache.all().size)
            assertEquals("file-1", cache.all().first().fileId)
            assertEquals("abc123", cache.all().first().md5)
        } finally {
            db.close()
        }
    }

    @Test
    fun migration_7_8_creates_increment_and_meta_tables() = runBlocking {
        val db = openRoomDatabase()
        try {
            val increments = db.dictationIncrementDao()
            assertFalse(increments.exists("11111111-1111-4111-8111-111111111111"))
            increments.insertIgnore(
                DictationIncrement(
                    dictationId = "11111111-1111-4111-8111-111111111111",
                    day = "2024-04-18",
                    words = 10L,
                    count = 1L,
                    chars = 44L,
                    ms = 8400L
                )
            )
            assertTrue(increments.exists("11111111-1111-4111-8111-111111111111"))

            val meta = db.deviceMetaDao()
            assertNull(meta.get("device_id"))
            meta.put(DeviceMetaEntry("device_id", "test-device"))
            assertEquals("test-device", meta.get("device_id"))
        } finally {
            db.close()
        }
    }

    @Test
    fun room_opens_cleanly_at_version_8() {
        openRoomDatabase().close()
    }
}
