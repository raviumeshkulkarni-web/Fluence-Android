package com.groq.voicetyper.sync.v1

/**
 * UTF-8 BYTE order comparison — the ONLY string ordering this package may use
 * for merge tiebreaks.
 *
 * TRAP: Kotlin's `String.compareTo` is UTF-16 code-unit order, which places a
 * supplementary-plane character (U+10000+, encoded as a surrogate pair whose
 * lead unit is 0xD800-0xDBFF) BELOW U+E000..U+FFFF. UTF-8 byte order — and
 * therefore Rust's `str::cmp` / `Ord on &[u8]` — places it ABOVE. The two
 * orders DISAGREE, so a `compareTo` here makes this platform pick a different
 * merge winner than Windows on exactly the tie these comparators exist to make
 * deterministic, which is permanent split-brain plus an endless re-upload loop.
 *
 * Honest clients mint ASCII UUID device ids, so this is latent — but it is the
 * same rule `Merge` already had to learn the hard way, and a second copy in the
 * same package is how the bug recurs. Length is the final tiebreak, matching
 * Rust's lexicographic `Ord` on `&[u8]` (equal prefix, shorter is smaller).
 */
internal fun compareUtf8(a: String, b: String): Int {
    val x = a.toByteArray(Charsets.UTF_8)
    val y = b.toByteArray(Charsets.UTF_8)
    val n = if (x.size < y.size) x.size else y.size
    for (i in 0 until n) {
        val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF)
        if (d != 0) return d
    }
    return x.size - y.size
}

/**
 * Total order over records by one or two STRING keys, compared in UTF-8 byte
 * order — the exact twin of Windows' `BTreeMap<K>` iteration, which is
 * lexicographic `Ord` on the key's bytes.
 *
 * Use this for every sort on a `businessKey` (and its `syncId` tiebreak).
 * `compareBy({ it.a }, { it.b })` and `sortedBy { it.key }` use Kotlin's
 * natural `String` ordering, which is UTF-16 code-unit order and DISAGREES with
 * UTF-8 for supplementary-plane characters. That divergence has two costs, both
 * permanent:
 *
 *  1. In `DomainSerializer` the sort decides the ORDER OF BYTES ON THE WIRE.
 *     The frozen contract mandates deterministic sorting, and Windows sorts the
 *     same envelope in UTF-8 order, so a single emoji in a `spoken` form makes
 *     the two platforms emit the same logical envelope as DIFFERENT BYTES.
 *  2. In `Merge` it decides the Rule-B residual tie and the merge-fold order,
 *     so the two platforms can pick different winners for a rename and then
 *     re-PUT each other's bytes indefinitely.
 *
 * ASCII-only keys (`day`, `eventId`, settings `key`) are provably unaffected
 * because they are validated as ASCII, and may keep the cheaper comparators.
 */
internal fun <T> utf8Comparator(first: (T) -> String, second: (T) -> String): Comparator<T> =
    Comparator { a, b ->
        val c = compareUtf8(first(a), first(b))
        if (c != 0) c else compareUtf8(second(a), second(b))
    }

internal fun <T> utf8Comparator(single: (T) -> String): Comparator<T> =
    Comparator { a, b -> compareUtf8(single(a), single(b)) }

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
        // UTF-8 byte order, NOT String.compareTo. See [compareUtf8].
        return compareUtf8(aDeviceId, bDeviceId)
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
        return compareUtf8(aValue, bValue)
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
        var ord = compareUtf8(aDay, bDay)
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
