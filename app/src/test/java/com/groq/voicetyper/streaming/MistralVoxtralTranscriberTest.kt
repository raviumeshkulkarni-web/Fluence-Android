package com.groq.voicetyper.streaming

import io.mockk.every
import io.mockk.mockkStatic
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real transport tests for MistralVoxtralTranscriber against a live MockWebServer
 * WebSocket endpoint (S5a/S5b). These exercise the actual implementation: channel
 * buffering, writer coroutine, frame encoding, and event parsing.
 */
class MistralVoxtralTranscriberTest {

    private lateinit var server: MockWebServer
    private val collectJobs = mutableListOf<Job>()

    @Before
    fun setUp() {
        mockkStatic(android.util.Base64::class)
        every {
            android.util.Base64.encodeToString(any(), any())
        } answers {
            java.util.Base64.getEncoder().encodeToString(firstArg())
        }

        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        collectJobs.forEach { it.cancel() }
        try {
            server.shutdown()
        } catch (e: IOException) {
            // A websocket whose close handshake never completed keeps the server
            // executor alive; close() force-releases connections without waiting.
            server.close()
        }
    }

    private fun collectEvents(transcriber: MistralVoxtralTranscriber): MutableList<StreamingTranscriptEvent> {
        val events = Collections.synchronizedList(mutableListOf<StreamingTranscriptEvent>())
        val job = kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            transcriber.connect(
                server.url("/v1/audio/transcriptions/realtime").toString(),
                "test-key",
                "voxtral-mini-transcribe-realtime-2602",
                null
            ).collect { events.add(it) }
        }
        collectJobs.add(job)
        return events
    }

    private fun awaitTrue(condition: () -> Boolean, timeoutMs: Long = 5000, message: String = "condition not met") {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(message, condition())
    }

    @Test
    fun audioChunksBeforeAndAfterOpen_allArriveInOrder_noLoss() {
        val received = Collections.synchronizedList(mutableListOf<String>())
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    // okhttp does not auto-ack close frames; without the reply the
                    // handshake never completes and shutdown() hangs.
                    webSocket.close(code, reason)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                }
            })
        )

        val transcriber = MistralVoxtralTranscriber()
        collectEvents(transcriber)

        // Send frames immediately after connect: some land before the socket opens,
        // some after. EVERY frame must arrive, in order, none dropped (S5a).
        val frameCount = 10
        repeat(frameCount) { i ->
            val frame = ByteArray(320) { (i % 128).toByte() }
            transcriber.sendAudioChunk(frame, frame.size)
        }
        awaitTrue({ received.size >= frameCount }, message = "server received all audio frames")

        val decoded = received.take(frameCount).map { frameJson ->
            val json = JSONObject(frameJson)
            assertEquals("input_audio.append", json.getString("type"))
            java.util.Base64.getDecoder().decode(json.getString("audio"))
        }
        repeat(frameCount) { i ->
            assertArrayEquals(ByteArray(320) { (i % 128).toByte() }, decoded[i])
        }

        // The end marker must follow ALL audio frames (stopAndFinalize joins the
        // writer, so the queue is drained before input_audio.end is sent).
        runBlocking { transcriber.stopAndFinalize() }
        awaitTrue({ received.size == frameCount + 1 }, message = "input_audio.end received")
        assertEquals("input_audio.end", JSONObject(received.last()).getString("type"))

        transcriber.close()
    }

    @Test
    fun partialAndDone_framesProduceNormalizedEvents() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"transcription.text.delta","delta":"Hello"}""")
                    webSocket.send("""{"type":"transcription.text.delta","delta":" world"}""")
                    webSocket.send("""{"type":"transcription.done"}""")
                }
            })
        )

        val transcriber = MistralVoxtralTranscriber()
        val events = collectEvents(transcriber)

        awaitTrue({ events.size >= 3 }, message = "Partial/Partial/Final events emitted")
        assertEquals(StreamingTranscriptEvent.Partial("Hello"), events[0])
        assertEquals(StreamingTranscriptEvent.Partial("Hello world"), events[1])
        assertEquals(StreamingTranscriptEvent.Final("Hello world"), events[2])

        transcriber.close()
    }

    @Test
    fun serverClose_midSession_emitsClosed() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(1000, "done")
                }
            })
        )

        val transcriber = MistralVoxtralTranscriber()
        val events = collectEvents(transcriber)

        awaitTrue({ events.any { it is StreamingTranscriptEvent.Closed } }, message = "Closed event emitted")
        transcriber.close()
    }

    @Test
    fun transportQueueOverflow_failsExplicitlyWithError() {
        val transcriber = MistralVoxtralTranscriber()

        // Fill the bounded queue without an open socket: frame 65 (capacity 64)
        // must fail the session explicitly, never drop audio silently (S5a).
        repeat(MistralVoxtralTranscriber.AUDIO_QUEUE_CAPACITY + 1) {
            transcriber.sendAudioChunk(ByteArray(320) { 1 }, 320)
        }

        val events = collectEvents(transcriber)
        awaitTrue({ events.any { it is StreamingTranscriptEvent.Error } }, message = "overflow surfaced as Error")
        val error = events.first { it is StreamingTranscriptEvent.Error } as StreamingTranscriptEvent.Error
        assertTrue(error.message.contains("keep up") || error.message.contains("aborted"))

        transcriber.close()
    }

    @Test
    fun serverErrorFrame_emitsErrorEvent() {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send("""{"type":"error","error":{"message":"Invalid API key"}}""")
                }
            })
        )

        val transcriber = MistralVoxtralTranscriber()
        val events = collectEvents(transcriber)

        awaitTrue({ events.any { it is StreamingTranscriptEvent.Error } }, message = "server error surfaced")
        val error = events.first { it is StreamingTranscriptEvent.Error } as StreamingTranscriptEvent.Error
        assertEquals("Invalid API key", error.message)
        transcriber.close()
    }
}
