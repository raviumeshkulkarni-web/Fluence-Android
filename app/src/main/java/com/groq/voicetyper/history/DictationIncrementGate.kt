package com.groq.voicetyper.history

/** Part C/V4: a dictation's counters may be applied to `stats_daily` only when
 *  the increment row is newly inserted (UUID not already recorded), so re-runs
 *  or re-imports of the same dictation never double-count. */
internal object DictationIncrementGate {
    fun shouldApply(exists: Boolean, insertRowId: Long): Boolean = !exists && insertRowId != -1L
}
