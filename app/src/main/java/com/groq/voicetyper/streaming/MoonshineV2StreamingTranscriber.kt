package com.groq.voicetyper.streaming

import ai.moonshine.voice.JNI
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.TranscriptEvent
import android.util.Log
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
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
 */
class MoonshineV2StreamingTranscriber(
    private val modelDir: File,
    private val modelArch: Int
) : StreamingTranscriber {

    companion object {
        private const val TAG = "MoonshineV2Transcriber"
        private const val SAMPLE_RATE = 16000
    }

    private val transcriberScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var workerJob: Job? = null

    private val audioQueue = Channel<ByteArray>(
        capacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val eventChannel = Channel<StreamingTranscriptEvent>(
        capacity = Channel.UNLIMITED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @Volatile
    private var transcriber: Transcriber? = null
    private val completedLines = mutableListOf<String>()
    @Volatile
    private var activeLineText = ""

    private val isRunning = AtomicBoolean(false)
    private val loadGate = CompletableDeferred<Unit>()

    override fun connect(
        baseUrl: String,
        apiKey: String,
        model: String,
        language: String?
    ): Flow<StreamingTranscriptEvent> {
        completedLines.clear()
        activeLineText = ""
        isRunning.set(true)

        workerJob = transcriberScope.launch(Dispatchers.IO) {
            try {
                JNI.ensureLibraryLoaded()
                val engine = Transcriber()
                Log.d(TAG, "Loading Moonshine v2 model from ${modelDir.absolutePath}, arch=$modelArch")
                engine.loadFromFiles(modelDir.absolutePath, modelArch)

                engine.addListener { event ->
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

                engine.start()
                transcriber = engine
                loadGate.complete(Unit)
                Log.i(TAG, "Moonshine v2 streaming engine successfully started")

                // Audio processing loop
                while (isRunning.get()) {
                    val pcmBytes = audioQueue.receiveCatching().getOrNull() ?: break
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
                        engine.addAudio(floatArray, SAMPLE_RATE)
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize Moonshine v2 engine", e)
                loadGate.completeExceptionally(e)
                eventChannel.trySend(
                    StreamingTranscriptEvent.Error(e, e.localizedMessage ?: "Engine initialization failed")
                )
            }
        }

        return eventChannel.receiveAsFlow()
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
        if (!isRunning.get() || length <= 0 || audioQueue.isClosedForSend) return
        val copy = pcmData.copyOf(length)
        audioQueue.trySend(copy)
    }

    override suspend fun stopAndFinalize() {
        isRunning.set(false)
        audioQueue.close()

        withContext(Dispatchers.IO) {
            try {
                // Await model load if stop occurred rapidly
                if (loadGate.isCompleted && !loadGate.isCancelled) {
                    val engine = transcriber
                    engine?.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping Moonshine engine", e)
            }
            val finalText = getCumulativeText().trim()
            Log.d(TAG, "Emitting final transcript: $finalText")
            eventChannel.trySend(StreamingTranscriptEvent.Final(finalText))
        }
    }

    override fun close() {
        isRunning.set(false)
        audioQueue.close()
        workerJob?.cancel()
        workerJob = null

        val engine = transcriber
        transcriber = null
        if (engine != null) {
            transcriberScope.launch(Dispatchers.IO) {
                try {
                    engine.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing Moonshine engine", e)
                }
            }
        }
        eventChannel.trySend(StreamingTranscriptEvent.Closed)
    }
}
