package com.groq.voicetyper.sync.v1

import java.util.UUID

/**
 * Frozen v1.2 engine — one domain file per pass.
 *
 * Per domain: stamp NULL→accountHash atomically → GET (duplicate-safe) →
 * corrupt envelope treated as absent (repaired via CAS-protected push when any
 * usable local state exists) → MERGE (pure LWW winner max(updatedAt, deviceId);
 * stats union by eventId; settings per-key LWW over the frozen five) → PUT with
 * version-number staleness detection.
 *
 * Concurrency: Drive v3 does not honor If-Match, so freshness is verified via
 * the file `version` revision immediately before write. On StaleVersion the
 * full GET→MERGE cycle reruns against the fresh remote (bounded retries).
 * Check-then-write is not atomic; a race inside that window heals on the next
 * pass because every device persists its merged state locally. No silent loss:
 * local data is never discarded unless a strictly newer remote record wins.
 *
 * On success the store applies winners + clears dirty + sets everPushed in ONE
 * transaction, and maxSeen advances to the max updatedAt observed (monotonic
 * clock floor).
 */
object V1SyncEngine {

    const val MAX_ATTEMPTS = 4

    /**
     * Transport seam for one domain file — implemented by [AppDataDriveStore]
     * in production and by fakes in tests. The engine never sees HTTP/tokens.
     */
    interface DomainGateway {
        fun getDomain(domain: DomainFile): AppDataDriveStore.DomainFetch
        /** Upload; [expectedVersion] = version the merge was based on (null = create). */
        fun putDomain(domain: DomainFile, bytes: ByteArray, expectedVersion: String?): String

        // ---- Stats time shards (monthly per-event envelopes) ----
        // New clients union the frozen legacy `stats.json` (read via
        // getDomain(STATS), NEVER via putDomain(STATS)) with these siblings.
        // Old released clients list `stats.json` by exact name and never see
        // shard files. Defaults keep legacy-only fakes compiling; production
        // overrides them in AppDataDriveStore.
        /** All shard siblings in v1 (legacy `stats.json` excluded), sorted. */
        fun listStatsShards(): List<StatsShardMeta> = emptyList()
        /** Shard bytes by file id; null = vanished. Throws Rejected when oversized. */
        fun getStatsShardBytes(fileId: String): ByteArray? = null
        /**
         * CAS upload of one shard file; [expectedVersion] = version the merge
         * was based on (null = create), [preferredFileId] = file to update.
         * Rejects non-shard names fail-closed (legacy is never written here).
         */
        fun putStatsShard(
            name: String,
            bytes: ByteArray,
            expectedVersion: String?,
            preferredFileId: String?
        ): String = throw SyncError.Rejected("stats shards unsupported")

        /** Delete one shard file by id (same-name valid-duplicate cleanup only). */
        fun deleteStatsShard(fileId: String) {}
    }

    data class SyncResult(
        val uploaded: Boolean,
        val merged: Boolean,
        val skippedCorrupt: Boolean = false,
        val attemptsUsed: Int = 0
    )

    // ------------------------------------------------------------------
    // Dictionary — one winner per businessKey
    // ------------------------------------------------------------------

    suspend fun syncDictionary(
        localStore: DictionaryV1Store,
        drive: DomainGateway,
        accountHash: String,
        deviceId: String,
        maxSeenRef: MaxSeenRef
    ): SyncResult {
        localStore.stampUnstamped(accountHash)
        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            attempts++
            val fetch = drive.getDomain(DomainFile.DICTIONARY)
            if (fetch.bytes != null) {
                // Corrupt-but-size-valid remote (AppDataDriveStore guarantees:
                // oversize already surfaced as Rejected, corrupt bytes returned
                // with the corrupt file's version + preferred write target).
                // Treat it as absent for merging; the merged state then repairs
                // it in place via a CAS-protected put (Windows parity).
                val remoteDomain = DomainSerializer.parseDictionary(fetch.bytes!!)
                if (remoteDomain == null && DomainSerializer.isFutureEnvelope(fetch.bytes!!)) {
                    // A newer client owns this file: never write, never fabricate a
                    // replacement, leave it until this client is upgraded. Tested only
                    // after the typed parse failed, so a payload that parses is v1 by
                    // definition (Windows parity; also avoids parsing a large payload
                    // twice on the happy path).
                    return SyncResult(false, false, skippedCorrupt = true, attemptsUsed = attempts)
                }
                val remoteWasCorrupt = remoteDomain == null
                val merged = Merge.mergeDictionaries(
                    localStore.loadByAccount(accountHash).map { it.toRecord() },
                    remoteDomain?.entries ?: emptyList()
                )
                val remoteSorted = remoteDomain?.entries?.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
                    ?: emptyList()
                val needsPut = localStore.hasDirty(accountHash) || merged != remoteSorted || fetch.hasDuplicateValidFiles
                if (!needsPut) {
                    if (remoteWasCorrupt) {
                        // No usable local state and nothing dirty — never
                        // fabricate an upload (Windows parity: no-op skip).
                        return SyncResult(false, false, skippedCorrupt = true, attemptsUsed = attempts)
                    }
                    localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                    advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                    return SyncResult(false, true, attemptsUsed = attempts)
                }
                val sorted = merged.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
                val bytes = DomainSerializer.serializeDictionary(DictionaryDomain(entries = sorted)).toByteArray()
                // Tolerant self-check: count + businessKey/syncId set must survive roundtrip.
                // Full record equality is intentionally avoided — org.json null/number quirks
                // cause false Rejected on device vs JVM test. Desktop has no guard.
                DomainSerializer.parseDictionary(bytes)?.let { parsed ->
                    if (parsed.entries.size != sorted.size ||
                        parsed.entries.map { it.businessKey }.toSet() != sorted.map { it.businessKey }.toSet()) {
                        throw SyncError.Rejected("dictionary roundtrip key mismatch")
                    }
                } ?: throw SyncError.Rejected("dictionary roundtrip produced null")
                val uploadedVersion = try {
                    drive.putDomain(DomainFile.DICTIONARY, bytes, fetch.version)
                } catch (e: SyncError.StaleVersion) {
                    if (attempts < MAX_ATTEMPTS) null else throw e
                }
                if (uploadedVersion != null && uploadIsLive(drive, DomainFile.DICTIONARY, uploadedVersion)) {
                    localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                    advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                    return SyncResult(true, true, attemptsUsed = attempts)
                }
                waitForStaleRetry(attempts) // break the lockstep livelock
                continue // stale version: full re-GET -> MERGE -> PUT
            } else {
                // No remote file yet: create from local state.
                val local = localStore.loadByAccount(accountHash).map { it.toRecord() }
                val merged = Merge.mergeDictionaries(local, emptyList())
                if (merged.isEmpty()) return SyncResult(false, true, attemptsUsed = attempts)
                val sorted2 = merged.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
                val bytes2 = DomainSerializer.serializeDictionary(DictionaryDomain(entries = sorted2)).toByteArray()
                DomainSerializer.parseDictionary(bytes2)?.let { parsed ->
                    if (parsed.entries.size != sorted2.size) throw SyncError.Rejected("dictionary roundtrip size mismatch")
                } ?: throw SyncError.Rejected("dictionary roundtrip null")
                val createdVersion = drive.putDomain(DomainFile.DICTIONARY, bytes2, null)
                if (!uploadIsLive(drive, DomainFile.DICTIONARY, createdVersion)) {
                    return SyncResult(false, true, attemptsUsed = attempts)
                }
                localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                return SyncResult(true, true, attemptsUsed = attempts)
            }
        }
        return SyncResult(false, true, attemptsUsed = attempts)
    }

    // ------------------------------------------------------------------
    // Snippets — one winner per businessKey, expansion byte-exact
    // ------------------------------------------------------------------

    suspend fun syncSnippets(
        localStore: SnippetV1Store,
        drive: DomainGateway,
        accountHash: String,
        deviceId: String,
        maxSeenRef: MaxSeenRef
    ): SyncResult {
        localStore.stampUnstamped(accountHash)
        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            attempts++
            val fetch = drive.getDomain(DomainFile.SNIPPETS)
            if (fetch.bytes != null) {
                val remoteDomain = DomainSerializer.parseSnippets(fetch.bytes!!)
                if (remoteDomain == null && DomainSerializer.isFutureEnvelope(fetch.bytes!!)) {
                    return SyncResult(false, false, skippedCorrupt = true, attemptsUsed = attempts)
                }
                val remoteWasCorrupt = remoteDomain == null
                val merged = Merge.mergeSnippets(
                    localStore.loadByAccount(accountHash).map { it.toRecord() },
                    remoteDomain?.entries ?: emptyList()
                )
                val remoteSorted = remoteDomain?.entries?.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
                    ?: emptyList()
                val needsPut = localStore.hasDirty(accountHash) || merged != remoteSorted || fetch.hasDuplicateValidFiles
                if (!needsPut) {
                    if (remoteWasCorrupt) {
                        return SyncResult(false, false, skippedCorrupt = true, attemptsUsed = attempts)
                    }
                    // Remote already converged: persist merged winners locally
                    // anyway, or a fresh device never materializes pulled data.
                    localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                    advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                    return SyncResult(false, true, attemptsUsed = attempts)
                }
                val sorted = merged.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
                val bytes = DomainSerializer.serializeSnippets(SnippetDomain(entries = sorted)).toByteArray()
                DomainSerializer.parseSnippets(bytes)?.let { parsed ->
                    if (parsed.entries.size != sorted.size ||
                        parsed.entries.map { it.businessKey }.toSet() != sorted.map { it.businessKey }.toSet()) {
                        throw SyncError.Rejected("snippets roundtrip key mismatch")
                    }
                } ?: throw SyncError.Rejected("snippets roundtrip null")
                val uploadedVersion = try {
                    drive.putDomain(DomainFile.SNIPPETS, bytes, fetch.version)
                } catch (e: SyncError.StaleVersion) {
                    if (attempts < MAX_ATTEMPTS) null else throw e
                }
                if (uploadedVersion != null && uploadIsLive(drive, DomainFile.SNIPPETS, uploadedVersion)) {
                    localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                    advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                    return SyncResult(true, true, attemptsUsed = attempts)
                }
                waitForStaleRetry(attempts) // break the lockstep livelock
                continue
            } else {
                val merged = Merge.mergeSnippets(localStore.loadByAccount(accountHash).map { it.toRecord() }, emptyList())
                if (merged.isEmpty()) return SyncResult(false, true, attemptsUsed = attempts)
                val sorted2 = merged.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
                val bytes2 = DomainSerializer.serializeSnippets(SnippetDomain(entries = sorted2)).toByteArray()
                DomainSerializer.parseSnippets(bytes2)?.let { parsed ->
                    if (parsed.entries.size != sorted2.size) throw SyncError.Rejected("snippets roundtrip size mismatch")
                } ?: throw SyncError.Rejected("snippets roundtrip null")
                val createdVersion = drive.putDomain(DomainFile.SNIPPETS, bytes2, null)
                if (!uploadIsLive(drive, DomainFile.SNIPPETS, createdVersion)) {
                    return SyncResult(false, true, attemptsUsed = attempts)
                }
                localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                return SyncResult(true, true, attemptsUsed = attempts)
            }
        }
        return SyncResult(false, true, attemptsUsed = attempts)
    }

    // ------------------------------------------------------------------
    // Stats — time-sharded union ledger (frozen legacy + monthly shards).
    //
    // The Stats ledger is one LOGICAL set of per-dictation events stored as
    // SEVERAL physical v1 envelopes: the frozen legacy `stats.json` plus
    // monthly shards (`stats-YYYY-MM.json`, overflow `-pNN`). Every shard is
    // a complete v1 envelope with identical serialization, validation,
    // sorting, caps, merge/LWW, CAS, and liveness. Storage location changes;
    // record meaning never does (sessions = record count, totals = sums).
    //
    // Legacy rules (mixed-version safety):
    // - `stats.json` is read (unioned) but NEVER written and NEVER deleted by
    //   this path. Old released clients own it; they list it by exact name
    //   and never see shard siblings.
    // - Fresh accounts may have NO legacy file at all: new clients simply
    //   sync shards. If an old client later creates `stats.json` (its
    //   create-on-absent path), the next pass unions it in with no rewrite —
    //   dashboards differ across versions until upgrade, by design, and
    //   converge after. Seeding legacy from new clients was rejected: it
    //   would duplicate history or route growth back into the uncapped file.
    // - Shard content per month = merged rows NOT identical to the legacy
    //   version (history is never recopied, moved, or deleted).
    // - Shard files are never deleted; same-name valid duplicates are
    //   consolidated after a live PUT, corrupt/oversized files stay intact.
    // - No cross-file atomicity: each chunk PUT is independent; a partial
    //   pass leaves rows dirty so the next pass re-heals the remainder.
    // - Push decisions are diff-driven (desired chunk bytes vs remote), NOT
    //   `hasDirty`-driven: dirty flags are sticky under the mid-pass LWW
    //   tie-guard (an uploaded row ties with itself), so `hasDirty` is
    //   permanently true in steady state and must never gate PUTs — the
    //   diff already converges to zero PUTs with no upload loop.
    // ------------------------------------------------------------------

    suspend fun syncStats(
        localStore: StatV1Store,
        drive: DomainGateway,
        accountHash: String,
        deviceId: String,
        maxSeenRef: MaxSeenRef
    ): SyncResult {
        localStore.stampUnstamped(accountHash)
        if (!localStore.isBackfillDone(accountHash)) {
            localStore.backfillIfNeeded(accountHash, deviceId)
            localStore.setBackfillDone(accountHash, true)
        }
        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            attempts++
            try {
                return syncStatsAttempt(localStore, drive, accountHash, deviceId, maxSeenRef, attempts)
            } catch (e: SyncError.StaleVersion) {
                if (attempts >= MAX_ATTEMPTS) throw e
                waitForStaleRetry(attempts) // break the lockstep livelock
            } catch (e: RetryLedger) {
                // Liveness miss: the PUT may still appear. Retry the whole
                // ledger read; live chunks compare equal and skip.
                if (attempts >= MAX_ATTEMPTS) {
                    return SyncResult(false, false, attemptsUsed = attempts)
                }
                waitForStaleRetry(attempts)
            }
        }
        return SyncResult(false, true, attemptsUsed = attempts)
    }

    /** Internal: abort this ledger attempt and re-run GET -> MERGE -> PUT. */
    private class RetryLedger : Exception()

    private data class ShardPut(
        val name: String,
        val bytes: ByteArray,
        val expectedVersion: String?,
        val preferredFileId: String?,
        val extraValidIds: List<String>
    )

    private suspend fun syncStatsAttempt(
        localStore: StatV1Store,
        drive: DomainGateway,
        accountHash: String,
        deviceId: String,
        maxSeenRef: MaxSeenRef,
        attempts: Int
    ): SyncResult {
        // 1. Legacy `stats.json`: read-only. Only an oversize Rejected is
        // caught (skip + retain, shards still sync); transport errors
        // propagate. Corrupt/future payloads are skipped, never repaired:
        // frozen means frozen.
        var legacyUnusable = false
        val legacyFetch = try {
            drive.getDomain(DomainFile.STATS)
        } catch (e: SyncError.Rejected) {
            legacyUnusable = true
            AppDataDriveStore.DomainFetch(null, null)
        }
        val legacyEntries: List<StatRecord> = if (legacyFetch.bytes != null) {
            val parsed = DomainSerializer.parseStats(legacyFetch.bytes!!)
            if (parsed != null) parsed.entries
            else {
                // Future envelopes are a newer client's property, not
                // corruption; either way the frozen file is left untouched.
                legacyUnusable = true
                emptyList()
            }
        } else emptyList()

        // 2. Shard siblings, grouped by exact file name (same-name duplicates
        // merge). Oversize/future files are skipped and retained; corrupt
        // files are remembered as CAS repair targets, never deleted.
        data class RemoteShard(
            val validEntries: List<StatRecord>,
            val validIds: List<String>,
            val preferred: StatsShardMeta?,
            val repairTarget: StatsShardMeta?,
            val skipped: Boolean
        )
        val metas = drive.listStatsShards()
            .filter { StatsShards.isStatsShardFileName(it.name) }
            .sortedWith(compareBy(
                { StatsShards.shardSortKey(it.name)!!.first },
                { StatsShards.shardSortKey(it.name)!!.second },
                { it.fileId }
            ))
        val remoteByName = mutableMapOf<String, RemoteShard>()
        var anyShardSkipped = false
        for (name in metas.map { it.name }.distinct()) {
            val files = metas.filter { it.name == name }
            val validEntries = mutableListOf<StatRecord>()
            val validIds = mutableListOf<String>()
            var preferred: StatsShardMeta? = null
            var repairTarget: StatsShardMeta? = null
            var skipped = false
            for (meta in files) {
                // F5: listing-reported oversize skips the download, not just
                // the decode — the bytes would only be discarded.
                if (meta.size != null && meta.size > AppDataDriveStore.MAX_DOMAIN_BYTES) {
                    skipped = true
                    continue
                }
                val bytes = try {
                    drive.getStatsShardBytes(meta.fileId)
                } catch (e: SyncError.Rejected) {
                    skipped = true
                    continue
                } ?: continue // vanished between list and get
                val parsed = DomainSerializer.parseStats(bytes)
                if (parsed != null) {
                    validEntries += parsed.entries
                    validIds += meta.fileId
                    if (preferred == null) preferred = meta
                } else if (DomainSerializer.isFutureEnvelope(bytes)) {
                    skipped = true
                } else if (repairTarget == null) {
                    repairTarget = meta
                }
            }
            if (skipped) anyShardSkipped = true
            remoteByName[name] = RemoteShard(validEntries, validIds, preferred, repairTarget, skipped)
        }

        // 3. Deterministic global merge: local, legacy, then shards by name.
        // Local wins every LWW tie, preserving two-list behavior exactly.
        val localRecords = localStore.loadByAccount(accountHash).map { it.toRecord() }
        val ordered = mutableListOf(localRecords, legacyEntries)
        for (name in remoteByName.keys.sorted()) ordered += remoteByName.getValue(name).validEntries
        val merged = StatsShards.unionStatsMerge(ordered)

        // 4. Desired shard content per month (never recopies legacy history).
        val legacyById = StatsShards.indexByEventId(legacyEntries)
        val months = (StatsShards.monthsIn(merged) +
            remoteByName.keys.mapNotNull { StatsShards.shardSortKey(it)?.first })
            .distinct().sorted()
        val pending = mutableListOf<ShardPut>()
        for (month in months) {
            val desired = StatsShards.desiredShardRows(month, merged, legacyById)
            val chunks = StatsShards.chunkMonthRecords(month, desired)
            if (chunks.isEmpty()) {
                // Nothing desired: remote files (stale losers or corrupt) are
                // left permanently intact — never rewritten empty, never
                // deleted. Their rows lose LWW by construction.
                continue
            }
            for ((name, rows) in chunks) {
                val remote = remoteByName[name]
                val wantSorted = rows.sortedWith(compareBy({ it.day }, { it.eventId }))
                val remoteSorted = remote?.validEntries?.toMutableList()
                    ?.also { StatsShards.sortStatsRecords(it) } ?: emptyList()
                val dupes = (remote?.validIds?.size ?: 0) > 1
                if (remoteSorted == wantSorted && !dupes) continue
                val bytes = DomainSerializer.serializeStats(StatsDomain(entries = wantSorted)).toByteArray()
                DomainSerializer.parseStats(bytes)?.let { parsed ->
                    if (parsed.entries.size != wantSorted.size ||
                        parsed.entries.map { it.eventId }.toSet() != wantSorted.map { it.eventId }.toSet()) {
                        throw SyncError.Rejected("stats shard $name roundtrip key mismatch")
                    }
                } ?: throw SyncError.Rejected("stats shard $name roundtrip null")
                val (expectedVersion, preferredFileId) = when {
                    remote?.preferred != null -> remote.preferred!!.version to remote.preferred!!.fileId
                    remote?.repairTarget != null -> remote.repairTarget!!.version to remote.repairTarget!!.fileId
                    else -> null to null
                }
                val extraValidIds = remote?.let { r ->
                    r.validIds.filter { it != r.preferred?.fileId }
                } ?: emptyList()
                pending.add(ShardPut(name, bytes, expectedVersion, preferredFileId, extraValidIds))
            }
        }

        val degraded = legacyUnusable || anyShardSkipped
        if (pending.isEmpty()) {
            // Converged (or nothing to write): persist merged winners locally
            // anyway, or a fresh device never materializes pulled data.
            if (merged.toSet() != localRecords.toSet()) {
                localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                return SyncResult(false, true, skippedCorrupt = degraded, attemptsUsed = attempts)
            }
            if (degraded) {
                return SyncResult(false, false, skippedCorrupt = true, attemptsUsed = attempts)
            }
            localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
            advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
            return SyncResult(false, true, attemptsUsed = attempts)
        }

        // 5. Version-checked upload per chunk (no cross-file atomicity).
        // NOTE: a StaleVersion from any putStatsShard escapes to the caller,
        // which retries the whole ledger; live chunks compare equal and skip.
        for (put in pending) {
            val uploadedVersion = drive.putStatsShard(put.name, put.bytes, put.expectedVersion, put.preferredFileId)
            if (!isShardLive(drive, put.name, uploadedVersion)) {
                throw RetryLedger() // liveness miss: full re-list -> re-merge -> re-put
            }
            // Same-name valid duplicates consolidated only after a live PUT.
            // Corrupt/oversized files remain intact.
            for (duplicateId in put.extraValidIds) drive.deleteStatsShard(duplicateId)
        }
        localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
        advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
        return SyncResult(true, true, skippedCorrupt = degraded, attemptsUsed = attempts)
    }

    private fun isShardLive(drive: DomainGateway, name: String, uploadedVersion: String): Boolean =
        try {
            drive.listStatsShards().any { it.name == name && it.version == uploadedVersion }
        } catch (e: Exception) {
            false
        }

    // ------------------------------------------------------------------
    // Settings — per-key LWW over the frozen five keys
    // ------------------------------------------------------------------

    suspend fun syncSettings(
        localStore: SettingsV1Store,
        drive: DomainGateway,
        accountHash: String,
        deviceId: String,
        maxSeenRef: MaxSeenRef
    ): SyncResult {
        localStore.stampUnstamped(accountHash)
        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            attempts++
            val fetch = drive.getDomain(DomainFile.SETTINGS)
            if (fetch.bytes != null) {
                val remoteDomain = DomainSerializer.parseSettings(fetch.bytes!!)
                if (remoteDomain == null && DomainSerializer.isFutureEnvelope(fetch.bytes!!)) {
                    return SyncResult(false, false, skippedCorrupt = true, attemptsUsed = attempts)
                }
                val remoteWasCorrupt = remoteDomain == null
                val merged = Merge.mergeSettings(
                    localStore.loadByAccount(accountHash).map { it.toRecord() },
                    remoteDomain?.entries ?: emptyList()
                )
                val remoteSorted = remoteDomain?.entries?.sortedBy { it.key } ?: emptyList()
                val needsPut = localStore.hasDirty(accountHash) || merged != remoteSorted || fetch.hasDuplicateValidFiles
                if (!needsPut) {
                    if (remoteWasCorrupt) {
                        return SyncResult(false, false, skippedCorrupt = true, attemptsUsed = attempts)
                    }
                    // Remote already converged: persist merged winners locally
                    // anyway, or a fresh device never materializes pulled data.
                    localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                    advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                    return SyncResult(false, true, attemptsUsed = attempts)
                }
                val sorted = merged.sortedBy { it.key }
                val bytes = DomainSerializer.serializeSettings(SettingsDomain(entries = sorted)).toByteArray()
                DomainSerializer.parseSettings(bytes)?.let { parsed ->
                    if (parsed.entries.size != sorted.size ||
                        parsed.entries.map { it.key }.toSet() != sorted.map { it.key }.toSet()) {
                        throw SyncError.Rejected("settings roundtrip key mismatch")
                    }
                } ?: throw SyncError.Rejected("settings roundtrip null")
                val uploadedVersion = try {
                    drive.putDomain(DomainFile.SETTINGS, bytes, fetch.version)
                } catch (e: SyncError.StaleVersion) {
                    if (attempts < MAX_ATTEMPTS) null else throw e
                }
                if (uploadedVersion != null && uploadIsLive(drive, DomainFile.SETTINGS, uploadedVersion)) {
                    localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                    advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                    return SyncResult(true, true, attemptsUsed = attempts)
                }
                waitForStaleRetry(attempts) // break the lockstep livelock
                continue
            } else {
                val merged = Merge.mergeSettings(localStore.loadByAccount(accountHash).map { it.toRecord() }, emptyList())
                if (merged.isEmpty()) return SyncResult(false, true, attemptsUsed = attempts)
                val sorted2 = merged.sortedBy { it.key }
                val bytes2 = DomainSerializer.serializeSettings(SettingsDomain(entries = sorted2)).toByteArray()
                DomainSerializer.parseSettings(bytes2)?.let { parsed ->
                    if (parsed.entries.size != sorted2.size) throw SyncError.Rejected("settings roundtrip size mismatch")
                } ?: throw SyncError.Rejected("settings roundtrip null")
                val createdVersion = drive.putDomain(DomainFile.SETTINGS, bytes2, null)
                if (!uploadIsLive(drive, DomainFile.SETTINGS, createdVersion)) {
                    return SyncResult(false, true, attemptsUsed = attempts)
                }
                localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                return SyncResult(true, true, attemptsUsed = attempts)
            }
        }
        return SyncResult(false, true, attemptsUsed = attempts)
    }

    /** Monotonic clock floor: maxSeen never moves backwards. */
    // ------------------------------------------------------------------
    // Agents / Styles (Phase 6, additive) — one winner per businessKey
    // ------------------------------------------------------------------

    /**
     * Agents ride the same GET→MERGE→PUT contract as dictionary, with no
     * agent-specific rules: 2B LWW decides duplicates, `records()` feeds the
     * merge so tombstones survive, and the display projection stays separate.
     *
     * A blank [accountHash] means "never signed in" — local-first state must
     * never be uploaded into an unscoped or shared file, so we no-op.
     */
    suspend fun syncAgents(
        localStore: AgentV1Store,
        drive: DomainGateway,
        accountHash: String,
        deviceId: String,
        maxSeenRef: MaxSeenRef
    ): SyncResult {
        if (accountHash.isBlank()) return SyncResult(false, false)
        localStore.stampUnstamped(accountHash)
        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            attempts++
            val fetch = drive.getDomain(DomainFile.AGENTS)
            if (fetch.bytes != null) {
                val remoteDomain = DomainSerializer.parseAgents(fetch.bytes!!)
                if (remoteDomain == null && DomainSerializer.isFutureEnvelope(fetch.bytes!!)) {
                    // A newer client owns this file: never write, never fabricate a
                    // replacement, leave it until this client is upgraded.
                    return SyncResult(false, false, skippedCorrupt = true, attemptsUsed = attempts)
                }
                val remoteWasCorrupt = remoteDomain == null
                val merged = Merge.mergeAgents(
                    localStore.loadByAccount(accountHash).map { it.toRecord() },
                    remoteDomain?.entries ?: emptyList()
                )
                val remoteSorted = remoteDomain?.entries?.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
                    ?: emptyList()
                val needsPut = localStore.hasDirty(accountHash) || merged != remoteSorted || fetch.hasDuplicateValidFiles
                if (!needsPut) {
                    if (remoteWasCorrupt) {
                        return SyncResult(false, false, skippedCorrupt = true, attemptsUsed = attempts)
                    }
                    localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                    advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                    return SyncResult(false, true, attemptsUsed = attempts)
                }
                val sorted = merged.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
                val bytes = DomainSerializer.serializeAgents(AgentDomain(entries = sorted)).toByteArray()
                DomainSerializer.parseAgents(bytes)?.let { parsed ->
                    if (parsed.entries.size != sorted.size ||
                        parsed.entries.map { it.businessKey }.toSet() != sorted.map { it.businessKey }.toSet()
                    ) {
                        throw SyncError.Rejected("agents roundtrip key mismatch")
                    }
                } ?: throw SyncError.Rejected("agents roundtrip produced null")
                val uploadedVersion = try {
                    drive.putDomain(DomainFile.AGENTS, bytes, fetch.version)
                } catch (e: SyncError.StaleVersion) {
                    if (attempts < MAX_ATTEMPTS) null else throw e
                }
                if (uploadedVersion != null && uploadIsLive(drive, DomainFile.AGENTS, uploadedVersion)) {
                    localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                    advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                    return SyncResult(true, true, attemptsUsed = attempts)
                }
                waitForStaleRetry(attempts)
                continue
            } else {
                val local = localStore.loadByAccount(accountHash).map { it.toRecord() }
                val merged = Merge.mergeAgents(local, emptyList())
                if (merged.isEmpty()) return SyncResult(false, true, attemptsUsed = attempts)
                val sorted2 = merged.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
                val bytes2 = DomainSerializer.serializeAgents(AgentDomain(entries = sorted2)).toByteArray()
                DomainSerializer.parseAgents(bytes2)?.let { parsed ->
                    if (parsed.entries.size != sorted2.size) throw SyncError.Rejected("agents roundtrip size mismatch")
                } ?: throw SyncError.Rejected("agents roundtrip null")
                val createdVersion = drive.putDomain(DomainFile.AGENTS, bytes2, null)
                if (!uploadIsLive(drive, DomainFile.AGENTS, createdVersion)) {
                    return SyncResult(false, true, attemptsUsed = attempts)
                }
                localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                return SyncResult(true, true, attemptsUsed = attempts)
            }
        }
        return SyncResult(false, true, attemptsUsed = attempts)
    }

    suspend fun syncStyles(
        localStore: StyleV1Store,
        drive: DomainGateway,
        accountHash: String,
        deviceId: String,
        maxSeenRef: MaxSeenRef
    ): SyncResult {
        if (accountHash.isBlank()) return SyncResult(false, false)
        localStore.stampUnstamped(accountHash)
        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            attempts++
            val fetch = drive.getDomain(DomainFile.STYLES)
            if (fetch.bytes != null) {
                val remoteDomain = DomainSerializer.parseStyles(fetch.bytes!!)
                if (remoteDomain == null && DomainSerializer.isFutureEnvelope(fetch.bytes!!)) {
                    return SyncResult(false, false, skippedCorrupt = true, attemptsUsed = attempts)
                }
                val remoteWasCorrupt = remoteDomain == null
                val merged = Merge.mergeStyles(
                    localStore.loadByAccount(accountHash).map { it.toRecord() },
                    remoteDomain?.entries ?: emptyList()
                )
                val remoteSorted = remoteDomain?.entries?.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
                    ?: emptyList()
                val needsPut = localStore.hasDirty(accountHash) || merged != remoteSorted || fetch.hasDuplicateValidFiles
                if (!needsPut) {
                    if (remoteWasCorrupt) {
                        return SyncResult(false, false, skippedCorrupt = true, attemptsUsed = attempts)
                    }
                    localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                    advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                    return SyncResult(false, true, attemptsUsed = attempts)
                }
                val sorted = merged.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
                val bytes = DomainSerializer.serializeStyles(StyleDomain(entries = sorted)).toByteArray()
                DomainSerializer.parseStyles(bytes)?.let { parsed ->
                    if (parsed.entries.size != sorted.size ||
                        parsed.entries.map { it.businessKey }.toSet() != sorted.map { it.businessKey }.toSet()
                    ) {
                        throw SyncError.Rejected("styles roundtrip key mismatch")
                    }
                } ?: throw SyncError.Rejected("styles roundtrip produced null")
                val uploadedVersion = try {
                    drive.putDomain(DomainFile.STYLES, bytes, fetch.version)
                } catch (e: SyncError.StaleVersion) {
                    if (attempts < MAX_ATTEMPTS) null else throw e
                }
                if (uploadedVersion != null && uploadIsLive(drive, DomainFile.STYLES, uploadedVersion)) {
                    localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                    advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                    return SyncResult(true, true, attemptsUsed = attempts)
                }
                waitForStaleRetry(attempts)
                continue
            } else {
                val local = localStore.loadByAccount(accountHash).map { it.toRecord() }
                val merged = Merge.mergeStyles(local, emptyList())
                if (merged.isEmpty()) return SyncResult(false, true, attemptsUsed = attempts)
                val sorted2 = merged.sortedWith(compareBy({ it.businessKey }, { it.syncId }))
                val bytes2 = DomainSerializer.serializeStyles(StyleDomain(entries = sorted2)).toByteArray()
                DomainSerializer.parseStyles(bytes2)?.let { parsed ->
                    if (parsed.entries.size != sorted2.size) throw SyncError.Rejected("styles roundtrip size mismatch")
                } ?: throw SyncError.Rejected("styles roundtrip null")
                val createdVersion = drive.putDomain(DomainFile.STYLES, bytes2, null)
                if (!uploadIsLive(drive, DomainFile.STYLES, createdVersion)) {
                    return SyncResult(false, true, attemptsUsed = attempts)
                }
                localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                return SyncResult(true, true, attemptsUsed = attempts)
            }
        }
        return SyncResult(false, true, attemptsUsed = attempts)
    }

    private fun advanceMaxSeen(maxSeenRef: MaxSeenRef, observed: Long) {
        if (observed > maxSeenRef.value) maxSeenRef.value = observed
    }

    /**
     * B-3 post-upload verification — re-list version only. After a PUT reports a
     * version, re-GET and confirm that revision is actually live before marking
     * the rows pushed. Google Drive's eventual consistency makes a transient
     * miss expected; a miss leaves the rows dirty so the next pass re-heals.
     */
    private fun uploadIsLive(drive: DomainGateway, domain: DomainFile, uploadedVersion: String): Boolean =
        try {
            drive.getDomain(domain).version == uploadedVersion
        } catch (e: Exception) {
            false
        }

    /**
     * Jittered backoff before a StaleVersion re-fetch. De-correlates contending
     * devices that would otherwise invalidate each other's CAS in lockstep.
     * `attempts` is the 1-based attempt just consumed (first retry -> 2).
     */
    private suspend fun waitForStaleRetry(attempts: Int) {
        if (attempts < 2) return
        kotlinx.coroutines.delay(staleRetryDelayMs(attempts))
    }

    /** Mutable maxSeen cell; the orchestrator persists it to sync_metadata. */
    class MaxSeenRef(var value: Long)

    // ------------------------------------------------------------------
    // Store seams (implemented in V1Stores.kt over Room / SharedPreferences)
    // ------------------------------------------------------------------

    interface DictionaryV1Store {
        suspend fun loadByAccount(hash: String): List<DictionaryLocal>
        suspend fun stampUnstamped(hash: String)
        suspend fun hasDirty(hash: String): Boolean
        /** Apply merge winners into local rows and clear dirty + set everPushed — ONE transaction. */
        suspend fun applyMergedAndClearDirty(hash: String, deviceId: String, merged: List<DictionaryRecord>)
    }

    interface SnippetV1Store {
        suspend fun loadByAccount(hash: String): List<SnippetLocal>
        suspend fun stampUnstamped(hash: String)
        suspend fun hasDirty(hash: String): Boolean
        suspend fun applyMergedAndClearDirty(hash: String, deviceId: String, merged: List<SnippetRecord>)
    }

    /**
     * Phase 6 store seams.
     *
     * `loadByAccount` MUST return the SYNC view — tombstones included — because
     * a tombstone that never reaches the merge is a delete that silently
     * un-deletes itself on the next pass. The display projection is a separate
     * concern (AccountScope.displayRecords) and must not be used here.
     */
    interface AgentV1Store {
        suspend fun loadByAccount(hash: String): List<AgentLocal>
        suspend fun stampUnstamped(hash: String)
        suspend fun hasDirty(hash: String): Boolean
        suspend fun applyMergedAndClearDirty(hash: String, deviceId: String, merged: List<AgentRecord>)
    }

    interface StyleV1Store {
        suspend fun loadByAccount(hash: String): List<StyleLocal>
        suspend fun stampUnstamped(hash: String)
        suspend fun hasDirty(hash: String): Boolean
        suspend fun applyMergedAndClearDirty(hash: String, deviceId: String, merged: List<StyleRecord>)
    }

    interface StatV1Store {
        suspend fun loadByAccount(hash: String): List<StatLocal>
        suspend fun stampUnstamped(hash: String)
        suspend fun hasDirty(hash: String): Boolean
        suspend fun applyMergedAndClearDirty(hash: String, deviceId: String, merged: List<StatRecord>)
        suspend fun isBackfillDone(hash: String): Boolean
        suspend fun setBackfillDone(hash: String, done: Boolean)
        /** Seed stat_events from transcription_history (preferred) or stats_daily. Returns whether any rows were created. */
        suspend fun backfillIfNeeded(hash: String, deviceId: String): Boolean
    }

    interface SettingsV1Store {
        suspend fun loadByAccount(hash: String): List<SettingsLocal>
        suspend fun stampUnstamped(hash: String)
        suspend fun hasDirty(hash: String): Boolean
        suspend fun applyMergedAndClearDirty(hash: String, deviceId: String, merged: List<SettingsRecord>)
    }

    // ------------------------------------------------------------------
    // Local row shapes
    // ------------------------------------------------------------------

    data class DictionaryLocal(
        val syncId: String,
        val businessKey: String,
        val spoken: String,
        val corrected: String,
        val isEnabled: Boolean,
        val updatedAt: Long,
        val deletedAt: Long?,
        val deviceId: String,
        val accountHash: String?,
        val dirty: Boolean,
        val everPushed: Boolean
    ) {
        fun toRecord() = DictionaryRecord(syncId, businessKey, spoken, corrected, isEnabled, updatedAt, deletedAt, deviceId)
    }

    data class SnippetLocal(
        val syncId: String,
        val businessKey: String,
        val trigger: String,
        val expansion: String,
        val isEnabled: Boolean,
        val updatedAt: Long,
        val deletedAt: Long?,
        val deviceId: String,
        val accountHash: String?,
        val dirty: Boolean,
        val everPushed: Boolean
    ) {
        fun toRecord() = SnippetRecord(syncId, businessKey, trigger, expansion, isEnabled, updatedAt, deletedAt, deviceId)
    }

    data class StatLocal(
        val eventId: String,
        val day: String,
        val wordCount: Int,
        val durationMs: Long,
        val updatedAt: Long,
        val deviceId: String,
        val deletedAt: Long?,
        val accountHash: String?,
        val dirty: Boolean,
        val everPushed: Boolean,
        val timestampMs: Long = 0,
        val chars: Int = 0
    ) {
        fun toRecord() = StatRecord(eventId, day, wordCount, durationMs, updatedAt, deviceId, deletedAt, timestampMs, chars)
    }

    data class SettingsLocal(
        val key: String,
        val value: String,
        val updatedAt: Long,
        val deviceId: String,
        val deletedAt: Long?,
        val accountHash: String?,
        val dirty: Boolean,
        val everPushed: Boolean
    ) {
        fun toRecord() = SettingsRecord(key, value, updatedAt, deviceId, deletedAt)
    }

    /**
     * Phase 6 local rows. `accountHash` is always the owning account for these
     * (Phase 4 stores them in per-account preference files), so `dirty` is the
     * only thing that varies per pass.
     */
    data class AgentLocal(
        val syncId: String,
        val businessKey: String,
        val name: String,
        val hint: String,
        val updatedAt: Long,
        val deletedAt: Long?,
        val deviceId: String,
        val accountHash: String?,
        val dirty: Boolean,
        val everPushed: Boolean
    ) {
        fun toRecord() = AgentRecord(syncId, businessKey, name, hint, updatedAt, deletedAt, deviceId)
    }

    data class StyleLocal(
        val syncId: String,
        val businessKey: String,
        val name: String,
        val hint: String,
        val updatedAt: Long,
        val deletedAt: Long?,
        val deviceId: String,
        val accountHash: String?,
        val dirty: Boolean,
        val everPushed: Boolean
    ) {
        fun toRecord() = StyleRecord(syncId, businessKey, name, hint, updatedAt, deletedAt, deviceId)
    }
}

fun generateDeviceId(): String = UUID.randomUUID().toString()

private const val STALE_RETRY_BASE_MS = 50L
private const val STALE_RETRY_MAX_MS = 600L

/**
 * Jittered backoff (ms) before a StaleVersion retry. Mirrors the Windows
 * engine: `base * 2^(attempt-2)`, capped, jittered into [0.5x, 1.5x] via
 * [rand] (uniform 0..1). Attempt 1 never delays. Exposed for deterministic
 * unit testing; production passes a `java.util.Random`.
 */
internal fun staleRetryDelayMs(attempts: Int, rand: () -> Double = { randSource.nextDouble() }): Long {
    if (attempts < 2) return 0L
    val base = minOf(STALE_RETRY_MAX_MS, (STALE_RETRY_BASE_MS shl minOf(attempts - 2, 5)).toLong())
    return minOf(STALE_RETRY_MAX_MS, (base * (0.5 + rand() * 1.0)).toLong())
}

private val randSource = java.util.Random()
