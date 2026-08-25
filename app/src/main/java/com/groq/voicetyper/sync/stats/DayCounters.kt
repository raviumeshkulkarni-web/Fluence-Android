package com.groq.voicetyper.sync.stats

/**
 * Minimal DayCounters kept for HomeScreen/history after legacy stats stack deletion.
 * The v1.2 stats ledger lives under sync/v1, this class only carries the UI-facing counters.
 */
data class DayCounters(
    val words: Long = 0L,
    val count: Long = 0L,
    val chars: Long = 0L,
    val ms: Long = 0L,
)
