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
 * eventIds are deterministic (SHA-1, version/variant bits set) so a re-run
 * after a crash reproduces identical ids and the union dedup absorbs them.
 *
 * DO NOT "align" these derivations with the Windows twins. They differ
 * deliberately, and aligning them would be destructive:
 *  - Windows backfills PER HISTORY ROW; Android falls back to the
 *    `stats_daily` AGGREGATE, keyed per (day, accountHash, index) — a
 *    different event, not the same one minted twice.
 *  - Transcription history is DEVICE-LOCAL and never syncs, so one dictation
 *    exists on exactly one device. The same logical event is therefore never
 *    minted on both platforms, and the union dedup by `eventId` is never asked
 *    to collapse a CROSS-PLATFORM pair. That much is settled.
 *
 * Changing either derivation re-ids every event this install already backfilled.
 * The ledger keeps the old ids, the next pass mints new ones, the union dedup
 * sees them as distinct, and the user's stats DOUBLE.
 *
 * KNOWN RESIDUAL — UNMITIGATED, AND THE INDEX IS THE CAUSE.
 * `fromDailyStats` keys on (day, accountHash, index) where `index` comes from
 * `mapIndexed` over the SORTED day list. Insert, remove, or add a single day in
 * `stats_daily` between runs and every later day's index shifts, so an existing
 * day gets a DIFFERENT eventId. The ledger keeps the old event, the next run
 * mints a new one, and the union dedup counts that day twice — permanently.
 * This is single-platform and unrelated to the cross-platform question above.
 *
 * How reachable is it? Narrower than it looks, but NOT zero:
 *  - The marker (`sync_metadata.backfillDone`) and the source (`DailyStat`) are
 *    both entities in the SAME `FluenceDatabase`, so an app-data wipe removes
 *    both together and cannot by itself re-mint over a surviving remote ledger.
 *  - BUT the marker is device-local and is NEVER restored from the synced
 *    ledger, while the ledger IS remote and does survive. So any path that
 *    resets the marker while repopulating `DailyStat` with overlapping days
 *    re-mints ids that differ from the ones the remote ledger already holds, and
 *    those days double-count. The concrete reachable case is
 *    `setBackfillDone(hash, false)` on a live install. A plain reinstall is NOT
 *    sufficient: Android auto-backup restores the whole app-data directory, so
 *    the marker comes back alongside the data — a full wipe is likewise enough,
 *    since it takes `DailyStat` too. It takes a SELECTIVE restore (a restored
 *    history/stats payload without the marker) to land in this state.
 *
 * WHY THE DERIVATION IS STILL LEFT ALONE. Keying on (day, accountHash) with no
 * index would remove the sensitivity entirely, but changing the derivation
 * re-ids every event this install already backfilled: the ledger keeps the old
 * ids, the next run mints new ones, and EVERY existing user's stats DOUBLE. That
 * converts a conditional residual into guaranteed, permanent corruption for the
 * whole installed base, which is not a trade worth making for this. Fixing it
 * properly needs a migration of already-minted ids, not a derivation change.
 */
object Backfill {

    fun utcDayOf(timestampMs: Long): String {
        return Instant.ofEpochMilli(timestampMs).atZone(ZoneOffset.UTC)
            .toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    fun eventIdForDictation(dictationSyncId: String): String =
        UUID.nameUUIDFromBytes("fluence-stat-v1:$dictationSyncId".toByteArray()).toString()

    /** Stable UUID for a pre-v5 history row whose syncId was never assigned. */
    fun syncIdForHistoryRow(historyRowId: Long): String =
        UUID.nameUUIDFromBytes("fluence-history-v1:$historyRowId".toByteArray()).toString()

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
                chars = row.chars
            )
        }
    }

    data class TranscriptionRowLite(
        val timestampMs: Long,
        val wordCount: Int,
        val durationMs: Long,
        val syncId: String,
        /** Real character count from the transcription text (fresh events use text.length). */
        val chars: Int = 0
    )
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
