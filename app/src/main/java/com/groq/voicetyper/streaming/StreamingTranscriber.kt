package com.groq.voicetyper.streaming

import kotlinx.coroutines.flow.Flow

/**
 * Pluggable interface for real-time online streaming speech-to-text providers.
 */
interface StreamingTranscriber {
    /**
     * Establishes a real-time streaming connection to the STT provider.
     * Returns a Flow emitting normalized [StreamingTranscriptEvent] items.
     */
    fun connect(
        baseUrl: String,
        apiKey: String,
        model: String,
        language: String? = null
    ): Flow<StreamingTranscriptEvent>

    /**
     * Sends a raw 16-bit PCM audio chunk over the active stream.
     */
    fun sendAudioChunk(pcmData: ByteArray, length: Int)

    /**
     * Signals end-of-audio to the provider and awaits final transcript.
     */
    suspend fun stopAndFinalize()

    /**
     * Immediately closes and cancels the connection/socket without waiting.
     */
    fun close()
}
