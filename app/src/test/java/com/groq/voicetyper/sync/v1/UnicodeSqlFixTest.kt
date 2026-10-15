package com.groq.voicetyper.sync.v1

import android.content.Context
import com.groq.voicetyper.dictionary.DictionaryRepository
import com.groq.voicetyper.dictionary.data.CustomDictionaryDao
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #9 — SQLite `lower()` is ASCII-only; the business key is Unicode.
 *
 * Two silent-loss paths shared one root cause.
 *
 * **Sync merge.** `applyMergedAndClearDirty` looked the collision up with
 * `getByBusinessKeyIncludingDeleted(rec.businessKey, hash)`, whose SQL is
 * `lower(trim(spokenText)) = :businessKey`. Stock Android SQLite folds ASCII
 * only, so `lower("Über")` stays `"Über"` while the Kotlin key is `"über"`:
 * no match → insert branch → `insert` is `@Insert(onConflict=IGNORE)` → the
 * unique `(spokenText, syncAccount)` index swallows it → `insertedId == -1L`
 * → `getBySyncId` is null because the colliding row has a different `syncId`
 * → no `appliedIds` entry, no throw, no log. The merged winner existed on
 * Drive and on desktop and was INVISIBLE on the phone, permanently. ASCII
 * `trim()` diverges identically, so fixing case alone would leave the split.
 *
 * **UI save.** `DictionaryRepository.saveEntryResolved` looked the row up with
 * `getBySpokenTextForAccount`, i.e. `LOWER(spokenText) = LOWER(:spokenText)`.
 * Typing "über" while "Über" existed missed, INSERTed (the raw values differ,
 * so the index never fired), and left TWO live rows for one word — both
 * returned by `getAllEnabledSyncForAccount`, so both corrections applied.
 *
 * The Rust twin derives keys in Rust and has no `lower(` in SQL, which is why
 * this was Android-only. Fix: resolve identity in Kotlin with
 * `DictionaryRecord.businessKeyOf` and compare exactly.
 */
class UnicodeSqlFixTest {

    private val hash = "a".repeat(64)

    private fun row(
        id: Long,
        spoken: String,
        syncAccount: String? = hash,
        deletedAt: Long? = null
    ) = CustomDictionaryEntry(
        id = id,
        spokenText = spoken,
        replacementText = "Ueber",
        syncId = "local-$id",
        syncAccount = syncAccount,
        deletedAt = deletedAt,
        updatedAt = 100L,
        deviceId = "device-a"
    )

    private fun rec(spoken: String, corrected: String = "Ueber") = DictionaryRecord(
        syncId = "remote-1",
        // Deliberately hostile: readers must recompute from `spoken`, never
        // trust the wire key (frozen v1.2 contract).
        businessKey = "totally-wrong-wire-key",
        spoken = spoken,
        corrected = corrected,
        isEnabled = true,
        updatedAt = 500L,
        deletedAt = null,
        deviceId = "device-b"
    )

    // ------------------------------------------------------------------
    // Sync merge path — findBusinessKeyCollision
    // ------------------------------------------------------------------

    @Test
    fun mergePathMatchesUnicodeCaseFoldedCollision() {
        // lower("Über") in stock SQLite == "Über" != "über" -> the old query
        // missed this row and the winner was silently swallowed by the index.
        val local = listOf(row(id = 1L, spoken = "Über"))
        val collision = findBusinessKeyCollision(local, rec("über"))
        assertNotNull("Unicode case folding must be resolved in Kotlin", collision)
        assertEquals(1L, collision!!.id)
    }

    @Test
    fun mergePathMatchesAcrossNonAsciiWhitespaceTrim() {
        // A no-break space is whitespace to the contract's unicodeTrim and
        // invisible to SQLite's ASCII trim().
        val local = listOf(row(id = 2L, spoken = "\u00A0Über\u00A0"))
        assertNotNull(findBusinessKeyCollision(local, rec("über")))
    }

    @Test
    fun mergePathRecomputesTheKeyFromSpokenAndIgnoresTheWireKey() {
        // Frozen contract: businessKey is recomputed from `spoken` on read.
        val local = listOf(row(id = 3L, spoken = "über"))
        assertNotNull(
            "a wrong wire businessKey must not change the collision",
            findBusinessKeyCollision(local, rec("ÜBER"))
        )
    }

    @Test
    fun mergePathStillReplacesATombstonedLoserInPlace() {
        // The old query was `...IncludingDeleted`, so a newer remote winner
        // could replace a tombstoned row. Candidates must stay tombstone-
        // inclusive or a deleted word could never come back.
        val tombstone = row(id = 4L, spoken = "Über", deletedAt = 400L)
        val collision = findBusinessKeyCollision(listOf(tombstone), rec("über"))
        assertNotNull(collision)
        assertEquals(4L, collision!!.id)
    }

    @Test
    fun mergePathChoosesDeterministicallyWhenTwoRowsShareTheKey() {
        // The old `LIMIT 1` had no ORDER BY; lowest row id is now the rule.
        val candidates = listOf(row(id = 9L, spoken = "ÜBER"), row(id = 2L, spoken = "über"))
        assertEquals(2L, findBusinessKeyCollision(candidates, rec("Über"))!!.id)
    }

    @Test
    fun mergePathDoesNotFoldDistinctWords() {
        // Kotlin lowercase maps nothing to ASCII here, but the guard must not
        // become a blanket "matches anything" either.
        assertNull(findBusinessKeyCollision(listOf(row(id = 5L, spoken = "STRASSE")), rec("straße")))
        assertNull(findBusinessKeyCollision(listOf(row(id = 6L, spoken = "hello")), rec("world")))
    }

    @Test
    fun mergePathReturnsNullForABlankWinnerRatherThanCollidingWithBlankRows() {
        val blank = row(id = 7L, spoken = "   ")
        assertNull(findBusinessKeyCollision(listOf(blank), rec("  ")))
    }

    // ------------------------------------------------------------------
    // UI save path — DictionaryRepository.findBySpokenKey
    // ------------------------------------------------------------------

    @Test
    fun uiPathMatchesUnicodeCaseFoldedExistingRow() {
        val existing = listOf(row(id = 1L, spoken = "Über"))
        assertEquals(1L, DictionaryRepository.findBySpokenKey(existing, "über")!!.id)
        assertEquals(1L, DictionaryRepository.findBySpokenKey(existing, "ÜBER")!!.id)
    }

    @Test
    fun uiPathStillMatchesAfterNonAsciiWhitespaceTrim() {
        val existing = listOf(row(id = 8L, spoken = "\u00A0Über "))
        assertNotNull(DictionaryRepository.findBySpokenKey(existing, "über"))
    }

    @Test
    fun uiPathDoesNotMatchADifferentWord() {
        assertNull(DictionaryRepository.findBySpokenKey(listOf(row(id = 1L, spoken = "Über")), "hello"))
    }

    // ------------------------------------------------------------------
    // UI save path — through the production saveEntryResolved seam
    // ------------------------------------------------------------------

    @Test
    fun saveEntryAbsorbsAUnicodeDuplicateInsteadOfInsertingASecondLiveRow() = runBlocking {
        val dao = mockk<CustomDictionaryDao>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val existing = row(id = 1L, spoken = "Über", syncAccount = null)
        coEvery { dao.getSyncRowsUnstamped() } returns listOf(existing)

        val result = DictionaryRepository.saveEntryResolved(dao, context, "über", "Ueber", true, 0L)

        assertEquals(
            "a Unicode-case duplicate must resolve to the existing row, not a new one",
            DictionaryRepository.SaveResult.PRESERVED,
            result
        )
        coVerify(exactly = 0) { dao.insert(any()) }
        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun saveEntryStillInsertsAGenuinelyNewWord() = runBlocking {
        // The Unicode guard must not turn every save into a PRESERVE.
        val dao = mockk<CustomDictionaryDao>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        coEvery { dao.getSyncRowsUnstamped() } returns listOf(row(id = 1L, spoken = "Über", syncAccount = null))
        coEvery { dao.insert(any()) } returns 42L

        val result = DictionaryRepository.saveEntryResolved(dao, context, "hello", "hi", true, 0L)

        assertEquals(DictionaryRepository.SaveResult.INSERTED, result)
        coVerify(exactly = 1) { dao.insert(match { it.spokenText == "hello" }) }
    }

    @Test
    fun saveEntryRevivesATombstonedUnicodeRowRatherThanAddingASecondOne() = runBlocking {
        // Tombstone-inclusive candidates: the old SQL had no deletedAt filter,
        // so a re-add revived the row instead of inserting a live duplicate.
        val dao = mockk<CustomDictionaryDao>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val tombstone = row(id = 3L, spoken = "Über", syncAccount = null, deletedAt = 400L)
        coEvery { dao.getSyncRowsUnstamped() } returns listOf(tombstone)
        val updated = io.mockk.slot<CustomDictionaryEntry>()
        coEvery { dao.update(capture(updated)) } returns Unit

        val result = DictionaryRepository.saveEntryResolved(dao, context, "über", "Ueber", true, 0L)

        assertEquals(DictionaryRepository.SaveResult.UPDATED, result)
        assertEquals(3L, updated.captured.id)
        assertEquals(null, updated.captured.deletedAt)
        coVerify(exactly = 0) { dao.insert(any()) }
    }
}
