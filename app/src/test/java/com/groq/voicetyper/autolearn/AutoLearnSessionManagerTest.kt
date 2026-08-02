package com.groq.voicetyper.autolearn

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.groq.voicetyper.autolearn.domain.AutoLearnSessionManager
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test

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
        unmockkAll()
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
}
