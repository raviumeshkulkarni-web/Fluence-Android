package com.groq.voicetyper.sync.v1

/**
 * Wall-clock UTC ms + maxSeen monotonic clock (frozen v1.2).
 * Winner ordering is pure LWW: max(updatedAt, deviceId). Tombstones are
 * ordinary records — they win exactly when they are newest, so a newer
 * re-creation legitimately beats an older deletion, and older remote state
 * can never resurrect over a newer local deletion.
 */
object Clock {
    /**
     * Upper bound on how far a locally-stamped updatedAt may run ahead of real
     * wall time (15 minutes). Without this cap, a wall-clock jump into the
     * future (user/NTP/clock drift) makes maxSeen sticky: every subsequent
     * local edit is stamped with an absurd updatedAt that permanently outranks
     * legitimate remote state, wedging LWW convergence on every device.
     */
    const val MAX_CLOCK_SKEW_MS = 900_000L

    fun nowWallMs(): Long = System.currentTimeMillis()

    /**
     * Next updatedAt = max(wall, maxSeen + 1), capped at wall + MAX_CLOCK_SKEW_MS.
     *
     * The cap stops a runaway local clock from manufacturing forever-dominant
     * timestamps. A small legitimately-future maxSeen is NOT capped away: when
     * `maxSeen + 1 <= wall + MAX_CLOCK_SKEW_MS` the stamp is `maxSeen + 1`,
     * i.e. it stays strictly ABOVE the held remote value and keeps local
     * monotonicity. Only a runaway floor (beyond `wall + MAX_CLOCK_SKEW_MS`)
     * is pulled down to the cap. Local monotonicity is preserved: as wall
     * advances, the capped stamp advances with it. A small legitimately-future
     * maxSeen (accurate peer clock) is not over-capped.
     */
    fun nextUpdatedAt(wallMs: Long, maxSeen: Long): Long {
        return minOf(maxOf(wallMs, maxSeen + 1), wallMs + MAX_CLOCK_SKEW_MS)
    }

    /** Compare two records by (updatedAt, deviceId) lexicographically max. */
    fun compareWinner(
        aUpdatedAt: Long, aDeviceId: String,
        bUpdatedAt: Long, bDeviceId: String
    ): Int {
        if (aUpdatedAt != bUpdatedAt) return if (aUpdatedAt > bUpdatedAt) 1 else -1
        return aDeviceId.compareTo(bDeviceId)
    }

    /**
     * LWW for the settings domain with a final `value` tiebreak, making the
     * order *total* (audit H2, coordinated with Windows
     * `clock::cmp_settings_winner`).
     *
     * [compareWinner] alone leaves a full tie — same key, same `updatedAt`,
     * same `deviceId`, different `value` — to merge side/fold order, so two
     * devices each keep their own value and re-push forever. `value` is
     * stable, present on every record, and wire-identical on both platforms.
     * Larger wins at every level, matching the `deviceId` convention. Callers
     * pass *effective* times (adoption sentinel already mapped); identical
     * rows still compare 0, preserving local-first fold behavior.
     */
    fun compareSettingsWinner(
        aUpdatedAt: Long, aDeviceId: String, aValue: String,
        bUpdatedAt: Long, bDeviceId: String, bValue: String
    ): Int {
        val primary = compareWinner(aUpdatedAt, aDeviceId, bUpdatedAt, bDeviceId)
        if (primary != 0) return primary
        return aValue.compareTo(bValue)
    }

    /**
     * LWW for stats records with final payload tiebreaks, making the order
     * *total* (audit H2, coordinated with Windows `clock::cmp_stats_winner`).
     *
     * Discriminator order after `(updatedAt, deviceId)`: `day`, then
     * `timestampMs`, `wordCount`, `chars`, `durationMs` — every field present
     * on both platforms and wire-identical. Larger wins at every level.
     * Identical rows still compare 0, so fold order (local-first) keeps
     * deciding true duplicates and the upload tie-guard is unaffected.
     */
    fun compareStatsWinner(
        aUpdatedAt: Long, aDeviceId: String,
        aDay: String, aTimestampMs: Long, aWords: Long, aChars: Long, aDurationMs: Long,
        bUpdatedAt: Long, bDeviceId: String,
        bDay: String, bTimestampMs: Long, bWords: Long, bChars: Long, bDurationMs: Long
    ): Int {
        val primary = compareWinner(aUpdatedAt, aDeviceId, bUpdatedAt, bDeviceId)
        if (primary != 0) return primary
        var ord = aDay.compareTo(bDay)
        if (ord != 0) return ord
        ord = aTimestampMs.compareTo(bTimestampMs)
        if (ord != 0) return ord
        ord = aWords.compareTo(bWords)
        if (ord != 0) return ord
        ord = aChars.compareTo(bChars)
        if (ord != 0) return ord
        return aDurationMs.compareTo(bDurationMs)
    }

    fun isWinner(
        candidateUpdatedAt: Long, candidateDeviceId: String,
        currentUpdatedAt: Long, currentDeviceId: String
    ): Boolean {
        return compareWinner(candidateUpdatedAt, candidateDeviceId,
            currentUpdatedAt, currentDeviceId) > 0
    }
}
