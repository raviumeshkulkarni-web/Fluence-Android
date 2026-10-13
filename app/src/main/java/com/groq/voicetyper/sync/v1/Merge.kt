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

    /**
     * Cross-key syncId collapse (audit finding H3 / Phase D).
     *
     * Mirrors the second half of Windows `merge_keyed`: a rename moves a
     * record's business key while keeping its syncId, so the pre-rename key
     * (usually remote) and the post-rename key (local) each contribute a
     * winner for the SAME logical record. Without this step both survive
     * every future merge as a permanent duplicate. Collapse to the
     * [compareKeyed] max — the same total order as the per-key pick — so the
     * outcome is independent of merge side/order. Tombstones ride the same
     * comparator, preserving delete-vs-edit semantics across keys: the newest
     * record wins regardless of which key it sits under. [pick] must
     * implement the per-key winner rule for the record type.
     *
     * Residual-tie determinism: inside one syncId group the syncId tiebreak
     * is inert, reducing the order to (updatedAt, deviceId). Winners are
     * pre-sorted by (businessKey, syncId) at every call site, so a genuine
     * full tie keeps the smallest business key — the same record Windows
     * keeps, whose collapse iterates BTreeMap (ascending-key) order.
     */
    private fun <T> collapseBySyncId(
        winners: Collection<T>,
        syncIdOf: (T) -> String,
        pick: (T, T) -> T
    ): List<T> {
        val byId = LinkedHashMap<String, T>()
        for (rec in winners) {
            val id = syncIdOf(rec)
            val incumbent = byId[id]
            byId[id] = if (incumbent == null) rec else pick(incumbent, rec)
        }
        return byId.values.toList()
    }

    private fun pickAgentWinner(a: AgentRecord, b: AgentRecord): AgentRecord =
        if (compareKeyed(
                a.updatedAt, a.deviceId, a.syncId,
                b.updatedAt, b.deviceId, b.syncId
            ) >= 0
        ) a else b

    private fun pickStyleWinner(a: StyleRecord, b: StyleRecord): StyleRecord =
        if (compareKeyed(
                a.updatedAt, a.deviceId, a.syncId,
                b.updatedAt, b.deviceId, b.syncId
            ) >= 0
        ) a else b

    /** Merge local + remote dictionaries: one winner per businessKey */
    fun mergeDictionaries(local: List<DictionaryRecord>, remote: List<DictionaryRecord>): List<DictionaryRecord> {
        val map = mutableMapOf<String, DictionaryRecord>()
        (local + remote).forEach { rec ->
            val key = rec.businessKey
            val existing = map[key]
            map[key] = if (existing == null) rec else pickDictionaryWinner(existing, rec)
        }
        // Pre-sorted so a residual full tie keeps the smallest business key
        // (Windows parity — see collapseBySyncId).
        val ordered = map.values.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
        return collapseBySyncId(ordered, { it.syncId }, { a, b -> pickDictionaryWinner(a, b) })
            .sortedWith(compareBy({ it.businessKey }, { it.syncId }))
    }

    fun mergeSnippets(local: List<SnippetRecord>, remote: List<SnippetRecord>): List<SnippetRecord> {
        val map = mutableMapOf<String, SnippetRecord>()
        (local + remote).forEach { rec ->
            val key = rec.businessKey
            val existing = map[key]
            map[key] = if (existing == null) rec else pickSnippetWinner(existing, rec)
        }
        // Pre-sorted so a residual full tie keeps the smallest business key
        // (Windows parity — see collapseBySyncId).
        val ordered = map.values.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
        return collapseBySyncId(ordered, { it.syncId }, { a, b -> pickSnippetWinner(a, b) })
            .sortedWith(compareBy({ it.businessKey }, { it.syncId }))
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
            map[rec.businessKey] = if (existing == null) rec else pickAgentWinner(existing, rec)
        }
        // Pre-sorted so a residual full tie keeps the smallest business key
        // (Windows parity — see collapseBySyncId).
        val ordered = map.values.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
        return collapseBySyncId(ordered, { it.syncId }, { a, b -> pickAgentWinner(a, b) })
            .sortedWith(compareBy({ it.businessKey }, { it.syncId }))
    }

    fun mergeStyles(local: List<StyleRecord>, remote: List<StyleRecord>): List<StyleRecord> {
        val map = mutableMapOf<String, StyleRecord>()
        (local + remote).forEach { rec ->
            val existing = map[rec.businessKey]
            map[rec.businessKey] = if (existing == null) rec else pickStyleWinner(existing, rec)
        }
        // Pre-sorted so a residual full tie keeps the smallest business key
        // (Windows parity — see collapseBySyncId).
        val ordered = map.values.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
        return collapseBySyncId(ordered, { it.syncId }, { a, b -> pickStyleWinner(a, b) })
            .sortedWith(compareBy({ it.businessKey }, { it.syncId }))
    }

    private fun effectiveSettingsTime(t: Long): Long = if (t == 1700000000000L) 0L else t

    /** Settings: per-key LWW over the frozen five keys only (others ignored).
     *  Full ties resolve by value, larger wins (H2: total order coordinated
     *  with Windows `merge_settings`, so both platforms pick the same winner
     *  when updatedAt and deviceId tie); output is canonically sorted by key,
     *  matching Windows exactly.
     *
     *  The comparison runs on effective times directly and returns the
     *  original records — no copies — so a local win always keeps the local
     *  record (a copy-based comparison could mistake the winner on the
     *  sentinel path, where the effective time differs from the stored one).
     *  On a full tie the existing (local-first) record is kept, matching
     *  Windows' strict-Greater replacement. */
    fun mergeSettings(local: List<SettingsRecord>, remote: List<SettingsRecord>): List<SettingsRecord> {
        val combined = mutableMapOf<String, SettingsRecord>()
        (local + remote).forEach { rec ->
            if (rec.key !in SettingsRecord.ALLOWED_KEYS) return@forEach
            val existing = combined[rec.key]
            combined[rec.key] = if (existing == null) rec else {
                val eTime = effectiveSettingsTime(existing.updatedAt)
                val rTime = effectiveSettingsTime(rec.updatedAt)
                when {
                    eTime == 0L && rTime != 0L -> rec
                    rTime == 0L && eTime != 0L -> existing
                    else -> if (Clock.compareSettingsWinner(
                            rTime, rec.deviceId, rec.value,
                            eTime, existing.deviceId, existing.value
                        ) > 0
                    ) rec else existing
                }
            }
        }
        return combined.values.map { rec ->
            if (rec.updatedAt == 0L) rec.copy(updatedAt = 1700000000000L) else rec
        }.sortedBy { it.key }
    }

    /** Stats: union dedup by eventId — totals are summed at display time.
     *  Full ties resolve by (day, timestampMs, wordCount, chars,
     *  durationMs), larger wins (H2: total order coordinated with Windows
     *  `merge_stats`); identical rows still tie so fold order keeps deciding
     *  true duplicates. Output is canonically sorted by (day, eventId),
     *  matching Windows. */
    fun mergeStats(local: List<StatRecord>, remote: List<StatRecord>): List<StatRecord> {
        val map = mutableMapOf<String, StatRecord>()
        (local + remote).forEach { rec ->
            val existing = map[rec.eventId]
            if (existing == null) map[rec.eventId] = rec
            else {
                val winner = if (Clock.compareStatsWinner(
                        rec.updatedAt, rec.deviceId,
                        rec.day, rec.timestampMs, rec.wordCount.toLong(), rec.chars.toLong(), rec.durationMs,
                        existing.updatedAt, existing.deviceId,
                        existing.day, existing.timestampMs, existing.wordCount.toLong(), existing.chars.toLong(), existing.durationMs
                    ) > 0
                ) {
                    rec
                } else {
                    existing
                }
                map[rec.eventId] = winner
            }
        }
        return map.values.sortedWith(compareBy({ it.day }, { it.eventId }))
    }

}
