package com.groq.voicetyper.autolearn

import com.groq.voicetyper.autolearn.data.SuggestionDao
import com.groq.voicetyper.autolearn.data.SuggestionEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the SuggestionRepository scheduler-free cores
 * (injectable-DAO seam convention, mirroring DictionaryRepository's
 * `saveEntryResolved` tests). SQL semantics of the Room queries themselves
 * are validated at build time by Room and exercised on-device.
 */
class SuggestionRepositoryTest {

    private val dao = mockk<SuggestionDao>(relaxUnitFun = true)

    private fun entry(
        id: Long,
        spoken: String,
        corrected: String,
        frequency: Int = 1
    ) = SuggestionEntry(
        id = id,
        spokenText = spoken,
        correctedText = corrected,
        frequency = frequency
    )

    // -- recordCandidateResolved ------------------------------------------

    @Test
    fun gateRejectsPairBeforeAnyDaoTouch() = runTest {
        val result = SuggestionRepository.recordCandidateResolved(
            dao = dao,
            knownPairKeys = emptySet(),
            spoken = "stuck",
            corrected = "agent",
            now = 0L
        )

        assertFalse(result)
        coVerify(exactly = 0) { dao.findByPair(any(), any()) }
        coVerify(exactly = 0) { dao.upsertCandidate(any(), any(), any()) }
        coVerify(exactly = 0) { dao.bumpById(any(), any()) }
    }

    @Test
    fun knownPairGateWinsOverSimilarityGate() = runTest {
        val known = setOf(
            com.groq.voicetyper.autolearn.domain.DecisionCore.canonicalPairKey("do", "add")
        )
        val result = SuggestionRepository.recordCandidateResolved(
            dao = dao,
            knownPairKeys = known,
            spoken = "do",
            corrected = "add",
            now = 0L
        )

        assertFalse(result)
        coVerify(exactly = 0) { dao.upsertCandidate(any(), any(), any()) }
    }

    @Test
    fun freshPairUpserts() = runTest {
        coEvery { dao.findByPair("shunade", "Sinead") } returns null
        coEvery { dao.getPendingForPurge() } returns emptyList()

        val result = SuggestionRepository.recordCandidateResolved(
            dao = dao,
            knownPairKeys = emptySet(),
            spoken = "shunade",
            corrected = "Sinead",
            now = 1234L
        )

        assertTrue(result)
        coVerify(exactly = 1) { dao.upsertCandidate("shunade", "Sinead", 1234L) }
        coVerify(exactly = 0) { dao.bumpById(any(), any()) }
    }

    @Test
    fun casingVariantFoldsIntoPendingTwinByCanonicalKey() = runTest {
        coEvery { dao.findByPair("shunade", "SineAd") } returns null
        coEvery { dao.getPendingForPurge() } returns listOf(entry(7, "shunade", "Sinead"))

        val result = SuggestionRepository.recordCandidateResolved(
            dao = dao,
            knownPairKeys = emptySet(),
            spoken = "shunade",
            corrected = "SineAd",
            now = 1234L
        )

        assertTrue(result)
        coVerify(exactly = 1) { dao.bumpById(7, 1234L) }
        coVerify(exactly = 0) { dao.upsertCandidate(any(), any(), any()) }
    }

    @Test
    fun exactPairStillBumpsExistingRow() = runTest {
        coEvery { dao.findByPair("shunade", "Sinead") } returns entry(3, "shunade", "Sinead")

        val result = SuggestionRepository.recordCandidateResolved(
            dao = dao,
            knownPairKeys = emptySet(),
            spoken = "shunade",
            corrected = "Sinead",
            now = 1234L
        )

        assertTrue(result)
        coVerify(exactly = 1) { dao.bumpById(3, 1234L) }
        coVerify(exactly = 0) { dao.upsertCandidate(any(), any(), any()) }
    }

    // -- purgeInvalidPendingResolved --------------------------------------

    @Test
    fun purgeDemotesOnlyGateInvalidRowsAndNeverDeletes() = runTest {
        coEvery { dao.getPendingForPurge() } returns listOf(
            entry(1, "stuck", "agent"), // floor reject
            entry(2, "shunade", "Sinead"), // valid residual
            entry(3, "do", "add"), // tightest floor reject
            entry(4, "cool", "Good"), // valid RC-1 residual
            entry(5, "hello", "Hello") // case-only reject
        )
        coEvery { dao.demotePending(any()) } returns 1

        val demoted = SuggestionRepository.purgeInvalidPendingResolved(dao, emptySet())

        assertEquals(3, demoted)
        coVerify(exactly = 1) { dao.demotePending(1) }
        coVerify(exactly = 0) { dao.demotePending(2) }
        coVerify(exactly = 1) { dao.demotePending(3) }
        coVerify(exactly = 0) { dao.demotePending(4) }
        coVerify(exactly = 1) { dao.demotePending(5) }
        coVerify(exactly = 0) { dao.delete(any()) }
        coVerify(exactly = 0) { dao.deleteById(any()) }
    }

    @Test
    fun purgeJudgesFrequencyOneRowsToo() = runTest {
        coEvery { dao.getPendingForPurge() } returns listOf(
            entry(9, "stuck", "agent", frequency = 1)
        )
        coEvery { dao.demotePending(any()) } returns 1

        val demoted = SuggestionRepository.purgeInvalidPendingResolved(dao, emptySet())

        assertEquals(1, demoted)
        coVerify(exactly = 1) { dao.demotePending(9) }
    }

    @Test
    fun purgeDemotesRowsWhosePairIsNowKnown() = runTest {
        // Windows parity: purge re-judges with the CURRENT dictionary, so a
        // pending row whose pair became a dictionary entry (any casing) is
        // redundant and is demoted, not kept.
        val known = setOf(
            com.groq.voicetyper.autolearn.domain.DecisionCore.canonicalPairKey("shunade", "Sinead")
        )
        coEvery { dao.getPendingForPurge() } returns listOf(
            entry(2, "shunade", "Sinead"),
            entry(4, "SHUNADE", "SINEAD") // canonical fold: same known pair
        )
        coEvery { dao.demotePending(any()) } returns 1

        val demoted = SuggestionRepository.purgeInvalidPendingResolved(dao, known)

        assertEquals(2, demoted)
        coVerify(exactly = 1) { dao.demotePending(2) }
        coVerify(exactly = 1) { dao.demotePending(4) }
    }

    @Test
    fun validResidualRowsSurviveStalePendingPurge() = runTest {
        // Rows that pass every gate (incl. owner-accepted boundary
        // residuals) are untouched: idempotent no-op on re-run.
        coEvery { dao.getPendingForPurge() } returns listOf(
            entry(6, "shunade", "Sinead"),
            entry(7, "cool", "Good")
        )

        val demoted = SuggestionRepository.purgeInvalidPendingResolved(dao, emptySet())

        assertEquals(0, demoted)
        coVerify(exactly = 0) { dao.demotePending(any()) }
    }
}
