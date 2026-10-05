package com.groq.voicetyper.sync.v1

import android.content.Context
import com.groq.voicetyper.dictionary.data.CustomDictionaryDao
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.history.FluenceDatabase
import com.groq.voicetyper.history.StatsCalculator
import com.groq.voicetyper.snippets.Snippet
import androidx.room.withTransaction
import com.groq.voicetyper.snippets.SnippetPreferences
import com.groq.voicetyper.sync.auth.SyncAuthSession
import kotlinx.coroutines.runBlocking

/**
 * Concrete v1.2 store implementations over Room + SharedPreferences.
 * Settings live in [PrefsSettingsV1Store] (V1StoresSettings.kt).
 *
 * Invariants honored here:
 * - load == accountHash only.
 * - applyMergedAndClearDirty: upsert merge winners + dirty=0 + everPushed=1
 *   in ONE transaction per domain call.
 * - Stats backfill: single source = live transcription_history rows, else
 *   stats_daily aggregates; backfillDone lives in sync_metadata per accountHash.
 */
object V1Stores {

    fun dictionaryStore(context: Context): RoomDictionaryV1Store {
        val db = FluenceDatabase.getInstance(context.applicationContext)
        return RoomDictionaryV1Store(db, context.applicationContext)
    }

    fun statStore(context: Context): RoomStatV1Store {
        val db = FluenceDatabase.getInstance(context.applicationContext)
        return RoomStatV1Store(
            db,
            db.statSyncDao(),
            db.transcriptionHistoryDao(),
            db.statsDao(),
            db.syncMetadataDao()
        )
    }

    fun settingsStore(context: Context): PrefsSettingsV1Store =
        PrefsSettingsV1Store(context.applicationContext)

    fun snippetStore(context: Context): PrefsSnippetV1Store =
        PrefsSnippetV1Store(context.applicationContext)

    fun agentStore(context: Context): AccountAgentV1Store =
        AccountAgentV1Store(context.applicationContext)

    fun styleStore(context: Context): AccountStyleV1Store =
        AccountStyleV1Store(context.applicationContext)

    // Phase 6 STAGE 4: the `OwnershipIndex` that used to live here is REMOVED.
    //
    // It existed solely because the Agents/Styles Drive envelope was POOLED at
    // `fluence/v1/agents.json`, where a downloaded record carried no owner and
    // account A could adopt account B's agents into its own selectable and
    // executable rows. STAGE 3 closes that at the path: each account reads and
    // writes only `fluence/v1/acct-<hash>/{agents,styles}.json`, so provenance
    // is established by location and no bookkeeping is required.
    //
    // Removing it also drops three defects it carried:
    //   * it failed OPEN (`ownerOf(...) ?: return false`), so an unknown record
    //     was treated as adoptable;
    //   * it only observed claims made on the SAME device, so it could not
    //     protect a fresh install at all;
    //   * a record claimed under a pre-rename account hash stayed stranded after
    //     an email rename (the same-device rename bug).
    // Its two SharedPreferences files (`fluence_acct_ownership_agents` /
    // `fluence_acct_ownership_styles`) are left on disk unread: they are inert,
    // hold no user data beyond a hash, and deleting them on upgrade would be a
    // data mutation with no benefit.
    /**
     * Phase 6 — Agents/Styles sync store adapters.
     *
     * These read the ACCOUNT's own records only (AccountScope.loadAgents /
     * loadStyles), never the legacy union. Legacy records are unowned by design
     * (D1a) and must never be silently adopted into an account, so syncing the
     * union would leak unowned local data into a signed-in account's Drive file.
     *
     * `loadByAccount` returns the SYNC view, tombstones included: a tombstone that
     * never reaches the merge is a delete that un-deletes itself next pass. The
     * display projection (VisibleAgentsSnapshot.displayRecords) is a separate
     * concern and is never used here.
     *
     * Records that have never been stamped (no syncId/updatedAt/deviceId) are
     * stamped on first sync via [stampUnstamped], which is also what makes them
     * dirty so they get uploaded. syncId is derived deterministically from the
     * record's own id so re-stamping is idempotent and two devices holding the
     * same record agree on its sync identity.
     */
    class AccountAgentV1Store(private val context: Context) : V1SyncEngine.AgentV1Store {

        private fun stableSyncId(id: String): String =
            java.util.UUID.nameUUIDFromBytes(("fluence-agent:" + id).toByteArray()).toString()

        private fun AccountScope.AccountAgent.toLocal(hash: String, deviceId: String) =
            V1SyncEngine.AgentLocal(
                syncId = syncId ?: stableSyncId(id),
                businessKey = id,
                name = name,
                hint = hint,
                updatedAt = updatedAt ?: 0L,
                deletedAt = deletedAt,
                deviceId = deviceId ?: "",
                accountHash = hash,
                // The explicit flag is load-bearing; the metadata checks stay as
                // a safety net so a row written before the flag existed still
                // uploads exactly once instead of being stranded.
                dirty = dirty || syncId == null || updatedAt == null,
                everPushed = syncId != null
            )

        override suspend fun loadByAccount(hash: String): List<V1SyncEngine.AgentLocal> {
            if (!AccountScope.validAccountHash(hash)) return emptyList()
            val deviceId = DeviceIdProvider.getDeviceId(context)
            return AccountScope.loadAgents(context, hash).map { it.toLocal(hash, deviceId) }
        }

        override suspend fun stampUnstamped(hash: String) {
            if (!AccountScope.validAccountHash(hash)) return
            val deviceId = DeviceIdProvider.getDeviceId(context)
            val current = AccountScope.loadAgents(context, hash)
            if (current.none { it.dirty || it.syncId == null || it.updatedAt == null }) return
            // Monotonic clock, exactly as the older domains stamp: the floor is
            // the account's own high-water mark, so two edits inside the same
            // wall-clock millisecond still get DISTINCT, increasing revisions.
            // With a raw `currentTimeMillis()` they collided, and LWW could not
            // order them, so the second edit could lose to the first on a peer.
            val highWater = current.maxOf { it.updatedAt ?: 0L }
            val stamp = Clock.nextUpdatedAt(Clock.nowWallMs(), highWater)
            AccountScope.saveAgents(context, hash, current.map { rec ->
                val stale = rec.updatedAt == null
                rec.copy(
                    syncId = rec.syncId ?: stableSyncId(rec.id),
                    updatedAt = if (rec.dirty || stale) stamp else rec.updatedAt,
                    deviceId = if (rec.dirty || rec.deviceId == null) deviceId else rec.deviceId
                )
            })
        }

        override suspend fun hasDirty(hash: String): Boolean {
            if (!AccountScope.validAccountHash(hash)) return false
            val deviceId = DeviceIdProvider.getDeviceId(context)
            return AccountScope.loadAgents(context, hash).any { it.toLocal(hash, deviceId).dirty }
        }

        override suspend fun applyMergedAndClearDirty(
            hash: String,
            deviceId: String,
            merged: List<AgentRecord>
        ) {
            if (!AccountScope.validAccountHash(hash)) return
            // STAGE 3 makes account isolation STRUCTURAL, so no ownership filter
            // is needed or wanted here.
            //
            // `merged` was read from `fluence/v1/acct-<hash>/agents.json`, a
            // location only this account's devices can name. Provenance is
            // therefore already established by the path itself.
            //
            // The removed `OwnershipIndex` existed only because the envelope used
            // to be POOLED at `fluence/v1/agents.json`, where a downloaded record
            // carried no owner and account A could adopt account B's agents into
            // its own selectable, executable rows. That hole is closed at the
            // path, not by bookkeeping. Keeping the index would have preserved
            // its three defects for no benefit: it failed OPEN
            // (`ownerOf(...) ?: return false`), it only saw claims made on the
            // same device, and it stranded records across an account rename.
            AccountScope.saveAgents(
                context, hash,
                merged.map { rec ->
                    AccountScope.AccountAgent(
                        id = rec.businessKey,
                        name = rec.name,
                        hint = rec.hint,
                        syncId = rec.syncId,
                        updatedAt = rec.updatedAt,
                        deviceId = rec.deviceId,
                        // Tombstone state comes from the WINNER alone.
                        //
                        // The old `rec.deletedAt ?: prior?.deletedAt` grafted a
                        // LOSING local `deletedAt` onto the winning record, so a
                        // newer live winner was persisted as a tombstone no
                        // device ever produced. Because the local side wins a
                        // timestamp tie, each later pass re-PUT that fabricated
                        // tombstone; it can then reach the peer and delete a
                        // record there. The winner already carries the correct
                        // state because it IS the LWW result.
                        deletedAt = rec.deletedAt,
                        // Merged back in: already in the sync state, so clean.
                        // Without this the flag would survive the merge and
                        // every pass would re-upload the row.
                        dirty = false
                    )
                }
            )
        }
    }

    class AccountStyleV1Store(private val context: Context) : V1SyncEngine.StyleV1Store {

        private fun stableSyncId(id: String): String =
            java.util.UUID.nameUUIDFromBytes(("fluence-style:" + id).toByteArray()).toString()

        private fun AccountScope.AccountStyle.toLocal(hash: String, deviceId: String) =
            V1SyncEngine.StyleLocal(
                syncId = syncId ?: stableSyncId(id),
                businessKey = id,
                name = name,
                hint = hint,
                updatedAt = updatedAt ?: 0L,
                deletedAt = deletedAt,
                deviceId = deviceId ?: "",
                accountHash = hash,
                // See the agents counterpart above.
                dirty = dirty || syncId == null || updatedAt == null,
                everPushed = syncId != null
            )

        override suspend fun loadByAccount(hash: String): List<V1SyncEngine.StyleLocal> {
            if (!AccountScope.validAccountHash(hash)) return emptyList()
            val deviceId = DeviceIdProvider.getDeviceId(context)
            return AccountScope.loadStyles(context, hash).map { it.toLocal(hash, deviceId) }
        }

        override suspend fun stampUnstamped(hash: String) {
            if (!AccountScope.validAccountHash(hash)) return
            val deviceId = DeviceIdProvider.getDeviceId(context)
            val current = AccountScope.loadStyles(context, hash)
            if (current.none { it.dirty || it.syncId == null || it.updatedAt == null }) return
            // Monotonic clock, as above and as the older domains already do.
            val highWater = current.maxOf { it.updatedAt ?: 0L }
            val stamp = Clock.nextUpdatedAt(Clock.nowWallMs(), highWater)
            AccountScope.saveStyles(context, hash, current.map { rec ->
                val stale = rec.updatedAt == null
                rec.copy(
                    syncId = rec.syncId ?: stableSyncId(rec.id),
                    updatedAt = if (rec.dirty || stale) stamp else rec.updatedAt,
                    deviceId = if (rec.dirty || rec.deviceId == null) deviceId else rec.deviceId
                )
            })
        }

        override suspend fun hasDirty(hash: String): Boolean {
            if (!AccountScope.validAccountHash(hash)) return false
            val deviceId = DeviceIdProvider.getDeviceId(context)
            return AccountScope.loadStyles(context, hash).any { it.toLocal(hash, deviceId).dirty }
        }

        override suspend fun applyMergedAndClearDirty(
            hash: String,
            deviceId: String,
            merged: List<StyleRecord>
        ) {
            if (!AccountScope.validAccountHash(hash)) return
            // STAGE 4: structural partitioning replaces the ownership filter.
            // See the agents counterpart above for the full rationale; styles
            // have the same provenance guarantee via
            // `fluence/v1/acct-<hash>/styles.json`.
            AccountScope.saveStyles(
                context, hash,
                merged.map { rec ->
                    AccountScope.AccountStyle(
                        id = rec.businessKey,
                        name = rec.name,
                        hint = rec.hint,
                        syncId = rec.syncId,
                        updatedAt = rec.updatedAt,
                        deviceId = rec.deviceId,
                        // Winner-only tombstone state; see the agents counterpart
                        // for why a losing `deletedAt` must never be grafted on.
                        deletedAt = rec.deletedAt,
                        // Merged back in: already in the sync state, so clean.
                        // Without this the flag would survive the merge and
                        // every pass would re-upload the row.
                        dirty = false
                    )
                }
            )
        }
    }

    fun metadataDao(context: Context): SyncMetadataDao =
        FluenceDatabase.getInstance(context.applicationContext).syncMetadataDao()
}

object MutationClock {
    fun next(context: Context): Long {
        return try {
            val hash = try { AccountHash.of(SyncAuthSession(context.applicationContext).accountEmail) } catch (_: Exception) { null }
            val seen = if (hash != null) {
                try { runBlocking { V1Stores.metadataDao(context).getByHash(hash)?.maxSeen ?: 0L } } catch (_: Exception) { 0L }
            } else 0L
            val wall = System.currentTimeMillis()
            // Same sticky-future-clock cap as Clock.nextUpdatedAt: a runaway
            // wall clock must not inflate this edit's stamp forever.
            minOf(maxOf(wall, seen + 1L), wall + Clock.MAX_CLOCK_SKEW_MS)
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}

fun decideDictionaryApply(currentUpdatedAt: Long?, currentDeviceId: String?, currentDirty: Boolean, rec: com.groq.voicetyper.sync.v1.DictionaryRecord): Boolean {
    if (!currentDirty) return true
    val curAt = currentUpdatedAt ?: 0L
    val curDev = currentDeviceId ?: ""
    return Clock.compareWinner(curAt, curDev, rec.updatedAt, rec.deviceId) <= 0
}

/**
 * Pure retention decision for the snippet apply pass (dictionary-store
 * parity). Rows owned by a DIFFERENT account survive untouched (they belong
 * to a previous sign-in and must never be dropped by this account's pass);
 * rows with no wire identity or a pending local change survive even when
 * absent from the merge (unsynced new/edit — they ride the next PUT);
 * current-account clean rows survive only when the merge still holds them —
 * absence means a newer remote tombstone won elsewhere.
 */
fun decideSnippetRetention(local: List<Snippet>, mergedIds: Set<String>, currentAccountHash: String): List<Snippet> {
    return local.filter { s ->
        val sid = s.effectiveSyncId()
        when {
            s.syncAccount != null && s.syncAccount != currentAccountHash -> true
            sid == null || s.dirty -> true
            else -> sid in mergedIds
        }
    }
}

/**
 * Mid-pass dirty guard for one merged snippet (decideDictionaryApply
 * parity): a locally-dirty row newer than the merged winner keeps its local
 * content and dirty flag so it rides the next PUT instead of being clobbered.
 */
fun decideSnippetApply(current: Snippet, rec: SnippetRecord): Boolean {
    if (!current.dirty) return true
    val curAt = current.updatedAt ?: 0L
    val curDev = current.deviceId ?: ""
    return Clock.compareWinner(curAt, curDev, rec.updatedAt, rec.deviceId) <= 0
}

/**
 * Contract defense: quarantined rows must be impossible to serialize via the
 * sync store even if a future writer stamps quarantineReason — they are
 * filtered out of every sync load.
 */
fun excludeQuarantined(rows: List<CustomDictionaryEntry>): List<CustomDictionaryEntry> =
    rows.filter { it.quarantineReason == null }

// ----------------------------------------------------------------------
// Dictionary
// ----------------------------------------------------------------------

class RoomDictionaryV1Store(
    private val db: FluenceDatabase,
    private val context: Context
) : V1SyncEngine.DictionaryV1Store {

    private val dao: CustomDictionaryDao = db.customDictionaryDao()
    private val metaDao: SyncMetadataDao = db.syncMetadataDao()

    override suspend fun loadByAccount(hash: String): List<V1SyncEngine.DictionaryLocal> =
        excludeQuarantined(dao.getAllByAccount(hash)).map { it.toLocal() }

    override suspend fun stampUnstamped(hash: String) {
        // Enrollment: claim rows with no account ownership, assign wire
        // identities to legacy rows, then give any stamped row lacking an LWW
        // timestamp a valid one (monotonic clock). Without this, a legacy row
        // would serialize updatedAt=0 and fail cross-platform validation.
        val claimed = dao.getSyncRows(hash)
        if (claimed.isEmpty()) return
        val meta = metaDao.getByHash(hash)
        val fallbackDeviceId = meta?.deviceId.orEmpty()
        val nextTs = Clock.nextUpdatedAt(Clock.nowWallMs(), meta?.maxSeen ?: 0L)
        for (row in claimed) {
            val owned = row.syncAccount == hash
            val needsRepair = row.syncAccount == null ||
                (owned && (row.syncId.isNullOrBlank() ||
                    (row.updatedAt ?: 0L) <= 0L ||
                    row.deviceId.isNullOrBlank()))
            if (needsRepair) {
                val updated = if ((row.updatedAt ?: 0L) <= 0L)
                    (row.createdAt?.takeIf { it > 0 } ?: nextTs) else row.updatedAt
                val fixed = row.copy(
                    syncAccount = hash,
                    syncId = row.syncId?.takeIf { it.isNotBlank() }
                        ?: java.util.UUID.randomUUID().toString(),
                    updatedAt = updated,
                    deviceId = row.deviceId?.takeIf { it.isNotBlank() }
                        ?: fallbackDeviceId.ifBlank { DeviceIdProvider.getDeviceId(context) },
                    dirty = true,
                    everPushed = false
                )
                dao.update(fixed)
            }
        }
    }

    private fun nowWallMs(): Long = System.currentTimeMillis()

    override suspend fun hasDirty(hash: String): Boolean =
        dao.getDirtyByAccount(hash).isNotEmpty()

    override suspend fun applyMergedAndClearDirty(
        hash: String,
        deviceId: String,
        merged: List<DictionaryRecord>
    ) = db.withTransaction {
        val existing = dao.getAllByAccount(hash)
        val bySyncId = existing.associateBy { it.syncId }
        // Replace only clean rows that lost the merge. A dirty row may have
        // been created or edited after the pre-pass snapshot; retaining it is
        // required so a successful network pass cannot erase a concurrent
        // local change. It will be reconciled on the next pass.
        val mergedIds = merged.map { it.syncId }.toSet()
        for (row in existing) {
            if (row.syncId !in mergedIds && !row.dirty) {
                dao.hardDeleteBySyncId(row.syncId ?: continue)
            }
        }
        val skipClear = mutableSetOf<String>()
        val appliedIds = mutableListOf<String>()
        for (rec in merged) {
            val current = bySyncId[rec.syncId]
            if (current != null) {
                if (!decideDictionaryApply(current.updatedAt, current.deviceId, current.dirty, rec)) {
                    skipClear.add(rec.syncId)
                    continue
                }
                dao.update(
                    current.copy(
                        spokenText = rec.spoken,
                        replacementText = rec.corrected,
                        isEnabled = rec.isEnabled,
                        deletedAt = rec.deletedAt,
                        updatedAt = rec.updatedAt,
                        deviceId = rec.deviceId,
                        syncAccount = hash,
                        dirty = false,
                        everPushed = true
                    )
                )
                appliedIds.add(rec.syncId)
            } else {
                // Remote-won record new to this device. Replace a losing
                // current-account row in place so the account-scoped unique
                // key remains valid without silently ignoring the import.
                val collision = dao.getByBusinessKeyIncludingDeleted(rec.businessKey, hash)
                if (collision != null && collision.syncId != rec.syncId && collision.syncId != null) {
                    // Mid-pass LWW guard (mirrors decideDictionaryApply on the bySyncId
                    // path above): if a concurrent write made this collision row dirty
                    // and it wins LWW over the remote winner since loadByAccount ran,
                    // leave it dirty to ride next PUT.
                    if (collision.dirty && Clock.compareWinner(collision.updatedAt ?: 0L, collision.deviceId ?: "", rec.updatedAt, rec.deviceId) > 0) {
                        continue
                    }
                    dao.update(
                        collision.copy(
                            spokenText = rec.spoken,
                            replacementText = rec.corrected,
                            isEnabled = rec.isEnabled,
                            syncId = rec.syncId,
                            createdAt = rec.updatedAt,
                            deletedAt = rec.deletedAt,
                            updatedAt = rec.updatedAt,
                            deviceId = rec.deviceId,
                            syncAccount = hash,
                            dirty = false,
                            everPushed = true
                        )
                    )
                    appliedIds.add(rec.syncId)
                    continue
                }
                val insertedId = dao.insert(
                    CustomDictionaryEntry(
                        spokenText = rec.spoken,
                        replacementText = rec.corrected,
                        isEnabled = rec.isEnabled,
                        syncId = rec.syncId,
                        createdAt = rec.updatedAt,
                        deletedAt = rec.deletedAt,
                        updatedAt = rec.updatedAt,
                        syncAccount = hash,
                        deviceId = rec.deviceId,
                        dirty = false,
                        everPushed = true
                    )
                )
                if (insertedId != -1L || dao.getBySyncId(rec.syncId) != null) {
                    appliedIds.add(rec.syncId)
                }
            }
        }
        val toClear = appliedIds.filterNot { it in skipClear }
        if (toClear.isNotEmpty()) {
            dao.clearDirtyBySyncIds(hash, toClear)
        }
    }

    private fun CustomDictionaryEntry.toLocal() = V1SyncEngine.DictionaryLocal(
        syncId = syncId ?: "",
        businessKey = DictionaryRecord.businessKeyOf(spokenText),
        spoken = spokenText,
        corrected = replacementText,
        isEnabled = isEnabled,
        updatedAt = updatedAt ?: 0L,
        deletedAt = deletedAt,
        deviceId = deviceId ?: "",
        accountHash = syncAccount,
        dirty = dirty,
        everPushed = everPushed
    )
}

// ----------------------------------------------------------------------
// Stats
// ----------------------------------------------------------------------

class RoomStatV1Store(
    private val db: com.groq.voicetyper.history.FluenceDatabase,
    private val statDao: StatSyncDao,
    private val historyDao: com.groq.voicetyper.history.TranscriptionHistoryDao,
    private val statsDao: com.groq.voicetyper.history.StatsDao,
    private val metadataDao: SyncMetadataDao
) : V1SyncEngine.StatV1Store {

    override suspend fun loadByAccount(hash: String): List<V1SyncEngine.StatLocal> =
        statDao.getByAccount(hash).map { it.toLocal() }

    override suspend fun stampUnstamped(hash: String) {
        statDao.stampUnstamped(hash)
    }

    override suspend fun hasDirty(hash: String): Boolean =
        statDao.getDirtyByAccount(hash).isNotEmpty()

    override suspend fun applyMergedAndClearDirty(
        hash: String,
        deviceId: String,
        merged: List<StatRecord>
    ) = db.withTransaction {
        val toClear = mutableListOf<String>()
        for (rec in merged) {
            val before = statDao.getByEventId(rec.eventId)
            // Never let one account's event id overwrite another account's
            // local row. Event ids are unique in the legacy Room schema.
            if (before?.accountHash != null && before.accountHash != hash) continue

            // A local edit made after the GET→PUT snapshot still wins if its
            // LWW stamp is newer (or ties on the device id). Otherwise the
            // merged remote winner must replace the stale dirty row; INSERT
            // IGNORE would leave that row dirty forever.
            if (before != null && before.dirty &&
                (before.updatedAt > rec.updatedAt ||
                    (before.updatedAt == rec.updatedAt &&
                        (before.deviceId ?: "") >= rec.deviceId))) {
                continue
            }

            val replacement = StatSyncEntry(
                id = before?.id ?: 0L,
                eventId = rec.eventId,
                day = rec.day,
                wordCount = rec.wordCount,
                durationMs = rec.durationMs,
                updatedAt = rec.updatedAt,
                deletedAt = rec.deletedAt,
                deviceId = rec.deviceId,
                accountHash = hash,
                dirty = false,
                everPushed = true,
                chars = rec.chars,
                timestampMs = rec.timestampMs
            )
            if (before == null) {
                if (statDao.insertIgnore(replacement) != -1L) {
                    // newly inserted
                    toClear.add(rec.eventId)
                }
            } else {
                statDao.insert(replacement)
                toClear.add(rec.eventId)
            }
        }
        if (toClear.isNotEmpty()) {
            statDao.clearDirtyByEventIds(hash, toClear.distinct())
        }
    }

    override suspend fun isBackfillDone(hash: String): Boolean =
        metadataDao.getByHash(hash)?.backfillDone == true

    override suspend fun setBackfillDone(hash: String, done: Boolean) {
        if (done) metadataDao.markBackfillDone(hash)
    }

    /**
     * One-time seed per accountHash. Single source: live transcription_history
     * rows when any exist, else stats_daily aggregates. UTC day bucketing;
     * deterministic eventIds make re-runs idempotent under union dedup.
     */
    override suspend fun backfillIfNeeded(hash: String, deviceId: String): Boolean = db.withTransaction {
        val now = System.currentTimeMillis()
        val liveRows = historyDao.getAllLiveRows()
        val records = if (liveRows.isNotEmpty()) {
            Backfill.fromTranscriptionRows(
                liveRows.map { row ->
                    val stableSyncId = row.syncId?.takeIf { it.isNotBlank() }
                        ?: Backfill.syncIdForHistoryRow(row.id).also { historyDao.assignSyncId(row.id, it) }
                    Backfill.TranscriptionRowLite(
                        timestampMs = row.timestamp,
                        wordCount = StatsCalculator.wordCountOf(row.text),
                        durationMs = row.durationMs,
                        syncId = stableSyncId,
                        chars = row.text.length
                    )
                },
                hash, deviceId, now
            )
        } else {
            Backfill.fromDailyStats(
                statsDao.getAllOnce().map { Backfill.DailyStatLite(it.day, it.wordCount.toInt(), it.dictationMs) },
                hash, deviceId, now
            )
        }
        var inserted = 0
        for (rec in records) {
            if (statDao.getByEventId(rec.eventId) == null) {
                statDao.insertIgnore(
                    StatSyncEntry(
                        eventId = rec.eventId,
                        day = rec.day,
                        wordCount = rec.wordCount,
                        durationMs = rec.durationMs,
                        updatedAt = rec.updatedAt,
                        deletedAt = null,
                        deviceId = deviceId,
                        accountHash = hash,
                        dirty = true,
                        everPushed = false,
                        chars = rec.chars,
                        timestampMs = rec.timestampMs
                    )
                )
                inserted++
            }
        }
        records.isNotEmpty()
    }

    private fun StatSyncEntry.toLocal() = V1SyncEngine.StatLocal(
        eventId = eventId,
        day = day,
        wordCount = wordCount,
        durationMs = durationMs,
        updatedAt = updatedAt,
        deviceId = deviceId ?: "",
        deletedAt = deletedAt,
        accountHash = accountHash,
        dirty = dirty,
        everPushed = everPushed,
        timestampMs = timestampMs,
        chars = chars
    )
}

// ----------------------------------------------------------------------
// Snippets (SharedPreferences JSON document)
// ----------------------------------------------------------------------

class PrefsSnippetV1Store(private val context: Context) : V1SyncEngine.SnippetV1Store {

    override suspend fun loadByAccount(hash: String): List<V1SyncEngine.SnippetLocal> =
        SnippetPreferences.allEntries(context)
            .filter { it.syncAccount == hash }
            .map { s ->
                V1SyncEngine.SnippetLocal(
                    syncId = s.effectiveSyncId() ?: "",
                    businessKey = s.businessKey(),
                    trigger = s.trigger,
                    expansion = s.expansion,
                    isEnabled = s.isEnabled,
                    updatedAt = s.updatedAt ?: s.createdAt ?: 0L,
                    deletedAt = s.deletedAt,
                    deviceId = s.deviceId ?: "",
                    accountHash = s.syncAccount,
                    dirty = s.dirty,
                    everPushed = s.everPushed
                )
            }

    override suspend fun stampUnstamped(hash: String) {
        val all = SnippetPreferences.allEntries(context)
        val needsStamp = all.any {
            it.syncAccount == null ||
                (it.syncAccount == hash && (it.effectiveSyncId().isNullOrBlank() ||
                    (it.updatedAt ?: 0L) <= 0L || it.deviceId.isNullOrBlank()))
        }
        if (!needsStamp) return
        val now = System.currentTimeMillis()
        SnippetPreferences.saveAll(context, all.map { s ->
            if (s.syncAccount == null || s.syncAccount == hash &&
                (s.effectiveSyncId().isNullOrBlank() || (s.updatedAt ?: 0L) <= 0L || s.deviceId.isNullOrBlank())) {
                s.copy(
                    uuid = s.effectiveSyncId() ?: java.util.UUID.randomUUID().toString(),
                    syncAccount = hash,
                    deviceId = s.deviceId?.takeIf { it.isNotBlank() } ?: DeviceIdProvider.getDeviceId(context),
                    createdAt = s.createdAt?.takeIf { it > 0L } ?: now,
                    updatedAt = s.updatedAt?.takeIf { it > 0L } ?: s.createdAt?.takeIf { it > 0L } ?: now,
                    dirty = true,
                    everPushed = false
                )
            } else s
        })
    }

    override suspend fun hasDirty(hash: String): Boolean =
        SnippetPreferences.allEntries(context).any { it.syncAccount == hash && it.dirty }

    override suspend fun applyMergedAndClearDirty(
        hash: String,
        deviceId: String,
        merged: List<SnippetRecord>
    ) {
        val all = SnippetPreferences.allEntries(context)
        // Retention first (decideSnippetRetention): foreign-account rows and
        // unsynced/dirty rows survive untouched; current-account clean rows
        // absent from the merge were tombstoned elsewhere and are dropped.
        val kept = decideSnippetRetention(all, merged.map { it.syncId }.toSet(), hash).toMutableList()
        var nextFree = ((kept.maxOfOrNull { it.id } ?: 0L)) + 1L
        for (rec in merged) {
            val idx = kept.indexOfFirst { it.effectiveSyncId() == rec.syncId }
            if (idx >= 0) {
                val current = kept[idx]
                // Never restamp another account's row; defer to a newer dirty
                // local edit created during the GET→PUT window.
                if (current.syncAccount != null && current.syncAccount != hash) continue
                if (!decideSnippetApply(current, rec)) continue
                kept[idx] = current.copy(
                    trigger = rec.trigger,
                    expansion = rec.expansion,
                    isEnabled = rec.isEnabled,
                    deletedAt = rec.deletedAt,
                    updatedAt = rec.updatedAt,
                    deviceId = rec.deviceId,
                    syncAccount = hash,
                    dirty = false,
                    everPushed = true
                )
            } else {
                kept.add(
                    Snippet(
                        id = nextFree++,
                        trigger = rec.trigger,
                        expansion = rec.expansion,
                        isEnabled = rec.isEnabled,
                        uuid = rec.syncId,
                        createdAt = rec.updatedAt,
                        updatedAt = rec.updatedAt,
                        deletedAt = rec.deletedAt,
                        syncAccount = hash,
                        deviceId = rec.deviceId,
                        dirty = false,
                        everPushed = true
                    )
                )
            }
        }
        SnippetPreferences.saveAll(context, kept)
    }
}
