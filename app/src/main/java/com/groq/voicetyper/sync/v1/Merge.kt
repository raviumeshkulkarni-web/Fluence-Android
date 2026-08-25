package com.groq.voicetyper.sync.v1

/**
 * Frozen v1.2 LWW merge: winner = max(updatedAt, deviceId).
 *
 * A tombstone is an ordinary state transition — it wins exactly when it is
 * the newest record for a business key. This makes delete/re-create symmetric:
 * - A newer deletion beats an older live record (no resurrection of stale data).
 * - A newer edit or re-creation beats an older tombstone (deleting a word and
 *   later adding it again works).
 *
 * - Null→stamped atomic: local NULL accountHash rows are stamped to the current
 *   accountHash in the same TX before first PUT.
 * - isEnabled is distinct from deleted: isEnabled=false ≠ tombstone.
 * - expansion/corrected byte-exact: no normalization beyond businessKey.
 */
object Merge {

    fun pickDictionaryWinner(a: DictionaryRecord, b: DictionaryRecord): DictionaryRecord =
        if (compareWinner(a.updatedAt, a.deviceId, b.updatedAt, b.deviceId) >= 0) a else b

    fun pickSnippetWinner(a: SnippetRecord, b: SnippetRecord): SnippetRecord =
        if (compareWinner(a.updatedAt, a.deviceId, b.updatedAt, b.deviceId) >= 0) a else b

    fun pickSettingsWinner(a: SettingsRecord, b: SettingsRecord): SettingsRecord =
        if (compareWinner(a.updatedAt, a.deviceId, b.updatedAt, b.deviceId) >= 0) a else b

    /** Pure LWW ordering: updatedAt first, deviceId as deterministic tiebreak. */
    private fun compareWinner(aTime: Long, aDev: String, bTime: Long, bDev: String): Int {
        if (aTime != bTime) return if (aTime > bTime) 1 else -1
        return aDev.compareTo(bDev)
    }

    /** Merge local + remote dictionaries: one winner per businessKey */
    fun mergeDictionaries(local: List<DictionaryRecord>, remote: List<DictionaryRecord>): List<DictionaryRecord> {
        val map = mutableMapOf<String, DictionaryRecord>()
        (local + remote).forEach { rec ->
            val key = rec.businessKey
            val existing = map[key]
            map[key] = if (existing == null) rec else pickDictionaryWinner(existing, rec)
        }
        return map.values.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
    }

    fun mergeSnippets(local: List<SnippetRecord>, remote: List<SnippetRecord>): List<SnippetRecord> {
        val map = mutableMapOf<String, SnippetRecord>()
        (local + remote).forEach { rec ->
            val key = rec.businessKey
            val existing = map[key]
            map[key] = if (existing == null) rec else pickSnippetWinner(existing, rec)
        }
        return map.values.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
    }

    private fun effectiveSettingsTime(t: Long): Long = if (t == 1700000000000L) 0L else t

    /** Settings: per-key LWW over the frozen five keys only (others ignored) */
    fun mergeSettings(local: List<SettingsRecord>, remote: List<SettingsRecord>): List<SettingsRecord> {
        val combined = mutableMapOf<String, SettingsRecord>()
        (local + remote).forEach { rec ->
            if (rec.key !in SettingsRecord.ALLOWED_KEYS) return@forEach
            val existing = combined[rec.key]
            combined[rec.key] = if (existing == null) rec else {
                val eTime = effectiveSettingsTime(existing.updatedAt)
                val rTime = effectiveSettingsTime(rec.updatedAt)
                val winner = when {
                    eTime == 0L && rTime != 0L -> rec
                    rTime == 0L && eTime != 0L -> existing
                    else -> pickSettingsWinner(existing.copy(updatedAt = eTime), rec.copy(updatedAt = rTime)).let { if (it == existing) existing else rec }
                }
                winner
            }
        }
        return combined.values.map { rec ->
            if (rec.updatedAt == 0L) rec.copy(updatedAt = 1700000000000L) else rec
        }.sortedBy { it.key }
    }

    /** Stats: union dedup by eventId — totals are summed at display time. */
    fun mergeStats(local: List<StatRecord>, remote: List<StatRecord>): List<StatRecord> {
        val map = mutableMapOf<String, StatRecord>()
        (local + remote).forEach { rec ->
            val existing = map[rec.eventId]
            if (existing == null) map[rec.eventId] = rec
            else {
                val winner = when {
                    rec.updatedAt != existing.updatedAt -> if (rec.updatedAt > existing.updatedAt) rec else existing
                    rec.deviceId < existing.deviceId -> rec
                    else -> existing
                }
                map[rec.eventId] = winner
            }
        }
        return map.values.sortedWith(compareBy({ it.day }, { it.eventId }))
    }
}
