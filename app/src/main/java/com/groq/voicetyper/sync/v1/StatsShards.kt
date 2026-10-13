package com.groq.voicetyper.sync.v1

/**
 * Stats time shards (monthly per-event envelopes).
 *
 * The Stats ledger is one LOGICAL set of immutable per-dictation events
 * ([StatRecord], one row per completed dictation). It is stored as SEVERAL
 * physical v1 envelopes so no single file can hit the 8 MiB / 50k caps:
 *
 *   appDataFolder/fluence/v1/stats.json                <- legacy, FROZEN (never written by new clients)
 *   appDataFolder/fluence/v1/stats-YYYY-MM.json        <- month shard, part 1
 *   appDataFolder/fluence/v1/stats-YYYY-MM-pNN.json    <- month overflow parts (NN >= 02)
 *
 * Every shard is a COMPLETE v1 envelope: same `{"v":1,"entries":[...]}` shape,
 * same [StatRecord] schema, same (day, eventId) sort, same per-record
 * validation, same per-file 8 MiB / 50k caps, same union-dedup-by-eventId
 * merge with the same LWW tiebreak, same CAS + upload-liveness per file.
 * Byte-identical with the Windows implementation (`stats_shards.rs`); the
 * naming vectors are asserted literally on both sides.
 *
 * Shard content rule (no migration, no rewrite of history): a shard holds
 * exactly the merged rows of its month that are NOT equal to the legacy
 * `stats.json` version of the same eventId. Legacy rows are never copied,
 * moved, or deleted; they are read as part of the union forever.
 *
 * Old released clients list `stats.json` by EXACT name, so shard siblings
 * are invisible to them: they can neither read, overwrite, nor delete a
 * shard. New clients union legacy + all shards on every read.
 */
/** One remote Stats shard file (legacy `stats.json` excluded). */
data class StatsShardMeta(
    val name: String,
    val fileId: String,
    val version: String?,
    /** F5: Drive-listed byte size when known — lets the engine skip
     *  downloading known-oversized shards. Unknown sizes download as before. */
    val size: Long? = null
)

object StatsShards {

    const val LEGACY_STATS_FILE = "stats.json"
    private const val SHARD_PREFIX = "stats-"
    private const val SHARD_SUFFIX = ".json"
    private const val SHARD_PART_SEP = "-p"

    /** First overflow part number. Part 1 is the base name (`stats-YYYY-MM.json`). */
    const val FIRST_OVERFLOW_PART = 2

    /**
     * UTC month (`YYYY-MM`) of a record `day` (`YYYY-MM-DD` UTC).
     * Null for malformed days (fail-closed: unroutable, excluded from shard
     * content, kept locally, never uploaded).
     */
    fun monthOfDay(day: String): String? {
        if (day.length != 10) return null
        // Strict date validation (leap days, month ranges), mirroring the
        // Windows NaiveDate check.
        try {
            java.time.LocalDate.parse(day, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: Exception) {
            return null
        }
        return day.substring(0, 7)
    }

    /** Canonical shard file for a UTC month, or null unless `YYYY-MM` (01-12). */
    fun shardFileForMonth(month: String): String? {
        if (!isValidMonth(month)) return null
        return "$SHARD_PREFIX$month$SHARD_SUFFIX"
    }

    /**
     * Canonical file for chunk [partIndex] (0-based) of a month: index 0 is
     * the base name, later chunks are `stats-YYYY-MM-pNN.json`.
     * Null for malformed months or part numbers above 999.
     */
    fun chunkFileName(month: String, partIndex: Int): String? {
        if (!isValidMonth(month) || partIndex < 0) return null
        val part = partIndex + 1
        if (part < FIRST_OVERFLOW_PART) {
            check(part == 1) { "part numbers start at 1" }
            return "$SHARD_PREFIX$month$SHARD_SUFFIX"
        }
        if (part > 999) return null
        return if (part < 100) "$SHARD_PREFIX$month$SHARD_PART_SEP${part.toString().padStart(2, '0')}$SHARD_SUFFIX"
        else "$SHARD_PREFIX$month$SHARD_PART_SEP$part$SHARD_SUFFIX"
    }

    private fun isValidMonth(month: String): Boolean {
        if (month.length != 7 || month[4] != '-') return false
        if (!month.substring(0, 4).all { it.isDigit() } || !month.substring(5).all { it.isDigit() }) return false
        val m = month.substring(5).toIntOrNull() ?: return false
        return m in 1..12
    }

    /**
     * True for Stats month/overflow shard names. Never true for the frozen
     * `stats.json`, other domains, backups, or case variants (Drive names are
     * case-sensitive; only exact lowercase shards are addressed).
     */
    fun isStatsShardFileName(name: String): Boolean = shardSortKey(name) != null

    /**
     * Sort key for a shard file: (month, part) with the base name as part 1.
     * Null for anything that is not a shard.
     */
    fun shardSortKey(name: String): Pair<String, Int>? {
        if (!name.startsWith(SHARD_PREFIX) || !name.endsWith(SHARD_SUFFIX)) return null
        val middle = name.substring(SHARD_PREFIX.length, name.length - SHARD_SUFFIX.length)
        val sepAt = middle.indexOf(SHARD_PART_SEP)
        if (sepAt >= 0) {
            val month = middle.substring(0, sepAt)
            val part = middle.substring(sepAt + SHARD_PART_SEP.length)
            if (!isValidMonth(month)) return null
            // Forward-tolerant on read: 2+ ASCII digits, never zero.
            if (part.length < 2 || !part.all { it.isDigit() }) return null
            val n = part.toIntOrNull() ?: return null
            if (n == 0) return null
            return month to n
        }
        if (!isValidMonth(middle)) return null
        return middle to 1
    }

    /** Canonical per-record ordering shared by every shard (envelope sort). */
    fun sortStatsRecords(rows: MutableList<StatRecord>) {
        rows.sortWith(compareBy({ it.day }, { it.eventId }))
    }

    private fun envelopeBytes(rows: List<StatRecord>): ByteArray =
        DomainSerializer.serializeStats(StatsDomain(entries = rows)).toByteArray(Charsets.UTF_8)

    /** True when [rows] fit one physical file: <= 50k records AND <= 8 MiB. */
    fun chunkFits(rows: List<StatRecord>): Boolean {
        if (rows.size > AppDataDriveStore.MAX_ENVELOPE_ITEMS) return false
        return envelopeBytes(rows).size <= AppDataDriveStore.MAX_DOMAIN_BYTES
    }

    private fun splitOversize(sorted: List<StatRecord>): List<List<StatRecord>> {
        if (chunkFits(sorted)) return listOf(sorted)
        val mid = (sorted.size + 1) / 2
        return splitOversize(sorted.subList(0, mid)) + splitOversize(sorted.subList(mid, sorted.size))
    }

    /**
     * Pack one month's rows into named chunk files. Empty input yields no
     * chunks (no empty file is created). Greedy fill by count first (stable:
     * appending newer rows only grows the LAST chunk), then byte-split only
     * oversize chunks. Concatenation in order equals the sorted input.
     *
     * Stability tradeoff (deliberate): crossing 50k rows renumbers later
     * `-pNN` parts once, and byte-splitting bisects — both rewrite a few
     * files in that pass. Always correct (content converges, CAS protects),
     * just briefly more PUTs. Count-only chunking was rejected: 50k
     * worst-case rows exceed 8 MiB, so the byte check is what keeps every
     * chunk uploadable.
     */
    /**
     * Name pre-split chunks for a month in part order. Throws an explicit
     * [SyncError.Fatal] when the month needs more than the 999 addressable
     * parts instead of crashing mid-pass (Windows parity:
     * stats_shards.rs `assign_chunk_names`). Same severity as the old crash
     * path, but honest and greppable, with the local ledger left intact so
     * nothing is uploaded partially. Only reachable with absurd inputs (1000
     * chunks need tens of millions of rows in one month).
     */
    fun assignChunkNames(month: String, chunks: List<List<StatRecord>>): List<Pair<String, List<StatRecord>>> {
        val out = ArrayList<Pair<String, List<StatRecord>>>(chunks.size)
        for ((partIndex, chunk) in chunks.withIndex()) {
            val name = chunkFileName(month, partIndex)
                ?: throw SyncError.Fatal(
                    "stats month $month needs more than 999 shard parts; " +
                        "ledger retained locally, nothing uploaded"
                )
            out.add(name to chunk)
        }
        return out
    }

    fun chunkMonthRecords(month: String, rows: List<StatRecord>): List<Pair<String, List<StatRecord>>> {
        if (rows.isEmpty()) return emptyList()
        val sorted = rows.sortedWith(compareBy({ it.day }, { it.eventId }))
        val chunks = mutableListOf<List<StatRecord>>()
        for (countChunk in sorted.chunked(AppDataDriveStore.MAX_ENVELOPE_ITEMS)) {
            chunks.addAll(splitOversize(countChunk))
        }
        return assignChunkNames(month, chunks)
    }

    /**
     * N-way union merge over ordered record lists. The merge folds over
     * [Merge.mergeStats], which is a TOTAL order (H2): same updatedAt AND
     * same deviceId but different payload resolves by (day, timestampMs,
     * wordCount, chars, durationMs), so the outcome depends only on the
     * record set, not on list order. Truly identical rows still tie, and
     * then fold order decides — callers pass [local, legacy, shards...] so
     * local wins those, preserving the two-list upload tie-guard exactly.
     */
    fun unionStatsMerge(orderedLists: List<List<StatRecord>>): List<StatRecord> {
        var acc: List<StatRecord> = emptyList()
        for (list in orderedLists) acc = Merge.mergeStats(acc, list)
        return acc
    }

    /**
     * Desired shard rows for one month: merged rows of [month] MINUS rows
     * equal to the legacy version of the same eventId. New rows and
     * LWW-updated rows are kept; history in `stats.json` is never recopied.
     */
    fun desiredShardRows(
        month: String,
        mergedSorted: List<StatRecord>,
        legacyById: Map<String, StatRecord>
    ): List<StatRecord> =
        mergedSorted.filter { r ->
            if (monthOfDay(r.day) != month) return@filter false
            val legacy = legacyById[r.eventId]
            if (legacy == null) true else legacy != r
        }

    /** Index helper: eventId -> record for the legacy file's entries. */
    fun indexByEventId(rows: List<StatRecord>): Map<String, StatRecord> =
        rows.associateBy { it.eventId }

    /** Months present in a record list (valid UTC months only), sorted. */
    fun monthsIn(rows: List<StatRecord>): List<String> =
        rows.mapNotNull { monthOfDay(it.day) }.toSortedSet().toList()
}
