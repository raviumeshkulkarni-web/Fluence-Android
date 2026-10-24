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
        unmockkAll()
    }

    private fun stubLivePath(
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()
    ) {
        mockkObject(com.groq.voicetyper.PrivacyPreferences)
        every {
            com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(any(), any())
        } returns false
        mockkObject(SuggestionRepository)
        coEvery { SuggestionRepository.knownPairKeys(any()) } returns emptySet()
        coJustRun { SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any()) }
        AutoLearnSessionManager.dispatcherOverride = dispatcher
    }

    @Test
    fun oversizedCommittedTextStopsObservationWithoutRecordingCandidates() {
        mockkObject(SuggestionRepository)
        AutoLearnSessionManager.startSession("x".repeat(10_001), context)

        AutoLearnSessionManager.onTextUpdated("short edit", context)

        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any()) }
    }

    @Test
    fun oversizedEditedTextStopsObservationWithoutRecordingCandidates() {
        mockkObject(SuggestionRepository)
        AutoLearnSessionManager.startSession("short committed text", context)

        AutoLearnSessionManager.onTextUpdated("y".repeat(10_001), context)

        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any()) }
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

        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any()) }
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
    fun staleJobFromPriorSessionAbortsInNewSession() {
        // Windows monitor parity: a batch job launched before a session
        // flip must not write after endSession or pollute the next
        // session's dedup set (generation-token revalidation).
        val testDispatcher = StandardTestDispatcher()
        stubLivePath(testDispatcher)

        AutoLearnSessionManager.startSession("I went to the shunade", context)
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context) // queued, not run
        AutoLearnSessionManager.endSession()
        AutoLearnSessionManager.onStartInput(editorInfo, context) // privacy re-granted
        AutoLearnSessionManager.startSession("I went to the shunade", context)
        testDispatcher.scheduler.advanceUntilIdle() // stale job runs: generation mismatch -> abort

        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), any(), any(), any())
        }

        // The new session forms its own valid observation -> exactly once.
        AutoLearnSessionManager.onTextUpdated("I went to the Sinead", context)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead", any())
        }
    }
}
