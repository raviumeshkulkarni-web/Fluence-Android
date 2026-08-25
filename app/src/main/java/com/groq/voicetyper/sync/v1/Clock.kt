package com.groq.voicetyper.sync.v1

/**
 * Wall-clock UTC ms + maxSeen monotonic clock (frozen v1.2).
 * Winner ordering is pure LWW: max(updatedAt, deviceId). Tombstones are
 * ordinary records — they win exactly when they are newest, so a newer
 * re-creation legitimately beats an older deletion, and older remote state
 * can never resurrect over a newer local deletion.
 */
object Clock {
    fun nowWallMs(): Long = System.currentTimeMillis()

    /** Next updatedAt = max(wall, maxSeen + 1) */
    fun nextUpdatedAt(wallMs: Long, maxSeen: Long): Long {
        return maxOf(wallMs, maxSeen + 1)
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
