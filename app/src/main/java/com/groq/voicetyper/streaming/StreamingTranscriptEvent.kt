package com.groq.voicetyper.streaming

/**
 * Normalized events emitted by a streaming transcription provider.
 */
sealed class StreamingTranscriptEvent {
    /**
     * Non-final preview transcript update.
     * @param cumulativeText Cumulative preview string for the current dictation session.
     */
    data class Partial(val cumulativeText: String) : StreamingTranscriptEvent()

    /**
     * Authoritative final transcript for the current utterance/session.
     * @param finalText Clean final transcript text ready for dictionary post-processing and insertion.
     */
    data class Final(val finalText: String) : StreamingTranscriptEvent()

    /**
     * Connection or provider error.
     * @param throwable Underlying exception cause.
     * @param message Human-readable error description.
     */
    data class Error(val throwable: Throwable, val message: String) : StreamingTranscriptEvent()

    /**
     * WebSocket connection cleanly closed.
     */
    object Closed : StreamingTranscriptEvent()
}
