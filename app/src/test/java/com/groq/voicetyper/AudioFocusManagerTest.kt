package com.groq.voicetyper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Looper
import com.groq.voicetyper.offline.ModelAssetManager
import com.groq.voicetyper.offline.OfflineEngineType
import com.groq.voicetyper.offline.OfflinePipelineProvider
import com.groq.voicetyper.offline.OfflinePreferences
import com.groq.voicetyper.offline.OfflineTranscriber
import com.groq.voicetyper.offline.OfflineTranscriptionPipeline
import io.mockk.Runs
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for AudioFocusManager. No physical device required.
 *
 * The manager is a process singleton; [resetForTests] restores it between tests.
 * AudioManager / AudioFocusRequest are mocked; reconcile() is driven directly
 * except for the final wiring test which drives the real recording state flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioFocusManagerTest {

    private lateinit var context: Context
    private lateinit var audioManager: AudioManager
    private val focusRequest = mockk<AudioFocusRequest>()
    private val listenerSlot = slot<AudioManager.OnAudioFocusChangeListener>()

    @Before
    fun setUp() {
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.Dispatchers.Unconfined)

        mockkObject(AudioFocusPreferences)
        every { AudioFocusPreferences.isDuckingEnabled(any()) } returns true

        audioManager = mockk(relaxed = true)
        every { audioManager.requestAudioFocus(any()) } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager

        mockkConstructor(AudioFocusRequest.Builder::class)
        every { anyConstructed<AudioFocusRequest.Builder>().build() } returns focusRequest
        every { anyConstructed<AudioFocusRequest.Builder>().setAudioAttributes(any()) } answers {
            this.self as AudioFocusRequest.Builder
        }
        every {
            anyConstructed<AudioFocusRequest.Builder>().setOnAudioFocusChangeListener(capture(listenerSlot), any())
        } answers {
            this.self as AudioFocusRequest.Builder
        }

        mockkConstructor(AudioAttributes.Builder::class)
        every { anyConstructed<AudioAttributes.Builder>().setUsage(any()) } answers {
            this.self as AudioAttributes.Builder
        }
        every { anyConstructed<AudioAttributes.Builder>().setContentType(any()) } answers {
            this.self as AudioAttributes.Builder
        }
        every { anyConstructed<AudioAttributes.Builder>().build() } returns mockk()

        AudioFocusManager.attachForTest(context)
    }

    @After
    fun tearDown() {
        if (TranscriptionSessionManager.recordingState.value != RecordingState.IDLE) {
            TranscriptionSessionManager.cancelRecording(context)
            TranscriptionSessionManager.cancelImeRecording(context)
        }
        AudioFocusManager.resetForTests()
        kotlinx.coroutines.Dispatchers.resetMain()
        clearAllMocks()
        unmockkAll()
    }

    // 1. Preference OFF + RECORDING -> no AudioManager interaction at all.
    @Test
    fun prefOff_recording_neverTouchesAudioManager() {
        every { AudioFocusPreferences.isDuckingEnabled(any()) } returns false

        AudioFocusManager.reconcile(RecordingState.RECORDING)

        verify(exactly = 0) { audioManager.requestAudioFocus(any()) }
        verify(exactly = 0) { audioManager.abandonAudioFocusRequest(any()) }
    }

    // 2. Preference ON + RECORDING -> requestAudioFocus exactly once.
    @Test
    fun prefOn_recording_requestsFocusOnce() {
        AudioFocusManager.reconcile(RecordingState.RECORDING)

        verify(exactly = 1) { audioManager.requestAudioFocus(focusRequest) }
        verify(exactly = 1) { anyConstructed<AudioFocusRequest.Builder>().build() }
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            AudioFocusManager.DUCKING_FOCUS_GAIN
        )
    }

    // 3. RECORDING -> TRANSCRIBING abandons focus immediately.
    @Test
    fun recordingToTranscribing_abandonsFocus() {
        AudioFocusManager.reconcile(RecordingState.RECORDING)
        AudioFocusManager.reconcile(RecordingState.TRANSCRIBING)

        verify(exactly = 1) { audioManager.requestAudioFocus(any()) }
        verify(exactly = 1) { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    // 4. RECORDING -> IDLE abandons focus immediately.
    @Test
    fun recordingToIdle_abandonsFocus() {
        AudioFocusManager.reconcile(RecordingState.RECORDING)
        AudioFocusManager.reconcile(RecordingState.IDLE)

        verify(exactly = 1) { audioManager.requestAudioFocus(any()) }
        verify(exactly = 1) { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    // 5. RECORDING -> ERROR abandons focus immediately.
    @Test
    fun recordingToError_abandonsFocus() {
        AudioFocusManager.reconcile(RecordingState.RECORDING)
        AudioFocusManager.reconcile(RecordingState.ERROR)

        verify(exactly = 1) { audioManager.requestAudioFocus(any()) }
        verify(exactly = 1) { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    // 6. Repeated RECORDING states never duplicate the request.
    @Test
    fun repeatedRecording_requestsOnce() {
        AudioFocusManager.reconcile(RecordingState.RECORDING)
        AudioFocusManager.reconcile(RecordingState.RECORDING)
        AudioFocusManager.reconcile(RecordingState.RECORDING)

        verify(exactly = 1) { audioManager.requestAudioFocus(any()) }
        verify(exactly = 0) { audioManager.abandonAudioFocusRequest(any()) }
    }

    // 7. Repeated non-RECORDING states never duplicate the abandon.
    @Test
    fun repeatedNonRecording_abandonsOnce() {
        AudioFocusManager.reconcile(RecordingState.RECORDING)
        AudioFocusManager.reconcile(RecordingState.IDLE)
        AudioFocusManager.reconcile(RecordingState.IDLE)
        AudioFocusManager.reconcile(RecordingState.TRANSCRIBING)

        verify(exactly = 1) { audioManager.requestAudioFocus(any()) }
        verify(exactly = 1) { audioManager.abandonAudioFocusRequest(focusRequest) }
    }

    // 8. requestAudioFocus failure never escapes; focus simply not held.
    @Test
    fun requestFailure_doesNotThrow_andRecordingUnaffected() {
        every { audioManager.requestAudioFocus(any()) } returns AudioManager.AUDIOFOCUS_REQUEST_FAILED

        AudioFocusManager.reconcile(RecordingState.RECORDING)
        AudioFocusManager.reconcile(RecordingState.IDLE)

        verify(exactly = 1) { audioManager.requestAudioFocus(any()) }
        verify(exactly = 0) { audioManager.abandonAudioFocusRequest(any()) }
    }

    // 9. AudioManager throwing never escapes into the recording path.
    @Test
    fun audioManagerException_isSwallowed() {
        every { audioManager.requestAudioFocus(any()) } throws RuntimeException("boom")

        AudioFocusManager.reconcile(RecordingState.RECORDING)
        AudioFocusManager.reconcile(RecordingState.IDLE)
    }

    @Test
    fun abandonException_isSwallowed() {
        AudioFocusManager.reconcile(RecordingState.RECORDING)
        every { audioManager.abandonAudioFocusRequest(any()) } throws RuntimeException("boom")

        AudioFocusManager.reconcile(RecordingState.IDLE)
    }

    // 10. Focus-loss callback abandons cleanly and never touches recording state.
    @Test
    fun focusLossCallback_abandonsCleanly_withoutTouchingRecordingState() {
        val stateBefore = TranscriptionSessionManager.recordingState.value

        AudioFocusManager.reconcile(RecordingState.RECORDING)
        listenerSlot.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        verify(exactly = 1) { audioManager.abandonAudioFocusRequest(focusRequest) }
        assertEquals(stateBefore, TranscriptionSessionManager.recordingState.value)
        assertEquals(RecordingState.IDLE, TranscriptionSessionManager.recordingState.value)
    }

    @Test
    fun focusLoss_noAutomaticReRequest() {
        AudioFocusManager.reconcile(RecordingState.RECORDING)
        listenerSlot.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        listenerSlot.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        verify(exactly = 1) { audioManager.requestAudioFocus(any()) }
        verify(exactly = 1) { audioManager.abandonAudioFocusRequest(any()) }
    }

    // 11. Wiring: the live observer follows a real session RECORDING -> TRANSCRIBING.
    @Test
    fun wiring_liveSession_acquiresOnRecording_andAbandonsOnStop() {
        val requestCalls = AtomicInteger(0)
        val abandonCalls = AtomicInteger(0)
        every { audioManager.requestAudioFocus(any()) } answers {
            requestCalls.incrementAndGet()
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        every { audioManager.abandonAudioFocusRequest(any()) } answers {
            abandonCalls.incrementAndGet()
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        mockkObject(SecurityUtils)
        every { SecurityUtils.isStreamingEnabled(any()) } returns false
        every { SecurityUtils.getSttPreset(any()) } returns "groq"
        every { SecurityUtils.getSttLanguage(any()) } returns ""
        every { SecurityUtils.getLlmPreset(any()) } returns "groq"
        every { SecurityUtils.getSttBaseUrl(any(), any<String>()) } returns "https://api.groq.com/openai"
        every { SecurityUtils.getSttModel(any(), any<String>()) } returns "whisper-large-v3"
        every { SecurityUtils.getProviderApiKey(any(), any<String>(), any<String>()) } returns "fake-key"

        mockkObject(GroqClient)
        coEvery { GroqClient.transcribe(any(), any(), any(), any(), any()) } returns Result.success("hello")

        mockkObject(com.groq.voicetyper.history.HistoryRepository)
        coEvery { com.groq.voicetyper.history.HistoryRepository.save(any(), any(), any(), any(), any(), any(), any()) } just Runs

        mockkObject(com.groq.voicetyper.dictionary.DictionaryTextPostProcessor)
        every { com.groq.voicetyper.dictionary.DictionaryTextPostProcessor.process(any(), any()) } answers { secondArg() }

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
        every { pipeline.modelError } returns MutableStateFlow(null)
        every { pipeline.isRunning } returns MutableStateFlow(false)
        every { pipeline.onTextTranscribed = any() } just Runs
        every { pipeline.start(any()) } just Runs
        coEvery { pipeline.forceRelease() } returns Unit
        coEvery { OfflinePipelineProvider.getInstance(any(), any()) } returns pipeline

        val sessionContext = mockk<Context>(relaxed = true)
        every { sessionContext.applicationContext } returns sessionContext
        every { sessionContext.packageName } returns "com.example.normal"
        every { sessionContext.cacheDir } returns File(System.getProperty("java.io.tmpdir"))

        // Start the live observer for the wiring test.
        AudioFocusManager.attach(context)

        val listener = mockk<SessionListener>(relaxed = true)

        TranscriptionSessionManager.startRecording(
            sessionContext,
            isOffline = false,
            agentMode = false,
            listener = listener,
            targetPackage = sessionContext.packageName
        )
        waitFor { requestCalls.get() >= 1 }

        TranscriptionSessionManager.stopRecording(sessionContext)
        waitFor { abandonCalls.get() >= 1 }

        waitFor { TranscriptionSessionManager.recordingState.value == RecordingState.IDLE }

        assertTrue(requestCalls.get() >= 1)
        assertTrue(abandonCalls.get() >= 1)
    }

    private fun waitFor(condition: () -> Boolean) {
        runBlocking {
            withTimeout(10_000) {
                while (!condition()) {
                    delay(20)
                }
            }
        }
    }
}
