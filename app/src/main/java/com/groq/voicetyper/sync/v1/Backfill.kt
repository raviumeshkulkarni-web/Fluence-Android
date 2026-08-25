package com.groq.voicetyper.sync.v1

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Stats backfill — once per accountHash (frozen v1.1, amendment #1).
 *
 * Single source priority: live `transcription_history` rows when any exist,
 * else the `stats_daily` aggregate fallback. UTC day bucketing everywhere.
 * eventIds are deterministic UUIDv5 (SHA-1, RFC 4122) derived from
 * day+accountHash+index so a re-run after a crash reproduces identical ids
 * and the union dedup absorbs them.
 */
object Backfill {

    fun utcDayOf(timestampMs: Long): String {
        return Instant.ofEpochMilli(timestampMs).atZone(ZoneOffset.UTC)
            .toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    fun eventIdForDictation(dictationSyncId: String): String =
        UUID.nameUUIDFromBytes("fluence-stat-v1:$dictationSyncId".toByteArray()).toString()

    /** Deterministic UUIDv5-style id (SHA-1, version/variant bits set). */
    fun eventIdFor(day: String, accountHash: String, index: Int): String =
        uuidV5("fluence-stats-backfill", "$day:$accountHash:$index")

    fun uuidV5(namespace: String, name: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        digest.update(namespace.toByteArray(Charsets.UTF_8))
        digest.update(0x00) // namespace/name separator per RFC 4122 §5
        digest.update(name.toByteArray(Charsets.UTF_8))
        val bytes = digest.digest()
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x".format(
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5],
            bytes[6], bytes[7],
            bytes[8], bytes[9],
            bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
        )
    }

    /** Generate StatRecords from live transcription rows (single source). */
    fun fromTranscriptionRows(
        rows: List<TranscriptionRowLite>,
        accountHash: String,
        deviceId: String,
        now: Long
    ): List<StatRecord> {
        if (rows.isEmpty()) return emptyList()
        return rows.map { row ->
            StatRecord(
                eventId = eventIdForDictation(row.syncId),
                day = utcDayOf(row.timestampMs),
                wordCount = row.wordCount,
                durationMs = row.durationMs,
                updatedAt = now,
                deviceId = deviceId,
                deletedAt = null,
                timestampMs = row.timestampMs,
                chars = 0
            )
        }
    }

    data class TranscriptionRowLite(val timestampMs: Long, val wordCount: Int, val durationMs: Long, val syncId: String)
    data class DailyStatLite(val day: String, val wordCount: Int, val durationMs: Long)

    /** Fallback source when no live history rows exist. */
    fun fromDailyStats(
        stats: List<DailyStatLite>,
        accountHash: String,
        deviceId: String,
        now: Long
    ): List<StatRecord> {
        return stats.sortedBy { it.day }.mapIndexed { idx, s ->
            StatRecord(
                eventId = eventIdFor(s.day, accountHash, idx),
                day = s.day,
                wordCount = s.wordCount,
                durationMs = s.durationMs,
                updatedAt = now,
                deviceId = deviceId,
                deletedAt = null,
                timestampMs = 0L,
                chars = 0
            )
        }
    }
}
