package com.groq.voicetyper.streaming

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriptEvent
import android.util.Log
import com.groq.voicetyper.offline.v2.MoonshineV2ResidentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

/**
 * Offline streaming speech-to-text implementation using Moonshine v2's native
 * ergodic streaming engine (sliding-window attention + cross-KV caching).
 *
 * Implements [StreamingTranscriber] so it can drop directly into the existing
 * streaming session pipeline without touching UI or textbox delivery layers.
 *
 * Audio is ingested in 16 kHz 16-bit PCM frames from [StreamingAudioCapture],
 * converted to normalized float samples, and fed continuously to the native stream.
 * Streaming state is strictly preserved across successive audio chunks.
 *
 * Fixes for production accuracy/latency:
 * - UNLIMITED queue (never DROP_OLDEST) so mid-speech gaps cannot occur.
 * - Drain all queued audio before native stop() so tail is not lost.
 * - Throttled updateInterval=1.0s to keep CPU ~1x real-time (was 0.5s causing 800ms decode every 500ms).
 * - Final emitted only after native stop's LineCompleted callbacks have propagated.
 * - Per-recording stream (createStream/startStream/stopStream/freeStream) to avoid hidden-state contamination.
 * - Listener tracked and removed on close to prevent leak on resident handle.
 *
 * Lifecycle hardening (single-owner native teardown):
 * A single worker coroutine owns every native call (addAudioToStream / stopStream /
 * freeStream / engine.close() / removeListener / resident release). All teardown runs
 * in that worker's `finally` after the drain loop has fully terminated, so it can never
 * overlap an in-flight native call on the same stream/transcriber handle. Neither
 * stopAndFinalize() nor close() touches native handles directly.
 */
class MoonshineV2StreamingTranscriber(
    private val modelDir: File,
    private val modelArch: Int
) : StreamingTranscriber {

    companion object {
        private const val TAG = "MoonshineV2Transcriber"
        private const val SAMPLE_RATE = 16000
        private const val UPDATE_INTERVAL_SEC = 1.0
    }

    private val transcriberScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var workerJob: Job? = null

    // UNLIMITED so microphone never loses audio under CPU load.
    // A 10s utterance = 250 frames (~320KB), negligible vs model 142MB.
    private val audioQueue = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    private val eventChannel = Channel<StreamingTranscriptEvent>(
        capacity = Channel.UNLIMITED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val lifecycleLock = Any()
    private enum class StreamState { IDLE, ACTIVE, STOPPING, STOPPED, CLOSED }
    private var streamState = StreamState.IDLE

    private val completedLines = mutableListOf<String>()
    @Volatile
    private var activeLineText = ""

    private val isRunning = AtomicBoolean(false)

    override fun connect(
        baseUrl: String,
        apiKey: String,
        model: String,
        language: String?
    ): Flow<StreamingTranscriptEvent> {
        synchronized(lifecycleLock) {
            completedLines.clear()
            activeLineText = ""
            streamState = StreamState.IDLE
        }
        isRunning.set(true)

        workerJob = transcriberScope.launch(Dispatchers.IO) {
            var engine: Transcriber? = null
            var streamHandle = -1
            var listener: Consumer<TranscriptEvent>? = null
            var wasResident = false
            var engineCreated = false
            try {
                JNI.ensureLibraryLoaded()
                // Try resident prewarmed Transcriber first — eliminates 1-2s load on hot mic path.
                val residentEngine = try {
                    MoonshineV2ResidentManager.getOrLoadResident(modelDir.absolutePath, modelArch)
                } catch (e: Throwable) {
                    Log.w(TAG, "Resident load failed, falling back to fresh", e)
                    null
                }
                // Straight-line ownership hand-off: capture whether the resident active-count was
                // incremented (wasResident) / a fresh engine was created (engineCreated) immediately,
                // with no suspension in between, so the teardown `finally` below always knows exactly
                // what it must release or close — even if setup aborts partway.
                if (residentEngine != null) {
                    engine = residentEngine
                    wasResident = true
                    Log.d(TAG, "Using resident Moonshine v2 Transcriber arch=$modelArch")
                } else {
                    engine = Transcriber()
                    engineCreated = true
                    Log.d(TAG, "Loading Moonshine v2 model from ${modelDir.absolutePath}, arch=$modelArch")
                    engine.loadFromFiles(modelDir.absolutePath, modelArch)
                }
                try { engine.setUpdateInterval(UPDATE_INTERVAL_SEC) } catch (_: Throwable) {}

                val l = Consumer<TranscriptEvent> { event ->
                    when (event) {
                        is TranscriptEvent.LineTextChanged -> {
                            val line = event.line
                            activeLineText = line.text ?: ""
                            val current = getCumulativeText()
                            eventChannel.trySend(StreamingTranscriptEvent.Partial(current))
                        }
                        is TranscriptEvent.LineCompleted -> {
                            val line = event.line
                            val text = line.text?.trim() ?: ""
                            synchronized(completedLines) {
                                if (text.isNotEmpty()) {
                                    completedLines.add(text)
                                }
                                activeLineText = ""
                            }
                            val current = getCumulativeText()
                            eventChannel.trySend(StreamingTranscriptEvent.Partial(current))
                        }
                        is TranscriptEvent.Error -> {
                            Log.e(TAG, "Moonshine v2 native error event")
                            eventChannel.trySend(
                                StreamingTranscriptEvent.Error(
                                    Exception("Moonshine v2 native error"),
                                    "Transcription error occurred"
                                )
                            )
                        }
                    }
                }
                listener = l

                synchronized(lifecycleLock) {
                    // Guard: if close() raced and already set CLOSED, don't attach listener or create a stream.
                    if (streamState == StreamState.CLOSED) {
                        Log.w(TAG, "connect: already closed, abort setup")
                        return@launch
                    }
                    try {
                        engine.addListener(l)
                    } catch (e: Throwable) {
                        Log.w(TAG, "addListener failed", e)
                    }

                    // Per-recording stream — fresh decoder state for each utterance.
                    val handle = try {
                        engine.createStream()
                    } catch (e: Throwable) {
                        Log.e(TAG, "createStream failed, falling back to default stream", e)
                        -1
                    }
                    streamHandle = handle
                    // If stopAndFinalize() already moved us to STOPPING while we were loading,
                    // leave it — teardown() will then run stopStream() to finalize. We still
                    // start the stream so any audio enqueued before the stop taps is decoded.
                    if (streamState != StreamState.STOPPING) {
                        streamState = StreamState.ACTIVE
                    }
                    try {
                        if (handle >= 0) {
                            engine.startStream(handle)
                        } else {
                            engine.start()
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "startStream failed", e)
                    }
                }
                Log.i(TAG, "Moonshine v2 streaming engine successfully started stream=$streamHandle")

                // Drain loop: iterate over channel until closed and empty.
                // Do NOT check isRunning in condition — we must deliver every queued frame
                // even after stopAndFinalize() closes the channel.
                for (pcmBytes in audioQueue) {
                    if (pcmBytes.isNotEmpty()) {
                        val sampleCount = pcmBytes.size / 2
                        val floatArray = FloatArray(sampleCount)
                        var i = 0
                        var j = 0
                        while (i < sampleCount) {
                            val low = pcmBytes[j].toInt() and 0xFF
                            val high = pcmBytes[j + 1].toInt()
                            val sample = (high shl 8) or low
                            floatArray[i] = (sample.toShort().toFloat() / 32768.0f).coerceIn(-1.0f, 1.0f)
                            i++
                            j += 2
                        }
                        try {
                            if (streamHandle >= 0) {
                                engine.addAudioToStream(streamHandle, floatArray, SAMPLE_RATE)
                            } else {
                                engine.addAudio(floatArray, SAMPLE_RATE)
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "addAudio failed during drain, stopping", e)
                            break
                        }
                    }
                }
                Log.d(TAG, "Audio drain loop finished, queue closed and empty")
            } catch (e: CancellationException) {
                // Preserve cancellation (e.g. close()): skip Error emission, still run teardown.
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize Moonshine v2 engine", e)
                eventChannel.trySend(
                    StreamingTranscriptEvent.Error(e, e.localizedMessage ?: "Engine initialization failed")
                )
            } finally {
                // Single-owner native teardown. Runs on this worker coroutine only, after the drain
                // loop has fully terminated, so it can never overlap an in-flight native call on the
                // same stream/transcriber. Wrapped in NonCancellable so that a close() cancellation
                // cannot skip resource cleanup or the resident active-count release.
                withContext(NonCancellable) {
                    teardown(engine, streamHandle, listener, wasResident, engineCreated)
                }
            }
        }

        return eventChannel.receiveAsFlow()
    }

    /**
     * All native resource teardown, executed only from the drain worker's `finally` (see [connect]).
     * Never called from stopAndFinalize()/close(), guaranteeing no native call can race an in-flight
     * addAudioToStream/stopStream on the same handle.
     *
     * Ordering matters: stopStream() is called while the listener is still attached so its synchronous
     * LineCompleted callbacks populate [completedLines] before the listener is removed and the stream freed.
     */
    private suspend fun teardown(
        engine: Transcriber?,
        streamHandle: Int,
        listener: Consumer<TranscriptEvent>?,
        wasResident: Boolean,
        engineCreated: Boolean
    ) {
        val stopRequested = synchronized(lifecycleLock) { streamState == StreamState.STOPPING }
        if (engine != null) {
            if (stopRequested) {
                try {
                    if (streamHandle >= 0) {
                        engine.stopStream(streamHandle)
                    } else {
                        engine.stop()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "stopStream failed in worker teardown", e)
                }
            }
            if (listener != null) {
                try {
                    engine.removeListener(listener)
                } catch (e: Throwable) {
                    Log.w(TAG, "removeListener failed", e)
                    try { engine.removeAllListeners() } catch (_: Throwable) {}
                }
            }
            if (streamHandle >= 0) {
                try {
                    engine.freeStream(streamHandle)
                } catch (e: Throwable) {
                    Log.w(TAG, "freeStream failed", e)
                }
            }
            if (wasResident) {
                try {
                    MoonshineV2ResidentManager.notifyStreamReleased(modelArch)
                } catch (_: Throwable) {
                }
            } else if (engineCreated) {
                try {
                    engine.close()
                } catch (e: Throwable) {
                    Log.w(TAG, "Error closing Moonshine engine", e)
                }
            }
        }
        isRunning.set(false)
        synchronized(lifecycleLock) {
            if (streamState != StreamState.CLOSED) streamState = StreamState.STOPPED
        }
    }

    private fun getCumulativeText(): String {
        synchronized(completedLines) {
            val sb = java.lang.StringBuilder()
            for (line in completedLines) {
                val t = line.trim()
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append(t)
                }
            }
            val active = activeLineText.trim()
            if (active.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(" ")
                sb.append(active)
            }
            return sb.toString()
        }
    }

    override fun sendAudioChunk(pcmData: ByteArray, length: Int) {
        if (length <= 0 || audioQueue.isClosedForSend) return
        if (!isRunning.get()) return
        val copy = pcmData.copyOf(length)
        audioQueue.trySend(copy)
    }

    override suspend fun stopAndFinalize() {
        val shouldProceed: Boolean
        synchronized(lifecycleLock) {
            when (streamState) {
                // ACTIVE is the normal stop. IDLE means the user stopped while the worker was
                // still loading (before it reached ACTIVE) — still proceed so we drain whatever
                // was queued up to the tap and emit a Final instead of silently dropping it.
                StreamState.ACTIVE, StreamState.IDLE -> {
                    streamState = StreamState.STOPPING
                    shouldProceed = true
                }
                else -> {
                    Log.w(TAG, "stopAndFinalize ignored, state=$streamState")
                    shouldProceed = false
                }
            }
        }
        if (!shouldProceed) return

        // Close queue first so worker loop drains remaining frames via for(pcm in queue).
        audioQueue.close()
        try {
            // The worker's finally (teardown) runs stopStream() synchronously when state is STOPPING,
            // driving LineCompleted listeners, then frees the stream. join() returns only after that.
            workerJob?.join()
        } catch (_: Throwable) {
        }

        val finalText = getCumulativeText().trim()
        Log.d(TAG, "Emitting final transcript: $finalText")
        eventChannel.trySend(StreamingTranscriptEvent.Final(finalText))
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (streamState == StreamState.CLOSED) {
                Log.d(TAG, "close ignored, already CLOSED")
                return
            }
            // The worker coroutine owns all native teardown (freeStream / engine.close() /
            // resident release) in its `finally` — see teardown(). We only cancel it and let the
            // finally run; we never touch native handles here, so there is no race with an in-flight
            // native call and no double-free (only the worker frees).
            streamState = StreamState.CLOSED
        }
        isRunning.set(false)
        audioQueue.close()
        workerJob?.cancel()
        workerJob = null
        eventChannel.trySend(StreamingTranscriptEvent.Closed)
    }
}
