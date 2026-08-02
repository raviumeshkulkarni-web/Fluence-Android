package com.groq.voicetyper.offline

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OfflineTranscriberTest {

    @Test
    fun testInitialState_isUnloaded() {
        val mockEngine = mockk<RecognizerEngine>(relaxed = true)
        val transcriber = OfflineTranscriber(mockEngine)
        assertEquals(OfflineTranscriber.EngineState.UNLOADED, transcriber.engineState.value)
        assertFalse(transcriber.isReady())
    }

    @Test
    fun testInitialize_success_transitionsToReady() = runTest {
        val mockEngine = mockk<RecognizerEngine>(relaxed = true)
        val transcriber = OfflineTranscriber(mockEngine)

        transcriber.initialize("/dummy/dir", 2)

        assertEquals(OfflineTranscriber.EngineState.READY, transcriber.engineState.value)
        assertTrue(transcriber.isReady())
        coVerify(exactly = 1) { mockEngine.initialize("/dummy/dir", 2) }
    }

    @Test
    fun testInitialize_failure_transitionsToUnloaded() = runTest {
        val mockEngine = mockk<RecognizerEngine>()
        coEvery { mockEngine.initialize(any(), any()) } throws IOException("Init failed")
        
        val transcriber = OfflineTranscriber(mockEngine)

        try {
            transcriber.initialize("/dummy/dir", 2)
            fail("Expected exception")
        } catch (e: Exception) {
            // expected
        }

        assertEquals(OfflineTranscriber.EngineState.UNLOADED, transcriber.engineState.value)
        assertFalse(transcriber.isReady())
    }

    @Test
    fun testInitialize_nativeError_resetsToUnloaded() = runTest {
        val mockEngine = mockk<RecognizerEngine>()
        coEvery { mockEngine.initialize(any(), any()) } throws UnsatisfiedLinkError("native lib missing")

        val transcriber = OfflineTranscriber(mockEngine)

        try {
            transcriber.initialize("/dummy/dir", 2)
            fail("Expected native error to propagate")
        } catch (e: UnsatisfiedLinkError) {
            // expected
        }

        assertEquals(OfflineTranscriber.EngineState.UNLOADED, transcriber.engineState.value)
        assertFalse(transcriber.isReady())
    }

    @Test
    fun testTranscribe_whenNotReady_returnsEmptyString() = runTest {
        val mockEngine = mockk<RecognizerEngine>(relaxed = true)
        val transcriber = OfflineTranscriber(mockEngine)

        val result = transcriber.transcribe(floatArrayOf(0.1f))
        assertEquals("", result)
        coVerify(exactly = 0) { mockEngine.transcribe(any(), any()) }
    }

    @Test
    fun testTranscribe_waitsForPendingInitialization_beforeInference() = runTest {
        val mockEngine = mockk<RecognizerEngine>(relaxed = true)
        val initStarted = CompletableDeferred<Unit>()
        val allowInitToFinish = CompletableDeferred<Unit>()
        coEvery { mockEngine.initialize(any(), any()) } coAnswers {
            initStarted.complete(Unit)
            allowInitToFinish.await()
        }
        coEvery { mockEngine.transcribe(any(), any()) } returns "first utterance"

        val transcriber = OfflineTranscriber(mockEngine)

        val initJob = launch(Dispatchers.Default) {
            transcriber.initialize("/dummy/dir", 2)
        }
        initStarted.await()

        // Transcribe is invoked while initialize is still in flight: it must
        // wait for initialization instead of returning "" immediately.
        val transcribeDeferred = async(Dispatchers.Default) {
            transcriber.transcribe(floatArrayOf(0.1f))
        }
        allowInitToFinish.complete(Unit)

        initJob.join()
        val result = transcribeDeferred.await()

        assertEquals("first utterance", result)
        coVerify(exactly = 1) { mockEngine.transcribe(any(), any()) }
    }

    @Test
    fun testTranscribe_afterFailedInitialization_returnsEmpty() = runTest {
        val mockEngine = mockk<RecognizerEngine>()
        coEvery { mockEngine.initialize(any(), any()) } throws IOException("Init failed")

        val transcriber = OfflineTranscriber(mockEngine)

        try {
            transcriber.initialize("/dummy/dir")
        } catch (e: Exception) {
            // expected
        }

        val result = transcriber.transcribe(floatArrayOf(0.1f))
        assertEquals("", result)
        coVerify(exactly = 0) { mockEngine.transcribe(any(), any()) }
    }

    @Test
    fun testTranscribe_afterRelease_returnsEmpty() = runTest {
        val mockEngine = mockk<RecognizerEngine>(relaxed = true)
        val transcriber = OfflineTranscriber(mockEngine)

        transcriber.initialize("/dummy/dir")
        transcriber.release()

        val result = transcriber.transcribe(floatArrayOf(0.1f))
        assertEquals("", result)
        coVerify(exactly = 0) { mockEngine.transcribe(any(), any()) }
    }

    @Test
    fun testTranscribe_whenReady_returnsTranscribedText() = runTest {
        val mockEngine = mockk<RecognizerEngine>(relaxed = true)
        coEvery { mockEngine.transcribe(any(), any()) } returns "hello world"

        val transcriber = OfflineTranscriber(mockEngine)
        transcriber.initialize("/dummy/dir")

        val result = transcriber.transcribe(floatArrayOf(0.1f))
        assertEquals("hello world", result)
        coVerify(exactly = 1) { mockEngine.transcribe(any(), any()) }
    }

    @Test
    fun testRelease_cancelsInFlightTranscription_andJoins() = runTest {
        val mockEngine = mockk<RecognizerEngine>(relaxed = true)
        
        // Setup engine transcribe to suspend for a while
        val transcribeStarted = CompletableDeferred<Unit>()
        val transcribeFinished = CompletableDeferred<Unit>()
        
        coEvery { mockEngine.transcribe(any(), any()) } coAnswers {
            transcribeStarted.complete(Unit)
            try {
                delay(5000) // Keep it suspended
                "should be cancelled"
            } finally {
                transcribeFinished.complete(Unit)
            }
        }

        val transcriber = OfflineTranscriber(mockEngine)
        transcriber.initialize("/dummy/dir")

        // Start transcription in a separate coroutine
        val transcribeJob = launch(Dispatchers.Default) {
            transcriber.transcribe(floatArrayOf(0.1f))
        }

        // Wait until transcription has actually started and entered the lock
        transcribeStarted.await()

        // Call release on the transcriber
        val releaseJob = launch(Dispatchers.Default) {
            transcriber.release()
        }

        // Wait for the release to finish
        releaseJob.join()

        // Verify that the transcribe job was cancelled and is finished
        transcribeFinished.await()
        transcribeJob.join()
        // Verify that the transcribe job was completed (via cancellation)
        assertTrue(transcribeJob.isCompleted)

        // Verify the final engine state
        assertEquals(OfflineTranscriber.EngineState.UNLOADED, transcriber.engineState.value)
        coVerify(exactly = 1) { mockEngine.release() }
    }
}
