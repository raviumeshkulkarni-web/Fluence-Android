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
        if (compareKeyed(a.updatedAt, a.deviceId, a.syncId, b.updatedAt, b.deviceId, b.syncId) >= 0) a else b

    fun pickSnippetWinner(a: SnippetRecord, b: SnippetRecord): SnippetRecord =
        if (compareKeyed(a.updatedAt, a.deviceId, a.syncId, b.updatedAt, b.deviceId, b.syncId) >= 0) a else b

    fun pickSettingsWinner(a: SettingsRecord, b: SettingsRecord): SettingsRecord =
        if (compareWinner(a.updatedAt, a.deviceId, b.updatedAt, b.deviceId) >= 0) a else b

    /** Pure LWW ordering: updatedAt first, deviceId as deterministic tiebreak. */
    private fun compareWinner(aTime: Long, aDev: String, bTime: Long, bDev: String): Int {
        if (aTime != bTime) return if (aTime > bTime) 1 else -1
        return aDev.compareTo(bDev)
    }

    /**
     * LWW for keyed domains (dictionary, snippets) with a final `syncId`
     * tiebreak, making the order *total*.
     *
     * [compareWinner] alone is not a total order: two records can share both
     * `updatedAt` and `deviceId` while differing in payload. A partial order
     * makes the merge outcome depend on which side a record arrived from, so two
     * devices holding different payloads for the same key each keep their own
     * copy and re-push to each other indefinitely. `syncId` is stable, present
     * on every record, and identical on both platforms and both sides of the
     * wire, so it is a safe final discriminator.
     *
     * Mirrors Windows `clock::cmp_keyed_winner`.
     */
    private fun compareKeyed(
        aTime: Long, aDev: String, aSyncId: String,
        bTime: Long, bDev: String, bSyncId: String
    ): Int {
        val primary = compareWinner(aTime, aDev, bTime, bDev)
        if (primary != 0) return primary
        return aSyncId.compareTo(bSyncId)
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

    /**
     * Phase 6 — Agents/Styles reuse [compareKeyed] verbatim.
     *
     * There is deliberately NO agent/style-specific merge rule: 2B LWW
     * (updatedAt → deviceId → syncId) is the single total order for every keyed
     * domain, so a duplicate agent id cannot resolve differently from a duplicate
     * dictionary key. Tombstones ride the same comparator — they are ordinary
     * records and win exactly when newest.
     */
    fun mergeAgents(local: List<AgentRecord>, remote: List<AgentRecord>): List<AgentRecord> {
        val map = mutableMapOf<String, AgentRecord>()
        (local + remote).forEach { rec ->
            val existing = map[rec.businessKey]
            map[rec.businessKey] = if (existing == null) rec
            else if (compareKeyed(
                    existing.updatedAt, existing.deviceId, existing.syncId,
                    rec.updatedAt, rec.deviceId, rec.syncId
                ) >= 0
            ) existing else rec
        }
        return map.values.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
    }

    fun mergeStyles(local: List<StyleRecord>, remote: List<StyleRecord>): List<StyleRecord> {
        val map = mutableMapOf<String, StyleRecord>()
        (local + remote).forEach { rec ->
            val existing = map[rec.businessKey]
            map[rec.businessKey] = if (existing == null) rec
            else if (compareKeyed(
                    existing.updatedAt, existing.deviceId, existing.syncId,
                    rec.updatedAt, rec.deviceId, rec.syncId
                ) >= 0
            ) existing else rec
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
                val winner = if (compareWinner(existing.updatedAt, existing.deviceId, rec.updatedAt, rec.deviceId) >= 0) {
                    existing
                } else {
                    rec
                }
                map[rec.eventId] = winner
            }
        }
        return map.values.sortedWith(compareBy({ it.day }, { it.eventId }))
    }

}
