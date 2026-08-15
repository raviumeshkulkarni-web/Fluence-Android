package com.groq.voicetyper.sync.engine

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.history.FluenceDatabase
import com.groq.voicetyper.sync.wire.RecordType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device Room tests for the dictionary LocalStore (spec §30.4): absorb on
 * duplicate-identical content (§10), latch with `collision` otherwise, and
 * first-mapping backfill. Runs against a real in-memory Room database.
 */
@RunWith(AndroidJUnit4::class)
class DictionaryLocalStoreTest {

    private lateinit var context: Context
    private lateinit var db: FluenceDatabase
    private lateinit var store: DictionaryLocalStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, FluenceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = DictionaryLocalStore(db, db.customDictionaryDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun allRows(): List<CustomDictionaryEntry> = runBlocking {
        db.customDictionaryDao().getAllRows()
    }

    private fun seedUserRow(spoken: String, corrected: String) = runBlocking {
        db.customDictionaryDao().insert(
            CustomDictionaryEntry(spokenText = spoken, replacementText = corrected, isEnabled = true)
        )
    }

    private fun row(uuid: String, spoken: String, corrected: String, createdAt: Long = 1_000L): LocalRow =
        LocalRow(
            uuid = uuid,
            timestampMs = createdAt,
            text = "",
            mode = "",
            durationMs = 0,
            provider = "",
            model = null,
            language = null,
            deletedAt = null,
            serverFileId = null,
            syncAccount = "acc@example.com",
            syncState = SYNC_STATE_CLEAN,
            quarantineReason = null,
            rtype = RecordType.Dictionary,
            spoken = spoken,
            corrected = corrected,
            kind = "correction",
        )

    @Test
    fun identicalContentAbsorbsIntoUserRow() = runBlocking {
        seedUserRow("brb", "be right back")
        store.import(row("uuid-1", "brb", "be right back"))

        val rows = allRows()
        assertEquals(1, rows.size)
        val absorbed = rows.single()
        assertEquals("brb", absorbed.spokenText)
        assertEquals("be right back", absorbed.replacementText)
        assertEquals("uuid-1", absorbed.syncId)
        assertEquals("acc@example.com", absorbed.syncAccount)
        assertNull(absorbed.quarantineReason)
    }

    @Test
    fun differentContentLatchesWithCollisionPlaceholder() = runBlocking {
        seedUserRow("brb", "be right back")
        store.import(row("uuid-1", "brb", "be back"))

        val rows = allRows()
        assertEquals(2, rows.size)
        val userRow = rows.first { it.syncId == null }
        assertEquals("brb", userRow.spokenText)
        val latched = rows.first { it.syncId == "uuid-1" }
        assertEquals("", latched.spokenText)
        assertEquals(QuarantineReason.Collision.asStr, latched.quarantineReason)
        assertEquals(SYNC_STATE_QUARANTINED, latched.syncState)
        assertEquals("acc@example.com", latched.syncAccount)
    }

    @Test
    fun reImportRelatchesInsteadOfLosingLatch() = runBlocking {
        seedUserRow("brb", "be right back")
        store.import(row("uuid-1", "brb", "be back", createdAt = 1_000L))
        store.import(row("uuid-1", "brb", "be back", createdAt = 2_000L))

        val rows = allRows()
        assertEquals(2, rows.size)
        val latched = rows.first { it.syncId == "uuid-1" }
        assertEquals(QuarantineReason.Collision.asStr, latched.quarantineReason)
        assertEquals(2_000L, latched.createdAt)
        // The user row was never touched.
        assertEquals("brb", rows.first { it.syncId == null }.spokenText)
    }

    @Test
    fun noCollisionUpsertsAndUpdatesByUuid() = runBlocking {
        store.import(row("uuid-2", "asap", "ASAP"))
        assertEquals(1, allRows().size)

        store.import(row("uuid-2", "asap", "as soon as possible", createdAt = 5_000L))
        val rows = allRows()
        assertEquals(1, rows.size)
        assertEquals("as soon as possible", rows.single().replacementText)
        assertEquals(5_000L, rows.single().createdAt)
    }

    @Test
    fun listRowsBackfillsSyncIdAndCreatedAt() = runBlocking {
        seedUserRow("idk", "i don't know")
        val rows = store.listRows(account = null)
        assertEquals(1, rows.size)
        assertNotNull(rows.single().uuid)
        val persisted = allRows().single()
        assertNotNull(persisted.syncId)
        assertNotNull(persisted.createdAt)
    }

    @Test
    fun foreignAccountRowsAreExcludedFromPass() = runBlocking {
        seedUserRow("brb", "be right back")
        store.import(row("uuid-1", "brb", "be back"))
        // A pass for another account sees only its own rows: the user row
        // (unstamped, backfilled) — the acc@example.com latch is excluded.
        val rows = store.listRows(account = "other@example.com")
        val latchedUuid = allRows().first { it.quarantineReason != null }.syncId
        assertEquals(1, rows.size)
        assertNotNull(rows.single().uuid)
        assertEquals(false, rows.single().uuid == latchedUuid)
    }
}