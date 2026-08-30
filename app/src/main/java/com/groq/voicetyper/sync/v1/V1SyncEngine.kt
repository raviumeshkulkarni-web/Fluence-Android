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
    // Stats — union dedup eventId, backfill once per accountHash
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
            if (localStore.backfillIfNeeded(accountHash, deviceId)) {
                localStore.setBackfillDone(accountHash, true)
            } else {
                localStore.setBackfillDone(accountHash, true)
            }
        }
        var attempts = 0
        while (attempts < MAX_ATTEMPTS) {
            attempts++
            val fetch = drive.getDomain(DomainFile.STATS)
            if (fetch.bytes != null) {
                val remoteDomain = DomainSerializer.parseStats(fetch.bytes!!)
                val remoteWasCorrupt = remoteDomain == null
                // Stats are an append-only wire ledger. Never remove a legacy
                // aggregate merely because a dictation event exists for the
                // same day; the aggregate may contain data from another peer.
                val merged = Merge.mergeStats(
                    localStore.loadByAccount(accountHash).map { it.toRecord() },
                    remoteDomain?.entries ?: emptyList()
                )
                val remoteSorted = remoteDomain?.entries?.sortedWith(compareBy({ it.day }, { it.eventId }))
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
                val sorted = merged.sortedWith(compareBy({ it.day }, { it.eventId }))
                val bytes = DomainSerializer.serializeStats(StatsDomain(entries = sorted)).toByteArray()
                DomainSerializer.parseStats(bytes)?.let { parsed ->
                    if (parsed.entries.size != sorted.size ||
                        parsed.entries.map { it.eventId }.toSet() != sorted.map { it.eventId }.toSet()) {
                        throw SyncError.Rejected("stats roundtrip key mismatch")
                    }
                } ?: throw SyncError.Rejected("stats roundtrip null")
                val uploadedVersion = try {
                    drive.putDomain(DomainFile.STATS, bytes, fetch.version)
                } catch (e: SyncError.StaleVersion) {
                    if (attempts < MAX_ATTEMPTS) null else throw e
                }
                if (uploadedVersion != null && uploadIsLive(drive, DomainFile.STATS, uploadedVersion)) {
                    localStore.applyMergedAndClearDirty(accountHash, deviceId, merged)
                    advanceMaxSeen(maxSeenRef, merged.maxOfOrNull { it.updatedAt } ?: 0L)
                    return SyncResult(true, true, attemptsUsed = attempts)
                }
                waitForStaleRetry(attempts) // break the lockstep livelock
                continue
            } else {
                val merged = Merge.mergeStats(localStore.loadByAccount(accountHash).map { it.toRecord() }, emptyList())
                if (merged.isEmpty()) {
                    localStore.setBackfillDone(accountHash, true)
                    return SyncResult(false, true, attemptsUsed = attempts)
                }
                val sorted2 = merged.sortedWith(compareBy({ it.day }, { it.eventId }))
                val bytes2 = DomainSerializer.serializeStats(StatsDomain(entries = sorted2)).toByteArray()
                DomainSerializer.parseStats(bytes2)?.let { parsed ->
                    if (parsed.entries.size != sorted2.size) throw SyncError.Rejected("stats roundtrip size mismatch")
                } ?: throw SyncError.Rejected("stats roundtrip null")
                val statCreatedVersion = drive.putDomain(DomainFile.STATS, bytes2, null)
                if (!uploadIsLive(drive, DomainFile.STATS, statCreatedVersion)) {
                    localStore.setBackfillDone(accountHash, true)
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
