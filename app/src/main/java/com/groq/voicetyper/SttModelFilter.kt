package com.groq.voicetyper

/**
 * Speech-to-text picker filtering, Windows parity.
 *
 * Mirrors src-tauri/src/transcribe.rs (ASR_MODEL_MARKERS, is_asr_model_id,
 * apply_stt_model_filter): the transcription picker only offers ids that look
 * like speech models, across every provider. Batch and streaming both pass,
 * since the markers match families (whisper, voxtral, stt) rather than modes.
 * Chat ids (llama-*, gpt-4o, mistral-large-*) match nothing, so the picker
 * can never offer them. Unknown ids fail closed to false. This only gates
 * what the picker offers. Saving any configured id still works.
 */
object SttModelFilter {
    val ASR_MODEL_MARKERS = listOf(
        "whisper",
        "voxtral",
        "transcribe",
        "stt",
        "scribe",
        "asr",
        "speech",
        "parakeet",
        "canary",
        "moonshine",
        "sensevoice",
        "chirp",
        "nova"
    )

    fun isAsrModelId(id: String): Boolean {
        val lower = id.lowercase()
        return ASR_MODEL_MARKERS.any { lower.contains(it) }
    }

    /**
     * Filtered list for the transcription picker. [keep] (usually the saved
     * model) is always included, so fetching can never strand a selection.
     */
    fun applyFilter(ids: List<String>, keep: String?): List<String> {
        val filtered = ids.filter { isAsrModelId(it) }
        val trimmed = keep?.trim().orEmpty()
        return if (trimmed.isNotEmpty() && filtered.none { it == trimmed }) {
            filtered + trimmed
        } else {
            filtered
        }
    }
}
