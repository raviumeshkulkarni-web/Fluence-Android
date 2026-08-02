package com.groq.voicetyper

import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Looper
import com.groq.voicetyper.offline.ModelAssetManager
import com.groq.voicetyper.offline.OfflineEngineType
import com.groq.voicetyper.offline.OfflinePipelineProvider
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.offline.OfflineTranscriber
import com.groq.voicetyper.offline.OfflineTranscriptionPipeline
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.Runs
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class TranscriptionSessionManagerOwnerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)

        mockkObject(OfflinePreferences)
        every { OfflinePreferences.isOfflineModeEnabled(any()) } returns true
        every { OfflinePreferences.getEngineType(any()) } returns OfflineEngineType.SENSEVOICE

        mockkObject(ModelAssetManager)
        every { ModelAssetManager.isModelReadySync(any()) } returns true
        every { ModelAssetManager.getModelDir(any()) } returns File(System.getProperty("java.io.tmpdir"))

        mockkObject(OfflinePipelineProvider)
        coEvery { OfflinePipelineProvider.releaseInstance() } returns Unit

        val pipeline = mockk<OfflineTranscriptionPipeline>(relaxed = true)
        every { pipeline.amplitude } returns MutableStateFlow(0f)
        every { pipeline.engineState } returns MutableStateFlow(OfflineTranscriber.EngineState.READY)
        every { pipeline.onTextTranscribed = any() } just Runs
        every { pipeline.start(any()) } just Runs
        coEvery { pipeline.forceRelease() } returns Unit
        coEvery { OfflinePipelineProvider.getInstance(any(), any()) } returns pipeline

        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        if (TranscriptionSessionManager.recordingState.value == RecordingState.RECORDING) {
            TranscriptionSessionManager.cancelRecording(context)
            TranscriptionSessionManager.cancelImeRecording(context)
        }
        clearAllMocks()
    }

    @Test
    fun bubbleSession_isNotTornDownByImeDestroy() {
        val listener = mockk<SessionListener>(relaxed = true)
        TranscriptionSessionManager.startRecording(context, isOffline = true, agentMode = false, listener)
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)

        TranscriptionSessionManager.destroy()
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)

        TranscriptionSessionManager.cancelImeRecording(context)
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)

        TranscriptionSessionManager.cancelRecording(context)
        assertEquals(RecordingState.IDLE, TranscriptionSessionManager.recordingState.value)
    }

    @Test
    fun imeSession_isNotCancelledByBubbleCancel() {
        val listener = mockk<SessionListener>(relaxed = true)
        TranscriptionSessionManager.startImeRecording(context, isOffline = true, agentMode = false, listener)
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)

        TranscriptionSessionManager.cancelRecording(context)
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)

        TranscriptionSessionManager.cancelImeRecording(context)
        assertEquals(RecordingState.IDLE, TranscriptionSessionManager.recordingState.value)
    }

    @Test
    fun onTrimMemory_doesNotReleasePipelineWhileSessionActive() {
        val listener = mockk<SessionListener>(relaxed = true)
        TranscriptionSessionManager.startImeRecording(context, isOffline = true, agentMode = false, listener)
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)

        TranscriptionSessionManager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        coVerify(exactly = 0) { OfflinePipelineProvider.releaseInstance() }

        TranscriptionSessionManager.cancelImeRecording(context)
        assertEquals(RecordingState.IDLE, TranscriptionSessionManager.recordingState.value)

        TranscriptionSessionManager.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        coVerify(timeout = 3000) { OfflinePipelineProvider.releaseInstance() }
    }

    @Test
    fun secondStart_isRejectedWhileAlreadyRecording() {
        val listener = mockk<SessionListener>(relaxed = true)
        TranscriptionSessionManager.startImeRecording(context, isOffline = true, agentMode = false, listener)
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)

        TranscriptionSessionManager.startRecording(context, isOffline = true, agentMode = false, mockk(relaxed = true))
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)

        TranscriptionSessionManager.cancelImeRecording(context)
        assertEquals(RecordingState.IDLE, TranscriptionSessionManager.recordingState.value)
    }
}
