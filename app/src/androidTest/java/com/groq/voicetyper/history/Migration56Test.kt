package com.groq.voicetyper.history

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hand-rolled 5 -> 6 migration test (mirrors Migration45Test). exportSchema=false
 * blocks MigrationTestHelper, so this builds a real v5 SupportSQLiteDatabase,
 * seeds rows, stamps PRAGMA user_version = 5, closes it, and reopens the real
 * FluenceDatabase (v6, all migrations registered). Room's runtime schema
 * validation must pass.
 */
@RunWith(AndroidJUnit4::class)
class Migration56Test {

    private lateinit var context: Context
    private val dbName = "migration-5-6-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        createV5Database().close()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun createV5Database(): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createV5Schema(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        return helper.writableDatabase
    }

    private fun createV5Schema(db: SupportSQLiteDatabase) {
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
        // v5 `custom_dictionary`: NO sync columns yet (they land in 5 -> 6).
        db.execSQL(
            "CREATE TABLE `custom_dictionary` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`spokenText` TEXT NOT NULL, " +
                "`replacementText` TEXT NOT NULL, " +
                "`isEnabled` INTEGER NOT NULL DEFAULT 1)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX `index_custom_dictionary_spokenText` ON `custom_dictionary` (`spokenText`)"
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
            "INSERT INTO `transcription_history` (`id`, `text`, `provider`, `model`, `language`, `durationMs`, `isAgentMode`, `timestamp`) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(1L, "Hello world", "groq", "whisper-large-v3", "en", 8400L, 0, 1713456000123L)
        )
        db.execSQL(
            "INSERT INTO `custom_dictionary` (`spokenText`, `replacementText`, `isEnabled`) VALUES (?, ?, ?)",
            arrayOf("fluence", "Fluence", 1)
        )
        db.execSQL(
            "INSERT INTO `custom_dictionary` (`spokenText`, `replacementText`, `isEnabled`) VALUES (?, ?, ?)",
            arrayOf("asap", "ASAP", 0)
        )
        db.execSQL(
            "INSERT INTO `suggestion_history` (`spokenText`, `correctedText`, `frequency`, `status`, `createdAt`, `lastSeenAt`) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf("helo", "hello", 1, "PENDING", 1000L, 2000L)
        )
        db.execSQL(
            "INSERT INTO `stats_daily` (`day`, `wordCount`, `dictationMs`) VALUES (?, ?, ?)",
            arrayOf("2024-04-18", 10L, 5000L)
        )
        db.execSQL("PRAGMA user_version = 5")
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
            FluenceDatabase.MIGRATION_8_9,
            FluenceDatabase.MIGRATION_9_10,
            FluenceDatabase.MIGRATION_10_11,
            FluenceDatabase.MIGRATION_11_12,
            FluenceDatabase.MIGRATION_12_13
        )
        .allowMainThreadQueries()
        .build()

    @Test
    fun migration_5_6_keeps_all_rows() = runBlocking {
        val db = openRoomDatabase()
        try {
            val dictionary = db.customDictionaryDao()
            val rows = dictionary.getAll().first()
            assertEquals(2, rows.size)
            val fluence = rows.first { it.spokenText == "fluence" }
            assertEquals("Fluence", fluence.replacementText)
            assertTrue(fluence.isEnabled)
            val asap = rows.first { it.spokenText == "asap" }
            assertEquals("ASAP", asap.replacementText)
            assertTrue(!asap.isEnabled)

            val history = db.transcriptionHistoryDao()
            assertEquals(1, history.getAll().first().size)

            val stats = db.statsDao()
            assertEquals(1, stats.getAll().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun migration_5_6_does_not_generate_sync_id() = runBlocking {
        val db = openRoomDatabase()
        try {
            val rows = db.customDictionaryDao().getAll().first()
            assertEquals(2, rows.size)
            rows.forEach { assertNull("syncId must stay null after migration", it.syncId) }
        } finally {
            db.close()
        }
    }

    @Test
    fun new_columns_null_for_existing_rows() = runBlocking {
        val db = openRoomDatabase()
        try {
            val rows = db.customDictionaryDao().getAll().first()
            assertEquals(2, rows.size)
            rows.forEach {
                assertNull(it.syncId)
                assertNull(it.createdAt)
                assertNull(it.deletedAt)
                assertNull(it.serverFileId)
                assertNull(it.syncAccount)
                assertNull(it.quarantineReason)
                assertEquals("local", it.syncState)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun unique_index_allows_multiple_null_sync_ids() = runBlocking {
        val db = openRoomDatabase()
        try {
            val dao = db.customDictionaryDao()
            dao.insert(
                CustomDictionaryEntry(spokenText = "gm", replacementText = "good morning")
            )
            dao.insert(
                CustomDictionaryEntry(spokenText = "gn", replacementText = "good night")
            )
            assertEquals(4, dao.getAll().first().size)
        } finally {
            db.close()
        }
    }

    @Test
    fun room_opens_cleanly_at_version_6() {
        openRoomDatabase().close()
    }
}