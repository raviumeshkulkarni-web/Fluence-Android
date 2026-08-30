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
                // mid-process — refresh ownership so rows aren't hidden as foreign.
                if (auth.accountEmail != SyncAccounts.cachedAccount) SyncAccounts.refresh(context)
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
        val accountHash = AccountHash.of(auth.accountEmail)
            ?: throw SyncError.AuthRequired
        val drive = AppDataDriveStore(
            token,
            tokenRefresher = AccessTokenRefresher { staleToken ->
                // Drive rejected the token mid-pass: invalidate the session
                // cache and Play Services' own cache, then mint a fresh one
                // silently. One bounded retry — a second rejection below is a
                // genuine authorization problem (consent revoked, account
                // removed) and surfaces as AuthRequired/RecoveryRequired.
                auth.invalidateAccessToken()
                runCatching { GoogleOAuth.clearDriveToken(context, staleToken) }
                auth.refreshAccessTokenIfNeeded()
                auth.accessTokenOrNull() ?: throw SyncError.AuthRequired
            }
        )
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
        if (auth.accountEmail != SyncAccounts.cachedAccount) SyncAccounts.refresh(context)
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
