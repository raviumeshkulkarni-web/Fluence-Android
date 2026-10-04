package com.groq.voicetyper.sync

import android.content.Context
import com.groq.voicetyper.sync.auth.GoogleOAuth
import com.groq.voicetyper.sync.auth.SyncAuthSession
import com.groq.voicetyper.sync.scheduler.PassOutcomeKind
import com.groq.voicetyper.sync.scheduler.SyncSchedulerCore
import com.groq.voicetyper.sync.scheduler.worstOutcome
import com.groq.voicetyper.sync.v1.AccountHash
import com.groq.voicetyper.sync.v1.AccessTokenRefresher
import com.groq.voicetyper.sync.v1.AppDataDriveStore
import com.groq.voicetyper.sync.v1.DomainFile
import com.groq.voicetyper.sync.v1.DriveIdentity
import com.groq.voicetyper.sync.v1.SyncError
import com.groq.voicetyper.sync.v1.SyncMetadata
import com.groq.voicetyper.sync.v1.V1Stores
import com.groq.voicetyper.sync.v1.V1SyncEngine
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Foreground sync driver (frozen v1.2): owns the poll loop and the
 * single-flight pass mutex shared with [SyncWorker].
 *
 * One pass = the four v1.2 domain files on the user's Drive appDataFolder
 * (dictionary, snippets, stats, settings), merged with pure LWW and uploaded
 * with version-number staleness detection. Transcription history NEVER syncs —
 * it is platform-local by product contract.
 *
 * While the activity is started ([start]/[stop]) the loop polls on a short
 * cadence; WorkManager periodic works cover background passes. A "sync now"
 * request arriving during an active pass queues behind the gate and runs
 * afterwards (single-flight with requeue).
 */
/**
 * Guards a running sync pass against an account switch that happens mid-pass.
 *
 * Drive partitions storage by the *token's* account, so a token minted after the
 * active account changed belongs to a different account than the one this pass is
 * syncing for. Continuing would write this pass's payload into the other
 * account's `appDataFolder`. Refreshing under the pass's own account stays
 * allowed, because tokens legitimately expire mid-pass.
 */
internal object PassAccountGuard {

    /** Throws [SyncError.Retryable] unless [currentEmail] is still [passAccountEmail]. */
    fun requireUnchanged(passAccountEmail: String?, currentEmail: String?) {
        if (currentEmail != passAccountEmail) {
            throw SyncError.Retryable("account changed mid-pass; aborting before credential use")
        }
    }
}

class SyncManager(
    private val context: Context,
    private val auth: SyncAuthSession,
    private val scope: CoroutineScope,
    private val scheduler: SyncSchedulerCore = SyncSchedulerCore(),
) {

    private val _status = MutableStateFlow(
        SyncStatus(
            signedIn = auth.isSignedIn(),
            account = auth.accountEmail,
            syncEnabled = isSyncEnabled(),
        )
    )
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Optional status sink (SettingsScreen subscribes or polls this). */
    var listener: (SyncStatus) -> Unit = {}
        set(value) {
            field = value
            value(_status.value)
        }

    private var loopJob: Job? = null

    /**
     * Dedicated scope for sync passes. Survives activity ON_STOP so that a
     * pass in progress (e.g. waiting on GoogleAuthUtil.getToken) is not killed
     * when the screen turns off. Cancelled only when the SyncManager is no
     * longer needed.
     */
    private val passScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("sync-pass") +
            kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
                android.util.Log.e("FluenceSync", "passScope uncaught: ${t::class.simpleName}: ${t.message}")
            }
    )

    /**
     * Last successful pass, read from prefs at construction. The scheduler's
     * lastSyncAtMs is memory-only; this survives process death so the UI can
     * show an honest "Last synced" after a restart.
     */
    private var persistedLastSyncAtMs: Long? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SYNC_AT, 0L)
            .takeIf { it > 0L }

    /** Start the poll loop (idempotent). */
    fun start() {
        refreshFlags()
        if (loopJob?.isActive == true) return
        loopJob = scope.launch(Dispatchers.IO) { pollLoop() }
    }

    /** Stop the poll loop. An in-flight pass finishes on its own thread. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /** Cancel orphaned pass jobs — call only from Activity.onDestroy (not onStop). */
    fun destroy() {
        passScope.cancel()
    }

    /**
     * Manual "sync now": run a pass immediately (single-flight, requeued).
     * Returns false when sync is paused — nothing is scheduled, mirroring the
     * disabled Sync-now button so pull-to-refresh cannot bypass the gate.
     */
    fun syncNow(): Boolean {
        if (!isSyncEnabled()) return false
        passScope.launch { runPass() }
        return true
    }

    /** Complete sign-in with the account email chosen in the account picker. */
    fun completeSignIn(accountEmail: String) {
        auth.completeSignIn(accountEmail)
        AccountHash.of(accountEmail)?.let { V1Stores.settingsStore(context).activateAccount(it) }
        // A different account is signing in: any identity previously verified
        // against the old token is now stale and must not survive. The refresh
        // below re-establishes a provisional value from the persisted email; the
        // following pass replaces it with the token-verified one.
        SyncAccounts.clearAuthentication()
        // Refresh the ownership cache now (not only on next Activity create),
        // or isForeign would misclassify the previous account's rows.
        SyncAccounts.refresh(context)
        scheduler.resetForAccountChange()
        refreshStatus()
        // Enroll/pull immediately instead of waiting for the polling cadence.
        syncNow()
    }

    /** Sign out: clears encrypted storage and the status flow. */
    fun signOut() {
        auth.signOut()
        // The verified identity belonged to the token that just went away.
        SyncAccounts.clearAuthentication()
        SyncAccounts.refresh(context)
        scheduler.resetForAccountChange()
        refreshStatus()
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            if (scheduler.pollTick() && isSyncEnabled()) {
                passScope.launch { runPass() }
            }
            val waitMs = if (scheduler.running) {
                1_000L
            } else {
                (scheduler.nextAttemptMs - System.currentTimeMillis()).coerceIn(1_000L, 5_000L)
            }
            delay(waitMs)
        }
    }

    /** One full v1.2 pass; returns the outcome for callers that map retries. */
    internal suspend fun runPass(): PassOutcomeKind = withContext(Dispatchers.IO) {
        // Surface the running state BEFORE waiting on the single-flight gate:
        // a manual "Sync now" must show "Syncing…" (and disable the button)
        // immediately, even while a background worker holds the mutex.
        scheduler.beginPass()
        publish()
        try {
            SyncPassGate.mutex.withLock {
                auth.reloadFromStorage()
                // A recovered keystore can restore a previously committed email
                // mid-process, so refresh the ownership cache when the persisted
                // email has moved.
                //
                // STAGE 1: this is now SKIPPED once an identity has been verified
                // against the access token, because the verified value is
                // authoritative and the persisted email is display-only. A
                // persisted email that changes WITHOUT completeSignIn/signOut
                // therefore leaves the ownership *indicator* stale until the next
                // pass re-publishes. That is bounded and self-healing: SyncAccounts
                // only drives UI classification and delete-path skipping, while
                // the pass derives its own partition from the token and so cannot
                // be misdirected. Deliberately NOT clearing tokenVerified here —
                // doing so would let a display-only string demote a proven identity.
                if (!SyncAccounts.tokenVerified && auth.accountEmail != SyncAccounts.cachedAccount) SyncAccounts.refresh(context)
                if (!auth.isSignedIn()) {
                    scheduler.completePass(PassOutcomeKind.AUTH_REQUIRED)
                    publish()
                    return@withLock PassOutcomeKind.AUTH_REQUIRED
                }
                var outcome = PassOutcomeKind.SUCCESS
                try {
                    outcome = runV12Pass()
                } catch (e: com.groq.voicetyper.sync.v1.SyncError) {
                    android.util.Log.e("FluenceSync", "v1.2 pass failed: ${e::class.simpleName} ${e.message}")
                    outcome = when (e) {
                        is com.groq.voicetyper.sync.v1.SyncError.AuthRequired -> PassOutcomeKind.AUTH_REQUIRED
                        is com.groq.voicetyper.sync.v1.SyncError.RateLimited -> {
                            scheduler.noteRetryAfter(e.retryAfterMs)
                            PassOutcomeKind.RETRYABLE
                        }
                        is com.groq.voicetyper.sync.v1.SyncError.Retryable,
                        is com.groq.voicetyper.sync.v1.SyncError.StaleVersion -> PassOutcomeKind.RETRYABLE
                        is com.groq.voicetyper.sync.v1.SyncError.Fatal,
                        is com.groq.voicetyper.sync.v1.SyncError.Rejected -> PassOutcomeKind.FATAL
                    }
                } catch (e: Exception) {
                    android.util.Log.e("FluenceSync", "v1.2 pass failed unexpectedly: ${e::class.simpleName} ${e.message}", e)
                    outcome = PassOutcomeKind.RETRYABLE
                } finally {
                    scheduler.completePass(outcome)
                    if (outcome == PassOutcomeKind.SUCCESS) {
                        persistLastSyncAt(scheduler.lastSyncAtMs)
                    }
                    publish()
                }
                outcome
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelled while waiting for the gate (e.g. activity stopped):
            // clear the running flag so the UI never sticks on "Syncing…".
            scheduler.cancelPass()
            publish()
            throw e
        }
    }

    /**
     * One frozen-v1.2 pass across all four domains. Domains are isolated: a
     * classified failure in one never prevents the others from syncing, and
     * the worst per-domain outcome is reported as the pass outcome
     * (worstOutcome — SUCCESS < RETRYABLE < REJECTED/FATAL < AUTH_REQUIRED).
     * Only truly unexpected exceptions escape to the caller.
     */
    private suspend fun runV12Pass(): PassOutcomeKind {
        try {
            withTimeout(20_000L) { auth.refreshAccessTokenIfNeeded() }
        } catch (e: TimeoutCancellationException) {
            throw com.groq.voicetyper.sync.v1.SyncError.Retryable("token mint timeout")
        }
        val token = auth.accessTokenOrNull() ?: throw SyncError.AuthRequired
        // STAGE 1 — the partition identity is derived from the TOKEN, not from a
        // locally persisted string. Drive partitions appDataFolder by the account
        // that owns the access token, so this is the only value that provably
        // names the folder this pass is about to touch. The previous code derived
        // the key from `auth.accountEmail` while the token was minted from
        // `getLastSignedInAccount()` — two independent sources, deliberately
        // allowed to drift, so a token for one account could be paired with
        // another account's partition key. Fails CLOSED if the authenticated
        // identity cannot be established; there is deliberately no fallback to a
        // persisted value, because that fallback is the defect being removed.
        // Fail CLOSED if the authenticated identity cannot be established, but do
        // not misreport a transient Drive failure as an auth problem: a 5xx, 429
        // or connectivity timeout must surface as RETRYABLE so the user is not
        // prompted to sign in again for an account that was working fine.
        val authenticatedEmail = when (val id = DriveIdentity.resolveAccountEmail(token)) {
            is DriveIdentity.IdentityResult.Resolved -> id.email
            is DriveIdentity.IdentityResult.Unauthorized -> throw SyncError.AuthRequired
            is DriveIdentity.IdentityResult.Unavailable -> throw SyncError.Retryable(
                "identity: ${id.reason}"
            )
        }
        // Publish the token-proven identity as the process-wide ownership key, so
        // repositories and UI evaluate `isForeign` against the same partition the
        // pass is about to read and write — not against the persisted string.
        SyncAccounts.publishAuthenticated(authenticatedEmail)
        val accountHash = AccountHash.of(authenticatedEmail)
            ?: throw SyncError.AuthRequired
        // The session email is retained ONLY to detect an account change
        // mid-pass (sign-out / account switch). It is a display cache, not the
        // sync identity: after a Google email rename it legitimately lags the
        // token, so divergence is REPORTED rather than made fatal here. Rename
        // and orphan-record behaviour is a separate, explicit decision.
        val passSessionEmail = auth.accountEmail
        if (passSessionEmail != null && AccountHash.of(passSessionEmail) != accountHash) {
            android.util.Log.i(
                "FluenceSync",
                "session email differs from authenticated Drive identity; " +
                    "using the token-bound identity for this pass"
            )
        }
        val drive = AppDataDriveStore(
            token,
            tokenRefresher = AccessTokenRefresher { staleToken ->
                // Drive rejected the token mid-pass: invalidate the session
                // cache and Play Services' own cache, then mint a fresh one
                // silently. One bounded retry — a second rejection below is a
                // genuine authorization problem (consent revoked, account
                // removed) and surfaces as AuthRequired/RecoveryRequired.
                //
                // Refresh stays ALLOWED while this pass's own account is still
                // active (tokens expire mid-pass legitimately). It is refused
                // once the active account differs or is gone (sign-out clears
                // accountEmail), because then a fresh token belongs to somebody
                // else. Fail closed: abort this pass and let the next one run for
                // the new account, rather than crossing accounts.
                PassAccountGuard.requireUnchanged(passSessionEmail, auth.accountEmail)
                auth.invalidateAccessToken()
                runCatching { GoogleOAuth.clearDriveToken(context, staleToken) }
                auth.refreshAccessTokenIfNeeded()
                // Re-check: the account can change while the mint is in flight.
                PassAccountGuard.requireUnchanged(passSessionEmail, auth.accountEmail)
                val refreshedToken = auth.accessTokenOrNull() ?: throw SyncError.AuthRequired
                // STAGE 1 — verify the REFRESHED TOKEN's identity, not the session
                // string. The guard above can only prove the persisted email did
                // not change; it says nothing about WHICH Google account Play
                // Services mints for. If the device's Play Services account was
                // switched by another app or by system UI — without Fluence's
                // own completeSignIn running — the guard passes and a token for a
                // DIFFERENT account would be used to write this pass's
                // H_account-keyed payload into that account's appDataFolder.
                // Asking Drive who the new token belongs to closes that path; on
                // mismatch abort as RETRYABLE so the next pass re-derives the
                // identity from the new token and partitions correctly.
                // Same classification as the pass entry: a transient Drive
                // failure here must NOT be reported as an auth problem.
                val refreshedIdentity = when (
                    val r = DriveIdentity.resolveAccountEmailBlocking(refreshedToken)
                ) {
                    is DriveIdentity.IdentityResult.Resolved -> r.email
                    is DriveIdentity.IdentityResult.Unauthorized -> throw SyncError.AuthRequired
                    is DriveIdentity.IdentityResult.Unavailable ->
                        throw SyncError.Retryable("refresh identity: ${r.reason}")
                }
                if (!refreshedIdentity.equals(authenticatedEmail, ignoreCase = true)) {
                    android.util.Log.w(
                        "FluenceSync",
                        "refreshed token belongs to a different Drive account; aborting pass"
                    )
                    throw SyncError.Retryable("token identity changed mid-pass")
                }
                refreshedToken
            }
        )
        // STAGE 3: bind the account partition to the token-derived identity
        // established above. Agents/Styles read and write only inside
        // `fluence/v1/acct-<accountHash>/`, and fail closed when this is null.
        drive.accountHash = accountHash
        val deviceId = com.groq.voicetyper.sync.v1.DeviceIdProvider.getDeviceId(context)

        // Per-account metadata: atomic NULL→stamped rows + maxSeen/backfillDone.
        val metaDao = V1Stores.metadataDao(context)
        var meta = metaDao.getByHash(accountHash)
        if (meta == null) {
            meta = SyncMetadata(
                accountHash = accountHash,
                deviceId = deviceId,
                maxSeen = 0L,
                backfillDone = false
            )
            metaDao.upsert(meta)
        }

        val maxSeenRef = V1SyncEngine.MaxSeenRef(meta.maxSeen)
        var worst = PassOutcomeKind.SUCCESS
        for (domain in DomainFile.values()) {
            val outcome = runDomain(domain, drive, accountHash, deviceId, maxSeenRef)
            worst = worstOutcome(worst, outcome)
            metaDao.updateMaxSeen(accountHash, maxSeenRef.value)
        }
        return worst
    }

    /** Run one domain; classified failures are contained and folded into the
     *  pass outcome via [worstOutcome], never propagated, so the remaining
     *  domains still sync (Windows parity). Unexpected exceptions still escape.
     */
    private suspend fun runDomain(
        domain: DomainFile,
        drive: AppDataDriveStore,
        accountHash: String,
        deviceId: String,
        maxSeenRef: V1SyncEngine.MaxSeenRef
    ): PassOutcomeKind {
        return try {
            val result = when (domain) {
                DomainFile.DICTIONARY ->
                    V1SyncEngine.syncDictionary(V1Stores.dictionaryStore(context), drive, accountHash, deviceId, maxSeenRef)
                DomainFile.SNIPPETS ->
                    V1SyncEngine.syncSnippets(V1Stores.snippetStore(context), drive, accountHash, deviceId, maxSeenRef)
                DomainFile.STATS ->
                    V1SyncEngine.syncStats(V1Stores.statStore(context), drive, accountHash, deviceId, maxSeenRef)
                DomainFile.SETTINGS ->
                    V1SyncEngine.syncSettings(V1Stores.settingsStore(context), drive, accountHash, deviceId, maxSeenRef)
                // Phase 6 additive domains — same engine, same LWW, same
                // per-domain failure containment as every other domain above.
                DomainFile.AGENTS ->
                    V1SyncEngine.syncAgents(V1Stores.agentStore(context), drive, accountHash, deviceId, maxSeenRef)
                DomainFile.STYLES ->
                    V1SyncEngine.syncStyles(V1Stores.styleStore(context), drive, accountHash, deviceId, maxSeenRef)
            }
            if (result.skippedCorrupt) {
                // A corrupt remote envelope could not be repaired this pass (no
                // usable local state). Surface retryable, not silent success: the
                // next pass re-attempts the repair as soon as local state exists
                // and never reports a corrupt domain as fully synced.
                android.util.Log.w("FluenceSync", "domain $domain corrupt remote skipped; will retry")
            }
            domainOutcome(result)
        } catch (e: com.groq.voicetyper.sync.v1.SyncError.StaleVersion) {
            android.util.Log.w("FluenceSync", "domain $domain kept changing; will converge next pass")
            PassOutcomeKind.RETRYABLE
        } catch (e: com.groq.voicetyper.sync.v1.SyncError.RateLimited) {
            // Explicit Retry-After: surface the delay so the scheduler waits at
            // least that long before retrying, instead of hammering Drive.
            scheduler.noteRetryAfter(e.retryAfterMs)
            android.util.Log.w("FluenceSync", "domain $domain rate limited; retry after ${e.retryAfterMs ?: "?(scheduler backoff)"}ms")
            PassOutcomeKind.RETRYABLE
        } catch (e: com.groq.voicetyper.sync.v1.SyncError.Retryable) {
            android.util.Log.w("FluenceSync", "domain $domain retryable: ${e.message}")
            PassOutcomeKind.RETRYABLE
        } catch (e: com.groq.voicetyper.sync.v1.SyncError.Rejected) {
            android.util.Log.e("FluenceSync", "domain $domain rejected: ${e.message}")
            PassOutcomeKind.FATAL
        } catch (e: com.groq.voicetyper.sync.v1.SyncError.Fatal) {
            android.util.Log.e("FluenceSync", "domain $domain fatal: ${e.message}")
            PassOutcomeKind.FATAL
        } catch (e: com.groq.voicetyper.sync.v1.SyncError.AuthRequired) {
            android.util.Log.e("FluenceSync", "domain $domain auth required: ${e.message}")
            PassOutcomeKind.AUTH_REQUIRED
        }
    }

    private fun publish() {
        val status = SyncStatus(
            signedIn = auth.isSignedIn(),
            account = auth.accountEmail,
            syncEnabled = isSyncEnabled(),
            running = scheduler.running,
            lastSyncAtMs = scheduler.lastSyncAtMs ?: persistedLastSyncAtMs,
            lastError = scheduler.lastOutcome?.takeIf { it != PassOutcomeKind.SUCCESS }?.name,
            recoveryPending = auth.recoveryIntent != null,
            secureStorageUnavailable = auth.storageDegraded,
        )
        _status.value = status
        listener(status)
    }

    /**
     * Re-read persisted auth into the status flow and, when the signed-in
     * account changed (e.g. recovered from a transient secure-storage
     * failure), refresh the ownership cache so previously hidden rows are
     * shown again instead of lingering as "foreign".
     */
    fun refreshStatus() {
        auth.reloadFromStorage()
        if (!SyncAccounts.tokenVerified && auth.accountEmail != SyncAccounts.cachedAccount) SyncAccounts.refresh(context)
        refreshFlags()
    }

    /**
     * Consume the pending Google consent intent, if any. The caller (UI)
     * should launch the returned intent via an ActivityResultLauncher.
     * Returns null when no consent is needed.
     */
    fun consumeRecoveryIntent(): android.content.Intent? = auth.recoveryIntent?.also {
        // Don't clear here — clear after successful token mint in refreshAccessTokenIfNeeded.
    }

    /** Write-through the last successful pass time so it survives process death. */
    private fun persistLastSyncAt(atMs: Long?) {
        if (atMs == null) return
        persistedLastSyncAtMs = atMs
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_SYNC_AT, atMs).apply()
    }

    private fun refreshFlags() {
        _status.value = _status.value.copy(
            signedIn = auth.isSignedIn(),
            account = auth.accountEmail,
            syncEnabled = isSyncEnabled(),
            secureStorageUnavailable = auth.storageDegraded,
        )
    }

    private fun isSyncEnabled(): Boolean = SyncManager.isSyncEnabled(context)

    companion object {
        private const val PREFS_NAME = "fluence_prefs"
        private const val KEY_SYNC_ENABLED = "sync_enabled"
        private const val KEY_LAST_SYNC_AT = "last_sync_at_ms"

        fun isSyncEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SYNC_ENABLED, false)

        fun setSyncEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()
        }
    }
}

/** Maps a [V1SyncEngine.SyncResult] (one that was not thrown as a classified
 *  error) to the pass outcome. Corrupt domains that could not be repaired this
 *  pass must never read as silent success, so they surface
 *  [PassOutcomeKind.RETRYABLE] and are re-attempted on the next pass.
 *  (Intentional Windows-parity divergence: Windows reports plain Ok.) */
internal fun domainOutcome(result: V1SyncEngine.SyncResult): PassOutcomeKind =
    if (result.skippedCorrupt) PassOutcomeKind.RETRYABLE else PassOutcomeKind.SUCCESS
