package com.groq.voicetyper.autolearn

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.groq.voicetyper.autolearn.domain.AutoLearnSessionManager
import io.mockk.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)

class AutoLearnSessionManagerTest {

    private val context = mockk<Context>(relaxed = true)
    private val editorInfo = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
    }

    @Before
    fun setUp() {
        mockkObject(AutoLearnPreferences)
        every { AutoLearnPreferences.isAutoLearnEnabled(any()) } returns true
        AutoLearnSessionManager.onStartInput(editorInfo, context)
    }

    @After
    fun tearDown() {
        AutoLearnSessionManager.endSession()
        AutoLearnSessionManager.dispatcherOverride = null
        AutoLearnSessionManager.settleDelayOverride = null
        unmockkAll()
    }

    private fun stubLivePath(
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
        settleDelay: Long = 0L
    ) {
        mockkObject(com.groq.voicetyper.PrivacyPreferences)
        every {
            com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(any(), any())
        } returns false
        mockkObject(SuggestionRepository)
        coEvery { SuggestionRepository.knownPairKeys(any()) } returns emptySet()
        coJustRun { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
        AutoLearnSessionManager.dispatcherOverride = dispatcher
        AutoLearnSessionManager.settleDelayOverride = settleDelay
    }

    @Test
    fun oversizedCommittedTextStopsObservationWithoutRecordingCandidates() {
        mockkObject(SuggestionRepository)
        AutoLearnSessionManager.startSession("x".repeat(10_001), context)

        AutoLearnSessionManager.onTextUpdated("short edit", context)

        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    @Test
    fun oversizedEditedTextStopsObservationWithoutRecordingCandidates() {
        mockkObject(SuggestionRepository)
        AutoLearnSessionManager.startSession("short committed text", context)

        AutoLearnSessionManager.onTextUpdated("y".repeat(10_001), context)

        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    @Test
    fun excludedPackage_doesNotStartObservation() {
        val excludedInfo = EditorInfo().apply {
            packageName = "com.example.excluded"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        mockkObject(com.groq.voicetyper.PrivacyPreferences)
        every {
            com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(context, "com.example.excluded")
        } returns true
        mockkObject(SuggestionRepository)

        AutoLearnSessionManager.onStartInput(excludedInfo, context)
        AutoLearnSessionManager.startSession("sensitive text", context)
        AutoLearnSessionManager.onTextUpdated("sensitive edit", context)

        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    @Test
    fun duplicateCandidatesWithinOneSessionRecordedOnce() {
        // Windows monitor session-dedup parity (monitor.rs): cursor moves
        // within one session must not re-count the same canonical pair.
        stubLivePath()
        AutoLearnSessionManager.startSession("I went to the shunade", context)

        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        AutoLearnSessionManager.onTextUpdated("I went to the SINEAD", context) // case variant folds canonically

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "SINEAD", any())
        }
    }

    @Test
    fun newSessionClearsPairKeysAndRecordsAgain() {
        stubLivePath()
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        AutoLearnSessionManager.endSession()

        // Real IME flow: the next field focus calls onStartInput, which
        // re-establishes the privacy gate before a new startSession.
        AutoLearnSessionManager.onStartInput(editorInfo, context)
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)

        coVerify(exactly = 2) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun candidatesNotPassingGatesNeverReachRepository() {
        stubLivePath()
        AutoLearnSessionManager.startSession("stuck agent release", context)
        AutoLearnSessionManager.onTextUpdated("add tabs agent stuck release plan", context)

        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
    }

    @Test
    fun observedUnsettledChangeFlushesAtRegularClose() {
        // Windows final diff (monitor.rs:515-548): a regular close while the
        // last observed state never settled still diffs it once, and the
        // flush cannot pollute the next session's dedup set (its key copy
        // is flush-local).
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)

        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context) // settle armed, timer not reached
        AutoLearnSessionManager.endSession()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }

        // The next session forms its own valid observation -> recorded again.
        AutoLearnSessionManager.onStartInput(editorInfo, context)
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 2) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun privacyFlippedObservationAbortsWritesWithoutFinalDiff() {
        // Review round 2 (must-fix 1), round-3 form: a PRIVACY revocation
        // mid-observation must abort the recorded candidate and must NOT
        // rescue it through the exit flush (Windows SecureFieldDetected
        // parity - the observed content is never persisted).
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher)
        val excludedInfo = EditorInfo().apply {
            packageName = "com.example.excluded"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        // One-shot poison (review round 4: a persistent stub leaked into the
        // second phase and killed the fresh session there instead). Only the
        // first lookup of phase 1 revokes privacy.
        var poisoned = false
        coEvery { SuggestionRepository.knownPairKeys(any()) } coAnswers {
            if (!poisoned) {
                poisoned = true
                every {
                    com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(any(), "com.example.excluded")
                } returns true
                AutoLearnSessionManager.onStartInput(excludedInfo, context)
            }
            emptySet<String>()
        }

        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }

        // A fresh privacy-allowed session still forms its own observation -> once.
        every { com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(any(), any()) } returns false
        AutoLearnSessionManager.onStartInput(editorInfo, context)
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun recordFailureDoesNotCrashAndReleasesPairKeyForRetry() {
        // Review round 2 (must-fix 2): DAO failures (disk full, corrupt DB)
        // fail open instead of crashing the IME, and the dedup key is
        // released so a later legitimate observation can retry.
        stubLivePath()
        coEvery {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        } throws RuntimeException("disk full")
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }

        // Retry works because the failed write released the pair key. The
        // second observation must differ from the already-classified
        // baseline, otherwise the settle gate treats it as a no-op
        // (Windows identical-read rule).
        coJustRun { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead now", context)

        coVerify(exactly = 2) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }

    @Test
    fun keystrokeBurstCollapsesIntoSingleSettledDiff() {
        // Windows SETTLED_POLLS parity (monitor.rs:77-82): intermediate
        // typing states must not become candidates - the burst collapses
        // into one diff of the final text.
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        AutoLearnSessionManager.startSession("I definately love this", context)

        AutoLearnSessionManager.onTextUpdated("I definely love this", context)
        AutoLearnSessionManager.onTextUpdated("I definitel love this", context)
        AutoLearnSessionManager.onTextUpdated("I definitely love this", context)

        testDispatcher.scheduler.advanceTimeBy(499)
        testDispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }

        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "definately", "definitely", any())
        }
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), "definately", "definely", any())
        }
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), "definately", "definitel", any())
        }
    }

    @Test
    fun midTypingPrivacyRevocationSuppressesFinalDiff() {
        // Windows SecureFieldDetected/ReadOnlyField parity: a
        // privacy-revoking close stops immediately; observed content is
        // dropped, NOT flushed by the exit diff.
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)

        every { com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(any(), any()) } returns true
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead again", context)

        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }
    }

    @Test
    fun blankFieldUpdateCancelsPendingArm() {
        // Windows NoValue parity (monitor.rs:466-478): an empty field is
        // never classified - clearing the field cancels the armed
        // observation so the close cannot flush it as a final diff
        // (monitor.rs:523-528 trusts only a readable last state).
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher, settleDelay = 500L)
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        AutoLearnSessionManager.onTextUpdated("", context)
        AutoLearnSessionManager.endSession()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }
    }

    @Test
    fun staleObservationAfterDeferredLookupAbortsRecording() {
        // Review round 4: the dictionary lookup and extraction are not
        // interruption points, so a newer keystroke (which cancels this arm
        // and bumps the settle epoch) must abort the stale observation at
        // its next guard - the intermediate never reaches the repository.
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher)
        var firstLookup = true
        coEvery { SuggestionRepository.knownPairKeys(any()) } coAnswers {
            if (firstLookup) {
                firstLookup = false
                // Keystroke lands while the lookup is in flight: this arm is
                // cancelled and a newer one armed; the in-flight work continues.
                AutoLearnSessionManager.onTextUpdated("I went to the Sineaddx", context)
                emptySet<String>()
            } else {
                emptySet<String>()
            }
        }
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        testDispatcher.scheduler.advanceUntilIdle()

        // The stale observation ("Sinead") aborted; only the newer arm
        // ("Sineaddx", a distinct canonical pair) recorded - once.
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sineaddx", any())
        }
    }
}
