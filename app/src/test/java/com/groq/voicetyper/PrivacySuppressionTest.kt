package com.groq.voicetyper

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PrivacySuppressionTest {
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(PrivacyPreferences)
        every { PrivacyPreferences.isPackageExcluded(any(), "com.example.excluded") } returns true
        every { PrivacyPreferences.isPackageExcluded(any(), "com.example.normal") } returns false
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun excludedTarget_blocksNewRecordingBeforeSessionStateChanges() {
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(
            context = context,
            isOffline = false,
            agentMode = true,
            listener = listener,
            targetPackage = "com.example.excluded"
        )

        verify { listener.onError("Dictation is unavailable in this app") }
        assertEquals(RecordingState.IDLE, TranscriptionSessionManager.recordingState.value)
    }
}
