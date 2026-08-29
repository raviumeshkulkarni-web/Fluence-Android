package com.groq.voicetyper.history

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.groq.voicetyper.autolearn.data.SuggestionDao
import com.groq.voicetyper.autolearn.data.SuggestionEntry
import com.groq.voicetyper.autolearn.data.SuggestionStatus
import com.groq.voicetyper.dictionary.data.CustomDictionaryDao
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hand-rolled 4 -> 5 migration test. exportSchema=false blocks MigrationTestHelper,
 * so this builds a real v4 SupportSQLiteDatabase (all four tables), seeds rows,
 * stamps PRAGMA user_version = 4, closes it, and reopens the real FluenceDatabase
 * (v5, all migrations registered). Room's runtime schema validation must pass.
 */
@RunWith(AndroidJUnit4::class)
class Migration45Test {

    private lateinit var context: Context
    private val dbName = "migration-4-5-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
        createV4Database().close()
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun createV4Database(): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createV4Schema(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        return helper.writableDatabase
    }

    private fun createV4Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE `transcription_history` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`provider` TEXT NOT NULL, " +
                "`model` TEXT NOT NULL, " +
                "`language` TEXT NOT NULL, " +
                "`durationMs` INTEGER NOT NULL, " +
                "`isAgentMode` INTEGER NOT NULL, " +
                "`timestamp` INTEGER NOT NULL)"
        )
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
            "INSERT INTO `transcription_history` (`id`, `text`, `provider`, `model`, `language`, `durationMs`, `isAgentMode`, `timestamp`) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(2L, "Agent note", "mistral", "voxtral-mini-latest", "en", 5000L, 1, 1713456001123L)
        )
        db.execSQL(
            "INSERT INTO `transcription_history` (`id`, `text`, `provider`, `model`, `language`, `durationMs`, `isAgentMode`, `timestamp`) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(3L, "Third entry", "offline", "moonshine-base-v1", "hi", 1234L, 0, 1713456002123L)
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
        db.execSQL("PRAGMA user_version = 4")
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
    fun migration_4_5_keeps_all_rows() = runBlocking {
        val db = openRoomDatabase()
        try {
            val history = db.transcriptionHistoryDao()
            val dictionary = db.customDictionaryDao()
            val suggestions = db.suggestionDao()
            val stats = db.statsDao()

            val rows = history.getAll().first()
            assertEquals(3, rows.size)
            assertEquals("Third entry", rows[0].text)
            assertEquals("moonshine-base-v1", rows[0].model)
            assertEquals("hi", rows[0].language)
            assertEquals("Agent note", rows[1].text)
            assertTrue(rows[1].isAgentMode)
            assertEquals("Hello world", rows[2].text)
            assertEquals("groq", rows[2].provider)
            assertEquals("whisper-large-v3", rows[2].model)
            assertEquals("en", rows[2].language)
            assertEquals(8400L, rows[2].durationMs)
            assertFalse(rows[2].isAgentMode)
            assertEquals(1713456000123L, rows[2].timestamp)

            val dictionaryRows = dictionary.getAllEnabledSync()
            assertEquals(1, dictionaryRows.size)
            assertEquals("fluence", dictionaryRows[0].spokenText)
            assertEquals("Fluence", dictionaryRows[0].replacementText)

            val suggestion = suggestions.findByPair("helo", "hello")
            assertEquals("hello", suggestion?.correctedText)
            assertEquals(SuggestionStatus.PENDING, suggestion?.status)

            val statsRows = stats.getAll().first()
            assertEquals(1, statsRows.size)
            assertEquals("2024-04-18", statsRows[0].day)
            assertEquals(10L, statsRows[0].wordCount)
            assertEquals(5000L, statsRows[0].dictationMs)
        } finally {
            db.close()
        }
    }

    @Test
    fun migration_4_5_does_not_generate_sync_id() = runBlocking {
        val db = openRoomDatabase()
        try {
            val rows = db.transcriptionHistoryDao().getAll().first()
            assertEquals(3, rows.size)
            rows.forEach { assertNull("syncId must stay null after migration", it.syncId) }
        } finally {
            db.close()
        }
    }

    @Test
    fun new_columns_null_for_existing_rows() = runBlocking {
        val db = openRoomDatabase()
        try {
            val rows = db.transcriptionHistoryDao().getAll().first()
            assertEquals(3, rows.size)
            rows.forEach {
                assertNull(it.syncId)
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
    fun model_language_now_nullable() = runBlocking {
        val db = openRoomDatabase()
        try {
            val dao = db.transcriptionHistoryDao()
            dao.insert(
                TranscriptionEntry(
                    text = "no model no language",
                    provider = "groq",
                    model = null,
                    language = null,
                    durationMs = 100L,
                    isAgentMode = false,
                    timestamp = 1713456003123L
                )
            )
            val rows = dao.getAll().first()
            assertEquals(4, rows.size)
            val inserted = rows.first { it.text == "no model no language" }
            assertNull(inserted.model)
            assertNull(inserted.language)
        } finally {
            db.close()
        }
    }

    @Test
    fun unique_index_allows_multiple_null_sync_ids() = runBlocking {
        val db = openRoomDatabase()
        try {
            val dao = db.transcriptionHistoryDao()
            dao.insert(
                TranscriptionEntry(
                    text = "null sync one", provider = "groq", model = null, language = null,
                    durationMs = 1L, isAgentMode = false, timestamp = 1L, syncId = null
                )
            )
            dao.insert(
                TranscriptionEntry(
                    text = "null sync two", provider = "groq", model = null, language = null,
                    durationMs = 2L, isAgentMode = false, timestamp = 2L, syncId = null
                )
            )
            assertEquals(5, dao.getAll().first().size)

            dao.insert(
                TranscriptionEntry(
                    text = "uuid one", provider = "groq", model = null, language = null,
                    durationMs = 3L, isAgentMode = false, timestamp = 3L,
                    syncId = "11111111-1111-4111-8111-111111111111"
                )
            )
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                runBlocking {
                    dao.insert(
                        TranscriptionEntry(
                            text = "uuid duplicate", provider = "groq", model = null, language = null,
                            durationMs = 4L, isAgentMode = false, timestamp = 4L,
                            syncId = "11111111-1111-4111-8111-111111111111"
                        )
                    )
                }
            }
            Unit
        } finally {
            db.close()
        }
    }

    @Test
    fun room_opens_cleanly_at_version_5() {
        openRoomDatabase().close()
    }
}
