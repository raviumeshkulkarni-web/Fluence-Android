package com.groq.voicetyper.autolearn

import com.groq.voicetyper.autolearn.data.SuggestionDao
import com.groq.voicetyper.autolearn.data.SuggestionEntry
import com.groq.voicetyper.autolearn.data.SuggestionPairProjection
import com.groq.voicetyper.autolearn.data.SuggestionStatus
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
 * `saveEntryResolved` tests). Mocked-DAO tests validate the Kotlin decision
 * logic only; the Room @Query SQL is compile-validated by the Room/KAPT
 * compiler and verified manually via release QA — no automated on-device
 * suite exists in this repo.
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
        coEvery { dao.listPairProjections() } returns emptyList()

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
        coEvery { dao.listPairProjections() } returns listOf(SuggestionPairProjection(7, "shunade", "Sinead"))

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
    fun casingVariantFoldsIntoDismissedRowWithoutResurrecting() = runTest {
        // Windows classify_candidate parity: bump ANY existing row without
        // resetting lifecycle status - a case variant of a purge-demoted
        // (DISMISSED) pair bumps that row and stays hidden instead of
        // spawning a fresh PENDING row.
        coEvery { dao.findByPair("Shunade", "SineAd") } returns null
        coEvery { dao.listPairProjections() } returns listOf(
            SuggestionPairProjection(11, "shunade", "Sinead")
        )

        val result = SuggestionRepository.recordCandidateResolved(
            dao = dao,
            knownPairKeys = emptySet(),
            spoken = "Shunade",
            corrected = "SineAd",
            now = 1234L
        )

        assertTrue(result)
        coVerify(exactly = 1) { dao.bumpById(11, 1234L) }
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
        coEvery { dao.demotePendingOlderThan(any()) } returns 0

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
        coEvery { dao.demotePendingOlderThan(any()) } returns 0

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
        coEvery { dao.demotePendingOlderThan(any()) } returns 0

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
        coEvery { dao.demotePendingOlderThan(any()) } returns 0

        val demoted = SuggestionRepository.purgeInvalidPendingResolved(dao, emptySet())

        assertEquals(0, demoted)
        coVerify(exactly = 0) { dao.demotePending(any()) }
    }

    // -- dismissSuggestionResolved ----------------------------------------

    @Test
    fun dismissDemotesInsteadOfDeleting() = runTest {
        // Windows dismiss_suggestion parity: the row stays behind with
        // DISMISSED status (deny memory) so the pair can never resurface
        // as pending later.
        coEvery { dao.demotePending(42) } returns 1

        val demoted = SuggestionRepository.dismissSuggestionResolved(
            dao,
            entry(42, "shunade", "Sinead")
        )

        assertEquals(1, demoted)
        coVerify(exactly = 1) { dao.demotePending(42) }
        coVerify(exactly = 0) { dao.delete(any()) }
        coVerify(exactly = 0) { dao.deleteById(any()) }
    }

    @Test
    fun dismissOnNonPendingRowIsNoOp() = runTest {
        // demotePending() is status-guarded, mirroring the Windows
        // Pending-only lookup: dismissing twice dismisses once.
        coEvery { dao.demotePending(5) } returns 0

        val demoted = SuggestionRepository.dismissSuggestionResolved(
            dao,
            entry(5, "shunade", "Sinead")
        )

        assertEquals(0, demoted)
        coVerify(exactly = 0) { dao.delete(any()) }
    }

    @Test
    fun stalePendingRowsOlderThan30DaysAreExpiredAfterGatePurge() = runTest {
        // Windows expire_stale_suggestions parity (suggestion.rs:746-779):
        // gate purge runs FIRST, then the 30-day lastSeenAt sweep.
        coEvery { dao.getPendingForPurge() } returns emptyList()
        coEvery { dao.demotePendingOlderThan(1234L - 30L * 24 * 60 * 60 * 1000) } returns 2

        val demoted = SuggestionRepository.purgeInvalidPendingResolved(
            dao = dao,
            knownPairKeys = emptySet(),
            now = 1234L
        )

        assertEquals(2, demoted)
        coVerify(exactly = 1) { dao.demotePendingOlderThan(1234L - 30L * 24 * 60 * 60 * 1000) }
    }

    @Test
    fun dismissPairForDeletedEntryFoldsCanonicalCaseVariants() = runTest {
        // Windows dismiss_matching_suggestions parity (suggestion.rs:576-597):
        // pair-scoped deny memory on canonical identity - a case variant of
        // the deleted rule's pair is demoted, unrelated pairs untouched.
        coEvery { dao.listPairProjections() } returns listOf(
            SuggestionPairProjection(4, "SHUNADE", "Sinead"),
            SuggestionPairProjection(5, "unrelated", "other")
        )
        coEvery { dao.demotePending(4) } returns 1

        val dismissed = SuggestionRepository.dismissPairResolved(dao, "shunade", "SineAd", 99L)

        assertEquals(1, dismissed)
        coVerify(exactly = 1) { dao.demotePending(4) }
        coVerify(exactly = 0) { dao.demotePending(5) }
    }
}
