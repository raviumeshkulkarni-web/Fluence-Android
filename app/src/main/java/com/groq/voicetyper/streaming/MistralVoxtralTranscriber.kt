package com.groq.voicetyper.streaming

import android.util.Base64
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * Mistral AI Realtime WebSocket implementation of [StreamingTranscriber].
 * Connects to Mistral's Realtime Audio API (`voxtral-mini-transcribe-realtime-2602`),
 * streams Base64-encoded PCM16 audio chunks, accumulates deltas into cumulative
 * hypothesis strings, and emits normalized [StreamingTranscriptEvent] objects.
 *
 * Audio transport: the real-time capture thread only enqueues PCM frames into a
 * bounded channel ([AUDIO_QUEUE_CAPACITY]). A dedicated writer coroutine drains the
 * channel, Base64-encodes, and writes to the WebSocket — the capture thread never
 * performs network work. Frames captured before the socket opens are buffered in the
 * same bounded channel (no silent loss of the first words); if the transport cannot
 * keep up, the session fails explicitly with an [StreamingTranscriptEvent.Error]
 * instead of dropping audio.
 */
class MistralVoxtralTranscriber : StreamingTranscriber {

    companion object {
        private const val TAG = "MistralVoxtralTranscriber"
        private const val DEFAULT_MODEL = "voxtral-mini-transcribe-realtime-2602"

        /** Bounded PCM transport: ~2.5s of 40ms frames held before/while writing. */
        const val AUDIO_QUEUE_CAPACITY = 64

        /** How long to wait for the WebSocket to open before failing the session. */
        private const val OPEN_TIMEOUT_MS = 20_000L

        private val sharedClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                // readTimeout(0) keeps the socket alive between utterances. A dead
                // connection still surfaces quickly: the writer pushes a frame every
                // 40ms while recording, so send failures / OkHttp's failure callback
                // fire on the next write instead of waiting for a read timeout.
                .readTimeout(0, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }

    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writerJob: Job? = null

    /** Bounded queue of PCM frames between the capture thread and the writer. */
    private val audioQueue = Channel<ByteArray>(
        capacity = AUDIO_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    /** Set on [WebSocketListener.onOpen]; the writer waits for it before draining. */
    private var openGate = CompletableDeferred<Unit>()

    /** Guards one-shot transport failure signaling (queue overflow / send errors). */
    private val transportFailed = AtomicBoolean(false)

    @Volatile
    private var webSocket: WebSocket? = null

    private val eventChannel = Channel<StreamingTranscriptEvent>(
        capacity = Channel.UNLIMITED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val textAccumulator = java.lang.StringBuilder()
    @Volatile
    private var isFinalized = false

    override fun connect(
        baseUrl: String,
        apiKey: String,
        model: String,
        language: String?
    ): Flow<StreamingTranscriptEvent> {
        textAccumulator.setLength(0)
        isFinalized = false
        transportFailed.set(false)
        openGate = CompletableDeferred()

        val targetModel = model.ifBlank { DEFAULT_MODEL }
        val baseWsUrl = if (baseUrl.startsWith("https://")) {
            baseUrl.replaceFirst("https://", "wss://")
        } else if (baseUrl.startsWith("http://")) {
            baseUrl.replaceFirst("http://", "ws://")
        } else {
            "wss://api.mistral.ai"
        }

        val endpoint = "${baseWsUrl.trimEnd('/')}/v1/audio/transcriptions/realtime?model=$targetModel"
        Log.d(TAG, "Connecting to Mistral Realtime WebSocket endpoint: $endpoint")

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            .header("x-api-key", apiKey)
            .build()

        webSocket = sharedClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Mistral Realtime WebSocket connection opened")
                openGate.complete(Unit)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                // RFC 6455: a received close frame MUST be acknowledged with a close
                // reply. okhttp does not do this automatically — without it the peer
                // never completes the handshake, so a graceful server close would
                // surface as a connection failure (EOF) instead of onClosed.
                ws.close(code, reason)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                parseServerMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val errorMsg = t.localizedMessage ?: "WebSocket failure (${response?.code ?: "Network error"})"
                Log.e(TAG, "Mistral Realtime WebSocket error: $errorMsg", t)
                if (transportFailed.compareAndSet(false, true)) {
                    eventChannel.trySend(StreamingTranscriptEvent.Error(t, errorMsg))
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Mistral Realtime WebSocket closed (code $code): $reason")
                eventChannel.trySend(StreamingTranscriptEvent.Closed)
            }
        })

        startWriter()
        return eventChannel.receiveAsFlow()
    }

    /**
     * Drains [audioQueue] and writes frames to the WebSocket. Waits for the socket
     * to open first so pre-open frames are retained, not dropped. Exits on close,
     * cancellation, or a transport failure (which emits an Error event once).
     */
    private fun startWriter() {
        writerJob?.cancel()
        writerJob = writerScope.launch {
            val opened = withTimeoutOrNull(OPEN_TIMEOUT_MS) { openGate.await() }
            if (opened == null) {
                Log.e(TAG, "WebSocket did not open within $OPEN_TIMEOUT_MS ms")
                if (transportFailed.compareAndSet(false, true)) {
                    eventChannel.trySend(
                        StreamingTranscriptEvent.Error(
                            IOException("WebSocket open timed out"),
                            "Connection to the STT provider timed out."
                        )
                    )
                }
                return@launch
            }

            while (true) {
                val frame = audioQueue.receiveCatching()
                if (frame.isClosed || transportFailed.get()) break
                val socket = webSocket ?: break
                try {
                    val pcm = frame.getOrThrow()
                    val base64Pcm = Base64.encodeToString(pcm, Base64.NO_WRAP)
                    val audioMsg = JSONObject().apply {
                        put("type", "input_audio.append")
                        put("audio", base64Pcm)
                    }
                    val sent = socket.send(audioMsg.toString())
                    if (!sent) {
                        failTransport("WebSocket closed while sending audio.")
                        break
                    }
                } catch (e: Exception) {
                    failTransport("Failed to send audio: ${e.localizedMessage ?: "unknown error"}")
                    break
                }
            }
        }
    }

    private fun failTransport(message: String) {
        Log.e(TAG, message)
        if (transportFailed.compareAndSet(false, true)) {
            eventChannel.trySend(StreamingTranscriptEvent.Error(Exception(message), message))
        }
    }

    private fun parseServerMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            Log.v(TAG, "Received frame type: $type")

            when (type) {
                "transcription.text.delta", "TranscriptionStreamTextDelta", "transcription.delta" -> {
                    val delta = json.optString("delta", json.optString("text", ""))
                    if (delta.isNotEmpty()) {
                        synchronized(textAccumulator) {
                            textAccumulator.append(delta)
                            val currentText = textAccumulator.toString()
                            eventChannel.trySend(StreamingTranscriptEvent.Partial(currentText))
                        }
                    }
                }
                "transcription.done", "TranscriptionStreamDone", "session.done" -> {
                    if (!isFinalized) {
                        isFinalized = true
                        val finalText = synchronized(textAccumulator) { textAccumulator.toString().trim() }
                        eventChannel.trySend(StreamingTranscriptEvent.Final(finalText))
                    }
                }
                "error" -> {
                    val errorObj = json.optJSONObject("error")
                    val message = errorObj?.optString("message") ?: json.optString("message", "Mistral server error")
                    Log.e(TAG, "Mistral error event: $message")
                    eventChannel.trySend(StreamingTranscriptEvent.Error(Exception(message), message))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse incoming WebSocket JSON frame", e)
        }
    }

    override fun sendAudioChunk(pcmData: ByteArray, length: Int) {
        if (length <= 0 || transportFailed.get() || audioQueue.isClosedForSend) return

        // The capture thread reuses its PCM buffer, so the frame must be copied
        // before it is handed to the async writer.
        val copy = pcmData.copyOf(length)
        val result = audioQueue.trySend(copy)
        if (result.isFailure) {
            // Bounded transport is full and the writer cannot keep up. Fail the
            // session explicitly rather than silently dropping audio.
            failTransport("Audio transport could not keep up; the streaming session was aborted.")
        }
    }

    override suspend fun stopAndFinalize() {
        // Close the queue first: the writer drains the remaining frames, then exits.
        audioQueue.close()
        writerJob?.join()

        val socket = webSocket ?: return
        try {
            val endMsg = JSONObject().apply {
                put("type", "input_audio.end")
            }
            socket.send(endMsg.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send input_audio.end frame", e)
        }
    }

    override fun close() {
        try {
            webSocket?.close(1000, "Session closed")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing WebSocket", e)
        } finally {
            webSocket = null
        }
        writerJob?.cancel()
        writerJob = null
        audioQueue.close()
    }
}
