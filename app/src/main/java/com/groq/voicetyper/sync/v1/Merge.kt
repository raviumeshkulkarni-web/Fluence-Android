package com.groq.voicetyper.sync.v1

/**
 * Frozen v1.2 LWW merge: per business key the winner is the max of
 * (updatedAt, deviceId, syncId, canonical wire body) — see [pickDictionaryWinner].
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

    /**
     * RULE A level 4 — the per-business-key winner, larger canonical wire form
     * on a full `(updatedAt, deviceId, syncId)` tie.
     */
    fun pickDictionaryWinner(a: DictionaryRecord, b: DictionaryRecord): DictionaryRecord =
        if (pickOrder(a, b, a.updatedAt, a.deviceId, a.syncId, b.updatedAt, b.deviceId, b.syncId,
                DomainSerializer::canonicalDictionaryEntry) >= 0
        ) a else b

    fun pickSnippetWinner(a: SnippetRecord, b: SnippetRecord): SnippetRecord =
        if (pickOrder(a, b, a.updatedAt, a.deviceId, a.syncId, b.updatedAt, b.deviceId, b.syncId,
                DomainSerializer::canonicalSnippetEntry) >= 0
        ) a else b

    /** RULE B — collapse keeps levels 1-3 only (no content level). */
    private fun pickDictionaryByKeyOrder(a: DictionaryRecord, b: DictionaryRecord): DictionaryRecord =
        if (compareKeyed(a.updatedAt, a.deviceId, a.syncId, b.updatedAt, b.deviceId, b.syncId) >= 0) a else b

    private fun pickSnippetByKeyOrder(a: SnippetRecord, b: SnippetRecord): SnippetRecord =
        if (compareKeyed(a.updatedAt, a.deviceId, a.syncId, b.updatedAt, b.deviceId, b.syncId) >= 0) a else b

    /**
     * RULE A, all four levels. [canonical] MUST be the record's own frozen
     * wire body from `DomainSerializer` — the same writer `serialize*` emits
     * with — so level 4 can never drift from what actually goes on the wire.
     */
    private fun <T> pickOrder(
        a: T, b: T,
        aTime: Long, aDev: String, aSyncId: String,
        bTime: Long, bDev: String, bSyncId: String,
        canonical: (T) -> String
    ): Int {
        val keyOrder = compareKeyed(aTime, aDev, aSyncId, bTime, bDev, bSyncId)
        if (keyOrder != 0) return keyOrder
        return compareUtf8(canonical(a), canonical(b))
    }

/**
     * UTF-8 BYTE order is used throughout this package via the single
     * top-level `compareUtf8` in `Clock.kt` — NOT `String.compareTo`, which is
     * UTF-16 code-unit order and disagrees with Windows' `str::cmp` for
     * supplementary-plane characters. The private copy that used to live here
     * was deleted so the rule cannot drift in one file and not the other.
     */
    /** Pure LWW ordering: updatedAt first, deviceId (UTF-8 bytes) as tiebreak. */
    private fun compareWinner(aTime: Long, aDev: String, bTime: Long, bDev: String): Int {
        if (aTime != bTime) return if (aTime > bTime) 1 else -1
        return compareUtf8(aDev, bDev)
    }

    /**
     * LWW for keyed domains (dictionary, snippets): `updatedAt` → `deviceId`
     * → `syncId`, levels 1-3 of the RULE A order.
     *
     * [compareWinner] alone is not a total order: two records can share both
     * `updatedAt` and `deviceId` while differing in payload. A partial order
     * makes the merge outcome depend on which side a record arrived from, so two
     * devices holding different payloads for the same key each keep their own
     * copy and re-push to each other indefinitely. `syncId` is stable, present
     * on every record, and identical on both platforms and both sides of the
     * wire, so it is a safe final discriminator.
     *
     * Mirrors Windows `clock::cmp_keyed_winner`, levels 1-3. Both string
     * levels compare UTF-8 BYTES ([compareUtf8]), not UTF-16 code units.
     */
    private fun compareKeyed(
        aTime: Long, aDev: String, aSyncId: String,
        bTime: Long, bDev: String, bSyncId: String
    ): Int {
        val primary = compareWinner(aTime, aDev, bTime, bDev)
        if (primary != 0) return primary
        return compareUtf8(aSyncId, bSyncId)
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
     * implement levels 1-3 of the record type's RULE A order.
     *
     * RULE B — NO level 4 here, deliberately. Cross-key partners differ in
     * businessKey by construction, and Windows collapses on the key order
     * only; adding the content level would let the two platforms collapse a
     * rename to different records.
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
        if (pickOrder(a, b,
                a.updatedAt, a.deviceId, a.syncId,
                b.updatedAt, b.deviceId, b.syncId,
                DomainSerializer::canonicalAgentEntry) >= 0
        ) a else b

    private fun pickStyleWinner(a: StyleRecord, b: StyleRecord): StyleRecord =
        if (pickOrder(a, b,
                a.updatedAt, a.deviceId, a.syncId,
                b.updatedAt, b.deviceId, b.syncId,
                DomainSerializer::canonicalStyleEntry) >= 0
        ) a else b

    /** RULE B — agents/styles collapse on levels 1-3 only. */
    private fun pickAgentByKeyOrder(a: AgentRecord, b: AgentRecord): AgentRecord =
        if (compareKeyed(a.updatedAt, a.deviceId, a.syncId, b.updatedAt, b.deviceId, b.syncId) >= 0) a else b

    private fun pickStyleByKeyOrder(a: StyleRecord, b: StyleRecord): StyleRecord =
        if (compareKeyed(a.updatedAt, a.deviceId, a.syncId, b.updatedAt, b.deviceId, b.syncId) >= 0) a else b

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
        val ordered = map.values.sortedWith(utf8Comparator({ it.businessKey }, { it.syncId }))
        return collapseBySyncId(ordered, { it.syncId }, { a, b -> pickDictionaryByKeyOrder(a, b) })
            .sortedWith(utf8Comparator({ it.businessKey }, { it.syncId }))
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
        val ordered = map.values.sortedWith(utf8Comparator({ it.businessKey }, { it.syncId }))
        return collapseBySyncId(ordered, { it.syncId }, { a, b -> pickSnippetByKeyOrder(a, b) })
            .sortedWith(utf8Comparator({ it.businessKey }, { it.syncId }))
    }

    /**
     * Phase 6 — Agents/Styles reuse the keyed RULE A order verbatim.
     *
     * There is deliberately NO agent/style-specific merge rule: the keyed LWW
     * order (updatedAt → deviceId → syncId → canonical wire body) is the single
     * total order for every keyed domain, so a duplicate agent id cannot resolve
     * differently from a duplicate dictionary key. Tombstones ride the same
     * comparator — they are ordinary records and win exactly when newest.
     */
    fun mergeAgents(local: List<AgentRecord>, remote: List<AgentRecord>): List<AgentRecord> {
        val map = mutableMapOf<String, AgentRecord>()
        (local + remote).forEach { rec ->
            val existing = map[rec.businessKey]
            map[rec.businessKey] = if (existing == null) rec else pickAgentWinner(existing, rec)
        }
        // Pre-sorted so a residual full tie keeps the smallest business key
        // (Windows parity — see collapseBySyncId).
        val ordered = map.values.sortedWith(utf8Comparator({ it.businessKey }, { it.syncId }))
        return collapseBySyncId(ordered, { it.syncId }, { a, b -> pickAgentByKeyOrder(a, b) })
            .sortedWith(utf8Comparator({ it.businessKey }, { it.syncId }))
    }

    fun mergeStyles(local: List<StyleRecord>, remote: List<StyleRecord>): List<StyleRecord> {
        val map = mutableMapOf<String, StyleRecord>()
        (local + remote).forEach { rec ->
            val existing = map[rec.businessKey]
            map[rec.businessKey] = if (existing == null) rec else pickStyleWinner(existing, rec)
        }
        // Pre-sorted so a residual full tie keeps the smallest business key
        // (Windows parity — see collapseBySyncId).
        val ordered = map.values.sortedWith(utf8Comparator({ it.businessKey }, { it.syncId }))
        return collapseBySyncId(ordered, { it.syncId }, { a, b -> pickStyleByKeyOrder(a, b) })
            .sortedWith(utf8Comparator({ it.businessKey }, { it.syncId }))
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
