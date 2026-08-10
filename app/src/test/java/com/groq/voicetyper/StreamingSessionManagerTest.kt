package com.groq.voicetyper

import android.content.Context
import android.os.Looper
import com.groq.voicetyper.dictionary.DictionaryTextPostProcessor
import com.groq.voicetyper.history.HistoryRepository
import com.groq.voicetyper.offline.ModelAssetManager
import com.groq.voicetyper.offline.OfflineEngineType
import com.groq.voicetyper.offline.OfflinePipelineProvider
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.offline.OfflineTranscriber
import com.groq.voicetyper.offline.OfflineTranscriptionPipeline
import com.groq.voicetyper.streaming.MistralVoxtralTranscriber
import com.groq.voicetyper.streaming.StreamingAudioCapture
import com.groq.voicetyper.streaming.StreamingTranscriptEvent
import io.mockk.Runs
import io.mockk.andThenJust
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
/**
 * Behavior tests for TranscriptionSessionManager's streaming path, exercising the REAL
 * singleton against mocked Android/iO dependencies (same MockK pattern as the owner
 * test). These cover the audit findings S4/S5c/S5d/S5e and the S7 requirement that
 * tests exercise the implementation rather than invented lookalikes.
 */

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingSessionManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        // The real TSM dispatches listener callbacks through Dispatchers.Main.
        // Unconfined runs those blocks inline on the calling (IO) thread, which is
        // exactly the semantics the assertions below need.
        Dispatchers.setMain(Dispatchers.Unconfined)

        mockkObject(SecurityUtils)
        mockkObject(OfflinePreferences)
        every { OfflinePreferences.isOfflineModeEnabled(any()) } returns false
        every { OfflinePreferences.getEngineType(any()) } returns OfflineEngineType.SENSEVOICE

        mockkObject(DictionaryTextPostProcessor)
        every { DictionaryTextPostProcessor.process(any(), any()) } answers { secondArg() }

        mockkObject(HistoryRepository)
        coEvery { HistoryRepository.save(any(), any(), any(), any(), any(), any(), any()) } just Runs

        mockkObject(CommandProcessor)

        // Streaming path constructs these directly; intercept the constructors so no
        // real AudioRecord/OkHttp/threads are created.
        mockkConstructor(StreamingAudioCapture::class)
        every { anyConstructed<StreamingAudioCapture>().amplitude } returns MutableStateFlow(0f)
        every { anyConstructed<StreamingAudioCapture>().startCapture(any()) } just Runs
        every { anyConstructed<StreamingAudioCapture>().stopCapture() } just Runs

        mockkConstructor(MistralVoxtralTranscriber::class)
        coEvery { anyConstructed<MistralVoxtralTranscriber>().connect(any(), any(), any(), any()) } returns flow {
            awaitCancellation()
        }
        coEvery { anyConstructed<MistralVoxtralTranscriber>().stopAndFinalize() } returns Unit
        every { anyConstructed<MistralVoxtralTranscriber>().close() } just Runs

        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        val state = TranscriptionSessionManager.recordingState.value
        if (state == RecordingState.RECORDING || state == RecordingState.TRANSCRIBING) {
            TranscriptionSessionManager.cancelRecording(context)
            TranscriptionSessionManager.cancelImeRecording(context)
        }
        Dispatchers.resetMain()
        clearAllMocks()
    }

    private fun stubStreamingConfig(streamingEnabled: Boolean, preset: String, sttKey: String?) {
        every { SecurityUtils.isStreamingEnabled(any()) } returns streamingEnabled
        every { SecurityUtils.getSttPreset(any()) } returns preset
        every { SecurityUtils.getProviderApiKey(any(), any(), any()) } returns sttKey
        every { SecurityUtils.getSttBaseUrl(any(), any()) } returns "https://api.mistral.ai"
        every { SecurityUtils.getSttModel(any(), any()) } returns "voxtral-mini-latest"
        every { SecurityUtils.getSttLanguage(any()) } returns ""
        every { SecurityUtils.getLlmPreset(any()) } returns "groq"
        every { SecurityUtils.getLlmBaseUrl(any(), any()) } returns "https://api.groq.com/openai"
        every { SecurityUtils.getLlmModel(any(), any()) } returns "llama-3.3-70b-versatile"
    }

    private fun awaitState(expected: RecordingState, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (TranscriptionSessionManager.recordingState.value == expected) return
            Thread.sleep(10)
        }
        assertEquals(expected, TranscriptionSessionManager.recordingState.value)
    }

    /**
     * TSM caches the AudioRecorder instance in a private field (initRecorder only
     * constructs when null) and the field persists across test classes sharing the
     * JVM. Null it so the batch tests' constructor mock is actually used.
     */
    private fun resetCachedAudioRecorder() {
        val field = TranscriptionSessionManager::class.java.getDeclaredField("audioRecorder")
        field.isAccessible = true
        field.set(TranscriptionSessionManager, null)
    }

    // ---- Branch selection ---------------------------------------------------

    @Test
    fun streamingEnabled_mistralPreset_startsStreamingCapture() {
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = "stt-key")
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener)

        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)
        verify { anyConstructed<StreamingAudioCapture>().startCapture(any()) }
    }

    @Test
    fun streamingEnabled_mistralPreset_usesRealtimeCompatibleModel() {
        // Release blocker: Mistral's realtime endpoint must be called with the
        // realtime-compatible model, never the stored batch model
        // (voxtral-mini-latest).
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = "stt-key")
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener)
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)

        coVerify(timeout = 3000) {
            anyConstructed<MistralVoxtralTranscriber>()
                .connect(any(), any(), eq("voxtral-mini-realtime-latest"), any())
        }
    }

    @Test
    fun streamingEnabled_customPreset_keepsConfiguredModel() {
        // Custom providers keep their own configured model; only the Mistral
        // preset is forced to the realtime-compatible model.
        stubStreamingConfig(streamingEnabled = true, preset = "custom", sttKey = "stt-key")
        every { SecurityUtils.getSttModel(any(), any()) } returns "my-custom-model"
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener)
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)

        coVerify(timeout = 3000) {
            anyConstructed<MistralVoxtralTranscriber>()
                .connect(any(), any(), eq("my-custom-model"), any())
        }
    }

    @Test
    fun streamingDisabled_usesBatchRecorder() {
        stubStreamingConfig(streamingEnabled = false, preset = "groq", sttKey = "stt-key")
        resetCachedAudioRecorder()
        mockkConstructor(AudioRecorder::class)
        every { anyConstructed<AudioRecorder>().amplitude } returns MutableStateFlow(0f)
        every { anyConstructed<AudioRecorder>().startRecording() } returns true
        every { anyConstructed<AudioRecorder>().stopRecording() } returns File("recording.m4a")
        mockkObject(GroqClient)
        coEvery { GroqClient.transcribe(any(), any(), any(), any(), any()) } returns Result.success("hello batch")
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener)
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)
        verify { anyConstructed<AudioRecorder>().startRecording() }

        TranscriptionSessionManager.stopRecording(context)
        awaitState(RecordingState.IDLE)
        verify { listener.onTranscription("hello batch") }
    }

    @Test
    fun offlineMode_usesOfflinePipeline() {
        stubStreamingConfig(streamingEnabled = false, preset = "groq", sttKey = "stt-key")
        every { OfflinePreferences.isOfflineModeEnabled(any()) } returns true
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
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = true, agentMode = false, listener)
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)
        verify(timeout = 3000) { pipeline.start(any()) }
    }

    @Test
    fun agentModeWithStreamingEnabled_stillUsesStreaming() {
        // S4: Agent Mode must not silently force the batch path when streaming is on.
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = "stt-key")
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = true, listener)

        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)
        verify { anyConstructed<StreamingAudioCapture>().startCapture(any()) }
    }

    // ---- Streaming lifecycle -------------------------------------------------

    @Test
    fun partialAndFinal_partialsNeverInsertedFinalInsertedExactlyOnce() {
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = "stt-key")
        coEvery {
            anyConstructed<MistralVoxtralTranscriber>().connect(any(), any(), any(), any())
        } returns flowOf(
            StreamingTranscriptEvent.Partial("hel"),
            StreamingTranscriptEvent.Partial("hello"),
            StreamingTranscriptEvent.Final("hello world")
        )
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener)
        awaitState(RecordingState.IDLE)

        // Invariant: partial text never reaches the target — only the final is
        // delivered through onTranscription (which is what injects into the app).
        verify(exactly = 1) { listener.onTranscription("hello world") }
        verify(exactly = 0) { listener.onCommand(any(), any()) }
        verify(exactly = 2) { listener.onPartialTranscription(any()) }
        assertEquals("", TranscriptionSessionManager.partialText.value)

        // Full teardown: capture stopped, transcriber closed, session over.
        verify { anyConstructed<StreamingAudioCapture>().stopCapture() }
        verify { anyConstructed<MistralVoxtralTranscriber>().close() }
    }

    @Test
    fun cancelDuringStreaming_isInstantAndSilent() {
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = "stt-key")
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener)
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)

        TranscriptionSessionManager.cancelRecording(context)
        assertEquals(RecordingState.IDLE, TranscriptionSessionManager.recordingState.value)
        assertNull(TranscriptionSessionManager.errorMessage.value)
        verify { anyConstructed<StreamingAudioCapture>().stopCapture() }
        verify { anyConstructed<MistralVoxtralTranscriber>().close() }
        verify(exactly = 0) { listener.onTranscription(any()) }
        verify(exactly = 0) { listener.onError(any()) }
    }

    @Test
    fun stopWithoutFinal_watchdogForceEndsSession() {
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = "stt-key")
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener)
        TranscriptionSessionManager.stopRecording(context)
        assertEquals(RecordingState.TRANSCRIBING, TranscriptionSessionManager.recordingState.value)

        // No Final ever arrives (dead socket): the watchdog must end the session
        // instead of hanging in TRANSCRIBING forever (S5c).
        awaitState(RecordingState.IDLE, TranscriptionSessionManager.STREAMING_FINALIZE_TIMEOUT_MS + 4000)
        assertTrue(TranscriptionSessionManager.errorMessage.value?.contains("did not complete") == true)
        // stopAndFinalize runs on the manager's async scope (Dispatchers.Default) before
        // the watchdog delay, so once the session has reached IDLE it is guaranteed to
        // have been invoked. Verify after that observation instead of racing the
        // dispatcher (this coVerify flaked on loaded CI runners within its poll window).
        coVerify { anyConstructed<MistralVoxtralTranscriber>().stopAndFinalize() }
        verify { anyConstructed<StreamingAudioCapture>().stopCapture() }
        verify { anyConstructed<MistralVoxtralTranscriber>().close() }
    }

    @Test
    fun missingSttApiKey_tearsDownCaptureAndTranscriber() {
        // S5d regression: the capture must be stopped, not left running for the
        // next tap to open a second AudioRecord.
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = null)
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener)
        awaitState(RecordingState.IDLE)

        assertTrue(TranscriptionSessionManager.errorMessage.value?.contains("API Key is missing") == true)
        verify { anyConstructed<StreamingAudioCapture>().stopCapture() }
        verify { anyConstructed<MistralVoxtralTranscriber>().close() }
    }

    @Test
    fun errorEvent_cleansUpWithoutInserting() {
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = "stt-key")
        coEvery {
            anyConstructed<MistralVoxtralTranscriber>().connect(any(), any(), any(), any())
        } returns flowOf(StreamingTranscriptEvent.Error(Exception("boom"), "boom"))
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener)
        awaitState(RecordingState.ERROR)

        assertTrue(TranscriptionSessionManager.errorMessage.value?.contains("boom") == true)
        verify { anyConstructed<StreamingAudioCapture>().stopCapture() }
        verify { anyConstructed<MistralVoxtralTranscriber>().close() }
        verify(exactly = 0) { listener.onTranscription(any()) }
    }

    @Test
    fun serverClosedMidSession_endsSessionExplicitly() {
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = "stt-key")
        coEvery {
            anyConstructed<MistralVoxtralTranscriber>().connect(any(), any(), any(), any())
        } returns flowOf(StreamingTranscriptEvent.Closed)
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener)
        awaitState(RecordingState.ERROR)

        assertTrue(TranscriptionSessionManager.errorMessage.value?.contains("closed") == true)
        verify { anyConstructed<StreamingAudioCapture>().stopCapture() }
        verify { anyConstructed<MistralVoxtralTranscriber>().close() }
    }

    @Test
    fun restartAfterFinal_startsCleanSecondSession() {
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = "stt-key")
        val listener1 = mockk<SessionListener>(relaxed = true)
        val listener2 = mockk<SessionListener>(relaxed = true)

        coEvery {
            anyConstructed<MistralVoxtralTranscriber>().connect(any(), any(), any(), any())
        } returns flowOf(StreamingTranscriptEvent.Final("first"))
        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener1)
        awaitState(RecordingState.IDLE)

        coEvery {
            anyConstructed<MistralVoxtralTranscriber>().connect(any(), any(), any(), any())
        } returns flowOf(StreamingTranscriptEvent.Final("second"))
        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener2)
        // No intermediate RECORDING assertion here: the mocked connect flow emits
        // Final immediately, and on a loaded CI runner the IO collector can deliver
        // it before the test thread observes RECORDING (state races to IDLE). The
        // restart cleanliness is proven by the verifications below instead.
        awaitState(RecordingState.IDLE)

        verify(exactly = 1) { listener1.onTranscription("first") }
        verify(exactly = 1) { listener2.onTranscription("second") }
        // anyConstructed() in a verify block aggregates across ALL constructed
        // instances, so this proves two sessions, each captured and stopped once.
        verify(exactly = 2) { anyConstructed<StreamingAudioCapture>().startCapture(any()) }
        verify(exactly = 2) { anyConstructed<StreamingAudioCapture>().stopCapture() }
    }

    @Test
    fun micStartFailure_retriesOnceThenFailsExplicitly() {
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = "stt-key")
        every { anyConstructed<StreamingAudioCapture>().startCapture(any()) } throws
            IllegalStateException("mic busy") andThenJust Runs
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener)
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)

        // Retry succeeds; the session continues and completes normally, with no error.
        verify(timeout = 3000, exactly = 2) { anyConstructed<StreamingAudioCapture>().startCapture(any()) }
        assertNull(TranscriptionSessionManager.errorMessage.value)
        TranscriptionSessionManager.cancelRecording(context)
    }

    @Test
    fun micStartDoubleFailure_endsSessionWithError() {
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = "stt-key")
        every { anyConstructed<StreamingAudioCapture>().startCapture(any()) } throws
            IllegalStateException("mic busy")
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = false, listener)
        awaitState(RecordingState.IDLE)

        assertTrue(TranscriptionSessionManager.errorMessage.value?.contains("microphone") == true)
        verify(timeout = 3000, exactly = 2) { anyConstructed<StreamingAudioCapture>().startCapture(any()) }
        verify { anyConstructed<StreamingAudioCapture>().stopCapture() }
    }

    @Test
    fun destroy_duringImeStreaming_tearsDownSession() {
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = "stt-key")
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startImeRecording(context, isOffline = false, agentMode = false, listener)
        assertEquals(RecordingState.RECORDING, TranscriptionSessionManager.recordingState.value)

        TranscriptionSessionManager.destroy()
        assertEquals(RecordingState.IDLE, TranscriptionSessionManager.recordingState.value)
        verify { anyConstructed<StreamingAudioCapture>().stopCapture() }
        verify { anyConstructed<MistralVoxtralTranscriber>().close() }
    }

    // ---- Agent integration (S4) ---------------------------------------------

    @Test
    fun agentMode_streamingFinal_routesThroughCommandProcessor() {
        stubStreamingConfig(streamingEnabled = true, preset = "mistral", sttKey = "stt-key")
        coEvery {
            anyConstructed<MistralVoxtralTranscriber>().connect(any(), any(), any(), any())
        } returns flowOf(
            StreamingTranscriptEvent.Partial("delete last"),
            StreamingTranscriptEvent.Final("delete last word")
        )
        coEvery { CommandProcessor.processCommand(any(), any(), any(), any(), any()) } returns
            Result.success(CommandResult("DELETE_CHARS", deleteCount = 4))
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = true, listener)
        awaitState(RecordingState.IDLE)

        coVerify { CommandProcessor.processCommand(any(), any(), any(), "delete last word", any()) }
        verify(exactly = 1) { listener.onCommand(any(), any()) }
        verify(exactly = 0) { listener.onTranscription(any()) }
        verify(exactly = 1) { listener.onPartialTranscription(any()) }
    }

    @Test
    fun agentMode_batchFinal_routesThroughCommandProcessor() {
        stubStreamingConfig(streamingEnabled = false, preset = "groq", sttKey = "stt-key")
        resetCachedAudioRecorder()
        mockkConstructor(AudioRecorder::class)
        every { anyConstructed<AudioRecorder>().amplitude } returns MutableStateFlow(0f)
        every { anyConstructed<AudioRecorder>().startRecording() } returns true
        every { anyConstructed<AudioRecorder>().stopRecording() } returns java.io.File.createTempFile("batch-test", ".wav")
        mockkObject(GroqClient)
        coEvery { GroqClient.transcribe(any(), any(), any(), any(), any()) } returns Result.success("move to end")
        coEvery { CommandProcessor.processCommand(any(), any(), any(), any(), any()) } returns Result.success(
            CommandResult("MOVE_CURSOR", cursorPosition = "END")
        )
        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(context, isOffline = false, agentMode = true, listener)
        TranscriptionSessionManager.stopRecording(context)
        awaitState(RecordingState.IDLE)

        coVerify { CommandProcessor.processCommand(any(), any(), any(), "move to end", any()) }
        verify(exactly = 1) { listener.onCommand(any(), any()) }
        verify(exactly = 0) { listener.onTranscription(any()) }
    }
}
