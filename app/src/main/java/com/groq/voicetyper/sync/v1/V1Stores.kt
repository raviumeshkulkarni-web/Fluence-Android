package com.groq.voicetyper.sync.v1

import android.content.Context
import com.groq.voicetyper.dictionary.data.CustomDictionaryDao
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.history.FluenceDatabase
import com.groq.voicetyper.history.StatsCalculator
import com.groq.voicetyper.snippets.Snippet
import androidx.room.withTransaction
import com.groq.voicetyper.snippets.SnippetPreferences
import com.groq.voicetyper.sync.SyncAccounts
import com.groq.voicetyper.sync.auth.SyncAuthSession
import kotlinx.coroutines.CancellationException

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
            // Shares the claim's monitor. Without this, a claim that has already
            // saved and reported success could be discarded by this function
            // saving the older snapshot it loaded — after which the claim's caller
            // deletes the legacy copy and the record exists in NEITHER store.
            // This is the minimum mutual exclusion required for the destructive
            // claim step; it is NOT the general B2/R1 account-store fix.
            synchronized(AccountScope.accountStoreLock) {
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
            //
            // RECONCILE (mirror of Windows `AgentDirtyStore::save_merged`):
            // `merged` is this pass's LWW result, computed from a snapshot taken
            // BEFORE the Drive round trip. A local create/edit/delete that landed
            // meanwhile exists only in the local store, so writing `merged`
            // wholesale destroyed it - a row created during a pass ended up in no
            // store at all. The current rows are therefore re-read INSIDE
            // `accountStoreLock`, which every local account writer also takes, so
            // nothing can interleave between the check and the write.
            synchronized(AccountScope.accountStoreLock) {
                val current = AccountScope.loadAgents(context, hash)
                val winners = merged.associateBy { it.businessKey }
                val surviving = mutableListOf<AccountScope.AccountAgent>()
                for (e in current) {
                    val winner = winners[e.id]
                    when {
                        // No winner for this id: a row created during the pass.
                        // Keep it exactly as stored - dirty flag included - so the
                        // next pass uploads it.
                        winner == null -> surviving += e
                        // Keep a row whose mutation is not uploaded yet. `dirty`
                        // is the signal, not the timestamp: a local edit does not
                        // restamp `updatedAt` (that belongs to the next pass), so
                        // it still carries its pre-edit revision and would lose any
                        // pure LWW comparison to the payload just uploaded. Once
                        // the content EQUALS the winner, this edit IS what was
                        // uploaded, so the winner is authoritative and the row is
                        // written back clean - without that check the flag would
                        // re-upload on every pass forever.
                        else -> {
                            val alreadyUploaded = e.name == winner.name &&
                                e.hint == winner.hint &&
                                e.deletedAt == winner.deletedAt
                            if (e.toLocal(hash, deviceId).dirty && !alreadyUploaded) {
                                surviving += e
                            }
                        }
                    }
                }
                val winnerKeys = merged.map { it.businessKey }.toSet()
                // Never-pushed tombstone purge - identical to the Windows store:
                // drop a tombstone only when it has NO counterpart in this
                // pass's payload (created and deleted while never synced, so it
                // carries nothing a peer could need). A tombstone whose record
                // WAS pushed must survive, or the next pass resurrects it.
                val kept = surviving.filterNot { e ->
                    e.deletedAt != null && e.syncId.isNullOrEmpty() && e.id !in winnerKeys
                }
                val keptIds = kept.map { it.id }.toSet()
                AccountScope.saveAgents(
                    context, hash,
                    kept + merged.filter { it.businessKey !in keptIds }.map { rec ->
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
            // Shares the claim's monitor — see AccountAgentV1Store.
            synchronized(AccountScope.accountStoreLock) {
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
            //
            // RECONCILE: identical rule to the agents counterpart - re-read the
            // current rows inside `accountStoreLock` and keep any row whose
            // mutation is not uploaded yet, instead of overwriting it with the
            // pass's pre-network snapshot.
            synchronized(AccountScope.accountStoreLock) {
                val current = AccountScope.loadStyles(context, hash)
                val winners = merged.associateBy { it.businessKey }
                val surviving = mutableListOf<AccountScope.AccountStyle>()
                for (e in current) {
                    val winner = winners[e.id]
                    when {
                        winner == null -> surviving += e
                        else -> {
                            val alreadyUploaded = e.name == winner.name &&
                                e.hint == winner.hint &&
                                e.deletedAt == winner.deletedAt
                            if (e.toLocal(hash, deviceId).dirty && !alreadyUploaded) {
                                surviving += e
                            }
                        }
                    }
                }
                val winnerKeys = merged.map { it.businessKey }.toSet()
                // Never-pushed tombstone purge; see the agents counterpart.
                val kept = surviving.filterNot { e ->
                    e.deletedAt != null && e.syncId.isNullOrEmpty() && e.id !in winnerKeys
                }
                val keptIds = kept.map { it.id }.toSet()
                AccountScope.saveStyles(
                    context, hash,
                    kept + merged.filter { it.businessKey !in keptIds }.map { rec ->
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
    }

    fun metadataDao(context: Context): SyncMetadataDao =
        FluenceDatabase.getInstance(context.applicationContext).syncMetadataDao()
}

/**
 * Monotonic revision stamps for local user mutations.
 *
 * **#28 — the floor is read under the account the sync pass actually writes
 * under.** The pass entry (`SyncManager`) resolves the identity from the live
 * access token and publishes it through `SyncAccounts.publishAuthenticated`;
 * [DriveIdentity] documents exactly what sourcing identity from a locally
 * persisted string costs — "writing one account's payload into another
 * account's appDataFolder". This function used to read `maxSeen` under
 * `AccountHash.of(SyncAuthSession(...).accountEmail)` instead, so a
 * persisted-vs-verified mismatch stamped local edits from the wrong
 * partition's high-water mark. The invariant is now enforced here too, from the
 * same accessor, instead of being assumed.
 *
 * **#30 — no `runBlocking` bridge.** Every caller is a user mutation reached
 * from a Compose screen on `Main.immediate`, so bridging the metadata read with
 * `runBlocking` performed synchronous disk I/O on the main thread for every
 * dictionary/snippet edit (jank, ANR exposure). [next] is `suspend` and reads
 * on the caller's dispatcher; [nextCached] is the zero-I/O variant for the
 * non-suspending snippet writers, which reuse the floor the last suspending
 * read cached.
 *
 * **Failures are surfaced, never silent.** The old body stacked three empty
 * `catch` blocks, so an unreadable database degraded invisibly to a raw
 * `System.currentTimeMillis()` — dropping the monotonic floor whose entire
 * purpose is to stop LWW ties. The stamp is still produced (failing a user's
 * save is worse than losing the floor for one edit) but the degradation is
 * recorded in [lastFloorFailure] and logged.
 */
object MutationClock {

    /**
     * Test seam: the `maxSeen` read. Production goes through
     * [V1Stores.metadataDao]; tests substitute a deterministic reader so the
     * floor is exercised without Room. `null` = production.
     */
    @Volatile
    internal var maxSeenReader: (suspend (Context, String) -> Long?)? = null

    /**
     * Test seam for the PROVISIONAL identity, used only while no
     * token-verified identity exists. Production reads the persisted sign-in
     * email out of [SyncAuthSession].
     */
    @Volatile
    internal var provisionalIdentity: (Context) -> String? = { ctx ->
        AccountHash.of(SyncAuthSession(ctx.applicationContext).accountEmail)
    }

    /**
     * Why the last metadata read failed, or `null` after a successful read.
     * Diagnostic only — a mutation is never refused because of it.
     */
    @Volatile
    var lastFloorFailure: String? = null
        private set

    /** Floor of the last successful read, and the partition it belongs to. */
    @Volatile
    private var cachedHash: String? = null

    @Volatile
    private var cachedMaxSeen: Long = 0L

    /** Drop the cached floor and any test seams. */
    internal fun resetForTest() {
        maxSeenReader = null
        provisionalIdentity = { AccountHash.of(SyncAuthSession(it.applicationContext).accountEmail) }
        lastFloorFailure = null
        cachedHash = null
        cachedMaxSeen = 0L
    }

    /**
     * Next revision stamp for a user mutation. Suspends on the caller's
     * dispatcher — never blocks it — and refreshes [nextCached]'s floor.
     */
    suspend fun next(context: Context): Long {
        val hash = resolveHash(context)
        val seen = if (hash == null) 0L else readMaxSeen(context, hash)
        return stamp(System.currentTimeMillis(), seen)
    }

    /**
     * Zero-I/O variant for non-suspending UI writers (snippet save/delete).
     *
     * Uses the floor cached by the last [next] read, and only when it belongs
     * to the account the current identity resolves to. With no cache (process
     * start, no pass, no suspending read yet) the stamp falls back to the wall
     * clock — which is the pre-existing cold-start behaviour, minus the
     * main-thread disk read.
     */
    fun nextCached(): Long {
        val hash = resolveHashCached()
        val seen = if (hash != null && hash == cachedHash) cachedMaxSeen else 0L
        return stamp(System.currentTimeMillis(), seen)
    }

    /**
     * Same sticky-future-clock cap as [Clock.nextUpdatedAt]: a runaway wall
     * clock must not inflate this edit's stamp forever.
     */
    internal fun stamp(wallMs: Long, maxSeen: Long): Long = Clock.nextUpdatedAt(wallMs, maxSeen)

    /**
     * The partition the floor is read under. A token-verified identity is
     * authoritative and is NEVER mixed with the persisted one; only when no
     * verified identity exists at all does the provisional value apply.
     */
    private fun resolveHash(context: Context): String? =
        if (SyncAccounts.tokenVerified) {
            SyncAccounts.currentAccountHash.value
        } else {
            try {
                provisionalIdentity(context)
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                null
            }
        }

    /** Same rule as [resolveHash], reading only in-memory process state. */
    private fun resolveHashCached(): String? =
        if (SyncAccounts.tokenVerified) {
            SyncAccounts.currentAccountHash.value
        } else {
            AccountHash.of(SyncAccounts.cachedAccount)
        }

    private suspend fun readMaxSeen(context: Context, hash: String): Long {
        return try {
            val reader = maxSeenReader
            val seen = if (reader != null) {
                reader(context, hash)
            } else {
                V1Stores.metadataDao(context).getByHash(hash)?.maxSeen ?: 0L
            } ?: 0L
            lastFloorFailure = null
            cachedHash = hash
            cachedMaxSeen = seen
            seen
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val reason = "maxSeen read failed for $hash: ${t.javaClass.simpleName}: ${t.message}"
            lastFloorFailure = reason
            // android.util.Log is stubbed to defaults in JVM unit tests, but a
            // diagnostic sink must never be the thing that throws.
            runCatching { android.util.Log.w("MutationClock", reason) }
            0L
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
 * Phase D (audit finding H3) — superseded-row selection for the dictionary
 * apply pass. The merge collapse drops a same-`syncId` loser that sits under
 * a different business key (rename), but the sync-id-keyed hard-delete in
 * [RoomDictionaryV1Store.applyMergedAndClearDirty] only removes rows whose
 * `syncId` is ABSENT from the merge — the superseded row's `syncId` is
 * present (the winner shares it), so without this rule it survives locally
 * forever as a visible duplicate while Drive converges. Selects clean rows
 * whose `syncId` is in the merge but whose business key is not: those lost
 * the collapse. Dirty rows are never selected — a mid-pass mutation rides
 * the next PUT (H5 invariant). Quarantined rows are never selected either:
 * they are held for recovery, excluded from the merge input
 * (`excludeQuarantined`), and must not be destroyed by a reconcile that never
 * saw them. Input is `dao.getAllByAccount(hash)`, already account-scoped, so
 * no ownership clause is needed here.
 *
 * Snippet parity note: this exemption was once claimed because "snippets have
 * no quarantine exclusion in their load path". Commit 54d0277 added
 * `it.quarantineReason == null` to `PrefsSnippetV1Store.loadByAccount`, which
 * removed the premise and left the exemption behind — a quarantined snippet
 * entered no merge and was then dropped by retention. Both snippet decisions
 * now carry the same `quarantineReason` guard the dictionary paths do.
 */
fun decideDictionarySupersededDeletions(
    existing: List<com.groq.voicetyper.dictionary.data.CustomDictionaryEntry>,
    merged: List<DictionaryRecord>
): List<com.groq.voicetyper.dictionary.data.CustomDictionaryEntry> {
    val mergedIds = merged.map { it.syncId }.toSet()
    val mergedKeys = merged.map { it.businessKey }.toSet()
    return existing.filter { row ->
        !row.dirty && row.quarantineReason == null && row.syncId in mergedIds &&
            DictionaryRecord.businessKeyOf(row.spokenText) !in mergedKeys
    }
}

/**
 * Pure retention decision for the snippet apply pass (dictionary-store
 * parity). Rows owned by a DIFFERENT account survive untouched (they belong
 * to a previous sign-in and must never be dropped by this account's pass);
 * QUARANTINED rows survive untouched (they are held for recovery and, since
 * 54d0277, excluded from the merge input, so their sync id can never be in
 * [mergedIds] — without this guard retention deleted exactly the rows the
 * quarantine exists to hold); rows with no wire identity or a pending local
 * change survive even when absent from the merge (unsynced new/edit — they
 * ride the next PUT); current-account clean rows survive only when the merge
 * still holds them — absence means a newer remote tombstone won elsewhere.
 */
fun decideSnippetRetention(local: List<Snippet>, mergedIds: Set<String>, currentAccountHash: String): List<Snippet> {
    return local.filter { s ->
        val sid = s.effectiveSyncId()
        when {
            s.syncAccount != null && s.syncAccount != currentAccountHash -> true
            s.quarantineReason != null -> true
            sid == null || s.dirty -> true
            else -> sid in mergedIds
        }
    }
}

/**
 * Phase D (audit finding H3) — snippet counterpart of
 * [decideDictionarySupersededDeletions]. [decideSnippetRetention] keeps any
 * row whose sync id is present in the merge, so a same-`syncId` loser under
 * a pre-rename trigger (whose id the collapse winner shares) would survive
 * locally forever as a visible duplicate. Drops clean current-account rows
 * whose sync id is merged but whose trigger key is not. Dirty rows survive
 * (mid-pass edit rides the next PUT — H5 invariant); foreign-account rows
 * and rows without wire identity are untouched.
 */
fun decideSnippetSupersededDrops(
    kept: List<Snippet>,
    merged: List<SnippetRecord>,
    currentAccountHash: String
): List<Snippet> {
    val mergedIds = merged.map { it.syncId }.toSet()
    val mergedKeys = merged.map { it.businessKey }.toSet()
    return kept.filterNot { s ->
        s.quarantineReason == null &&
            s.syncAccount == currentAccountHash && !s.dirty &&
            s.effectiveSyncId() in mergedIds &&
            SnippetRecord.businessKeyOf(s.trigger) !in mergedKeys
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

/**
 * Sync-id keyed hard-delete selection for the dictionary apply pass.
 *
 * A clean row whose `syncId` is absent from [mergedIds] lost LWW and is
 * dropped. Two shapes must never be selected:
 *
 *  * **Dirty rows** — a create/edit/delete that landed after the pre-pass
 *    snapshot. Retaining it is what stops a successful network pass from
 *    erasing a concurrent local change; it reconciles on the next pass.
 *  * **Quarantined rows** — they are held for recovery and are excluded from
 *    the merge input by [excludeQuarantined], so their `syncId` can NEVER
 *    appear in [mergedIds]. `quarantineBySyncId` does not set `dirty`, so a
 *    quarantined CLEAN row satisfied both conditions and was hard-deleted,
 *    destroying exactly the rows the quarantine exists to hold. Windows keeps
 *    them — `stores.rs`: "F3: quarantined rows are held for recovery; the
 *    wholesale replacement must not wipe them", pinned by
 *    `save_merged_keeps_quarantined_dict_row`. This is the guard the sibling
 *    [decideDictionarySupersededDeletions] already had and this primary path
 *    was missing.
 *
 * A row with no `syncId` is unstamped and never selected: there is no id to
 * delete by, and `stampUnstamped` gives it one. Input is
 * `dao.getAllByAccount(hash)`, already account-scoped, so no ownership clause
 * is needed here.
 */
fun decideDictionaryHardDeletes(
    existing: List<CustomDictionaryEntry>,
    mergedIds: Set<String>
): List<String> = existing.mapNotNull { row ->
    val syncId = row.syncId ?: return@mapNotNull null
    if (row.dirty || row.quarantineReason != null) return@mapNotNull null
    if (syncId in mergedIds) null else syncId
}

/**
 * Business-key collision lookup for one merged winner that is new to this
 * device (no local row carries its `syncId`).
 *
 * The identity is derived in KOTLIN, never in SQL. Stock Android SQLite's
 * `lower()` folds ASCII only, so `lower(trim("Über"))` stays `"Über"` while
 * the Kotlin key is `"über"`: the lookup missed, the insert branch ran,
 * `@Insert(onConflict = IGNORE)` was swallowed by the unique
 * `(spokenText, syncAccount)` index, `getBySyncId` returned null because the
 * colliding row carries a different id, and the merged winner became
 * INVISIBLE on the phone while Drive and desktop held it — permanently, with
 * no error. ASCII `trim()` diverges the same way, so fixing case alone would
 * leave the split. The Rust twin derives the key in Rust and has no `lower(`
 * in SQL, which is why this was Android-only.
 *
 * [candidates] must be the account's rows INCLUDING tombstones — a newer
 * remote winner has to be able to replace a tombstoned row, which is what
 * `getByBusinessKeyIncludingDeleted` allowed. The lowest row id wins, so the
 * choice is deterministic (the old `LIMIT 1` had no `ORDER BY`).
 *
 * Frozen contract: the key is recomputed from [DictionaryRecord.spoken]; the
 * wire `businessKey` is never trusted.
 */
fun findBusinessKeyCollision(
    candidates: List<CustomDictionaryEntry>,
    rec: DictionaryRecord
): CustomDictionaryEntry? {
    val key = DictionaryRecord.businessKeyOf(rec.spoken)
    if (key.isEmpty()) return null
    return candidates
        .filter { DictionaryRecord.businessKeyOf(it.spokenText) == key }
        .minByOrNull { it.id }
}

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
        // One transaction for the whole repair sweep. Each `dao.update` is
        // otherwise its own implicit transaction — its own journal commit and
        // fsync — so an account with N unenrolled rows cost N durable writes and
        // a process kill mid-loop left a partially-stamped store: some rows
        // carrying syncId/updatedAt and others still un-stamped, which then
        // serialized with updatedAt=0 and failed cross-platform validation on the
        // next pass. Every other multi-row operation in this file wraps its loop.
        db.withTransaction {
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
        // Replace only clean rows that lost the merge. A dirty row may have
        // been created or edited after the pre-pass snapshot; retaining it is
        // required so a successful network pass cannot erase a concurrent
        // local change. It will be reconciled on the next pass. Quarantined
        // rows are held for recovery and are never in the merge input, so they
        // are never selected — see decideDictionaryHardDeletes.
        val mergedIds = merged.map { it.syncId }.toSet()
        for (syncId in decideDictionaryHardDeletes(existing, mergedIds)) {
            dao.hardDeleteBySyncId(syncId)
        }
        // Phase D (H3): drop clean superseded rows — same syncId as the
        // winner, loser business key (rename). Deleted by row id:
        // hardDeleteBySyncId would also remove the survivor sharing the id.
        // The survivor view is re-read afterwards so the apply loop below
        // indexes post-delete rows only and cannot resurrect the ghost
        // through a stale `bySyncId` reference (which would `update` a
        // deleted row into a no-op and skip writing the winner).
        for (row in decideDictionarySupersededDeletions(existing, merged)) {
            dao.deleteById(row.id)
        }
        // Fetched ONCE and reused as the collision source below. This was
        // previously re-queried INSIDE the per-record loop, making a merge O(N)
        // full-table reads (at the system's own 50k-item ceiling that is 50k
        // queries, each result then linearly scanned, every key NFC-normalized)
        // — on a background worker inside a 120 s timeout, so a large dictionary
        // timed out on every attempt and reported RETRYABLE forever.
        //
        // Hoisting is behaviour-preserving, and the load-bearing reason is the
        // enclosing `db.withTransaction { }`: Room serialises writers, so no
        // concurrent write can land mid-loop and this snapshot is authoritative
        // for the whole merge. The previous per-iteration query saw the
        // transaction's own prior writes; the snapshot does not, and cannot need
        // to — `merged` holds at most one record per business key (Rule A groups
        // by key), so no two loop iterations collide on one; the collision branch
        // always rewrites the matched row to `rec`'s key, which cannot recur
        // later in the loop; rows the loop UPDATES are reached via `bySyncId`,
        // i.e. the other branch; and `decideDictionarySupersededDeletions` has
        // already run above.
        //
        // INVARIANT — if this call is ever moved out of `withTransaction`, or a
        // caller invokes it without one, a real mid-pass staleness window opens
        // for the `collision.dirty` guard below, which exists precisely to catch
        // a concurrent edit. Re-check the snapshot before dropping the query.
        val accountRows = dao.getAllByAccount(hash)
        val bySyncId = accountRows.associateBy { it.syncId }
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
                // The business key is derived in Kotlin, not by SQL `lower()`
                // — see findBusinessKeyCollision.
                val collision = findBusinessKeyCollision(accountRows, rec)
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

            // Shard-unroutable rows (malformed `day`, no valid UTC month) can
            // never be uploaded to a month shard. They stay in the local
            // ledger for the dashboard, permanently dirty — never clean.
            val routable = StatsShards.monthOfDay(rec.day) != null

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
                dirty = !routable,
                everPushed = routable,
                chars = rec.chars,
                timestampMs = rec.timestampMs
            )
            if (before == null) {
                if (statDao.insertIgnore(replacement) != -1L) {
                    // newly inserted
                    if (routable) toClear.add(rec.eventId)
                }
            } else {
                statDao.insert(replacement)
                if (routable) toClear.add(rec.eventId)
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
            // Contract defense (dictionary excludeQuarantined parity):
            // quarantined rows are held for recovery and must be impossible
            // to serialize via the sync store even if a future writer stamps
            // quarantineReason on an entry.
            .filter { it.syncAccount == hash && it.quarantineReason == null }
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
        // Serialized with every other snippet writer (F3 write lock): the
        // read-compute-write below must not interleave with a UI insert.
        SnippetPreferences.withWriteLock {
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
    }

    override suspend fun hasDirty(hash: String): Boolean =
        SnippetPreferences.allEntries(context).any { it.syncAccount == hash && it.dirty }

    override suspend fun applyMergedAndClearDirty(
        hash: String,
        deviceId: String,
        merged: List<SnippetRecord>
    ) {
        // Serialized with every other snippet writer (F3 write lock): a UI
        // insert landing between the read below and the wholesale write would
        // otherwise be silently lost.
        SnippetPreferences.withWriteLock {
        val all = SnippetPreferences.allEntries(context)
        // Retention first (decideSnippetRetention): foreign-account rows and
        // unsynced/dirty rows survive untouched; current-account clean rows
        // absent from the merge were tombstoned elsewhere and are dropped.
        // Phase D (H3): then drop clean superseded rows — same syncId as the
        // winner under a pre-rename trigger — which retention alone would keep
        // because their sync id is present in the merge.
        val kept = decideSnippetSupersededDrops(
            decideSnippetRetention(all, merged.map { it.syncId }.toSet(), hash),
            merged,
            hash
        ).toMutableList()
        // Local ids are never reused: derive nextFree from the PRE-drop id
        // space, not the kept rows. A healed duplicate can hold the max id;
        // deriving from kept would hand its id to the next insert. Wire
        // identity is the uuid, so skipping the dropped id is free.
        var nextFree = ((all.maxOfOrNull { it.id } ?: 0L)) + 1L
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
}
