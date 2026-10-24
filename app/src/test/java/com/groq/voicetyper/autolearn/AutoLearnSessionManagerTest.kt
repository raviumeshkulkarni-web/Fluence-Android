package com.groq.voicetyper.autolearn

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.groq.voicetyper.autolearn.domain.AutoLearnSessionManager
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun stubLivePath() {
        mockkObject(com.groq.voicetyper.PrivacyPreferences)
        every {
            com.groq.voicetyper.PrivacyPreferences.isPackageExcluded(any(), any())
        } returns false
        mockkObject(SuggestionRepository)
        coEvery { SuggestionRepository.knownPairKeys(any()) } returns emptySet()
        coJustRun { SuggestionRepository.recordCorrectionCandidate(any(), any(), any()) }
        AutoLearnSessionManager.dispatcherOverride = UnconfinedTestDispatcher()
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
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead")
        }
        coVerify(exactly = 0) {
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "SINEAD")
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
            SuggestionRepository.recordCorrectionCandidate(any(), "shunade", "Sinead")
        }
    }

    @Test
    fun candidatesNotPassingGatesNeverReachRepository() {
        stubLivePath()
        AutoLearnSessionManager.startSession("stuck agent release", context)
        AutoLearnSessionManager.onTextUpdated("add tabs agent stuck release plan", context)

        coVerify(exactly = 0) { SuggestionRepository.recordCorrectionCandidate(any(), any(), any()) }
    }
}
