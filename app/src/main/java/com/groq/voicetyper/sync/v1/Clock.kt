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
     * timestamps, while staying strictly below a genuinely held remote value.
     * Local monotonicity is preserved: as wall advances, the capped stamp
     * advances with it. A small legitimately-future maxSeen (accurate peer
     * clock) is not over-capped.
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

    fun isWinner(
        candidateUpdatedAt: Long, candidateDeviceId: String,
        currentUpdatedAt: Long, currentDeviceId: String
    ): Boolean {
        return compareWinner(candidateUpdatedAt, candidateDeviceId,
            currentUpdatedAt, currentDeviceId) > 0
    }
}
