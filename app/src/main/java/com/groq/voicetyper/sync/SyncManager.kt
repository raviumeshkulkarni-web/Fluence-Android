package com.groq.voicetyper.sync

import android.content.Context
import com.groq.voicetyper.history.HistoryRepository
import com.groq.voicetyper.snippets.SnippetPreferences
import com.groq.voicetyper.sync.auth.SyncAuthSession
import com.groq.voicetyper.sync.drive.GoogleDriveStore
import com.groq.voicetyper.sync.engine.SyncEngine
import com.groq.voicetyper.sync.engine.SyncError
import com.groq.voicetyper.sync.scheduler.PassOutcomeKind
import com.groq.voicetyper.sync.scheduler.SyncSchedulerCore
import com.groq.voicetyper.sync.wire.RecordType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Foreground sync driver (spec §27 phase 8): owns the poll loop and the
 * single-flight pass mutex shared with [SyncWorker]. Mirrors the Windows
 * scheduler semantics via [SyncSchedulerCore].
 *
 * While the activity is started ([start]/[stop]) the loop polls on a 5-min
 * cadence; the WorkManager periodic works (15-min minimum, documented) cover
 * background passes. Manual "sync now" runs an immediate pass through the
 * same gate.
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

    /** Manual "sync now": run a pass immediately (single-flight). */
    fun syncNow() {
        scope.launch(Dispatchers.IO) { runPass() }
    }

    /** Re-read persisted auth + prefs into the status flow. */
    fun refreshStatus() {
        auth.reloadFromStorage()
        SyncAccounts.refresh(context)
        refreshFlags()
    }

    /** Complete sign-in using the server auth code from native Google Sign-In. */
    suspend fun completeSignInWithAuthCode(serverAuthCode: String, accountEmailHint: String? = null): String {
        val email = auth.completeSignInWithAuthCode(serverAuthCode, accountEmailHint)
        SyncAccounts.refresh(context)
        refreshStatus()
        return email
    }

    /** Sign out: clears encrypted storage and the status flow. */
    fun signOut() {
        auth.signOut()
        SyncAccounts.refresh(context)
        refreshStatus()
    }

    private suspend fun pollLoop() {
        while (currentCoroutineContext().isActive) {
            if (scheduler.pollTick() && isSyncEnabled()) {
                runPass()
            }
            val waitMs = if (scheduler.running) {
                1_000L
            } else {
                (scheduler.nextAttemptMs - System.currentTimeMillis()).coerceIn(1_000L, 5_000L)
            }
            delay(waitMs)
        }
    }

    private suspend fun runPass() = withContext(Dispatchers.IO) {
        SyncPassGate.mutex.withLock {
            auth.reloadFromStorage()
            SyncAccounts.refresh(context)
            if (!auth.isSignedIn()) {
                scheduler.completePass(PassOutcomeKind.AUTH_REQUIRED)
                publish()
                return@withLock
            }
            scheduler.beginPass()
            var outcome = PassOutcomeKind.SUCCESS
            try {
                auth.refreshAccessTokenIfNeeded()
                val token = auth.accessTokenOrNull() ?: throw SyncError.AuthRequired
                val drive = GoogleDriveStore(token)
                val account = auth.accountEmail
                var retryableFailures = 0
                var rejectedFailures = 0
                for (kind in SYNC_KINDS) {
                    val local = LocalStores.forKind(context, kind)
                    val o = SyncEngine.run(kind, account, local, drive, auth)
                    retryableFailures += o.retryableFailures
                    rejectedFailures += o.rejectedFailures
                    if (kind == RecordType.History) {
                        // Imports bypass HistoryRepository.save, so stats are
                        // rebuilt after the history phase (§30.3 parity).
                        HistoryRepository.refreshStats(context)
                    }
                    if (kind == RecordType.Settings) {
                        // §30.3 mirror: the synced toggle becomes the local flag.
                        (local as com.groq.voicetyper.sync.engine.SettingsStore)
                            .mirrorEnabled { SnippetPreferences.setSnippetsEnabled(context, it) }
                    }
                }
                // Windows parity (scheduler.rs classify_pass): retryable wins
                // over rejected; either makes the pass non-success so the
                // outcome is never recorded as synced.
                outcome = when {
                    retryableFailures > 0 -> PassOutcomeKind.RETRYABLE
                    rejectedFailures > 0 -> PassOutcomeKind.REJECTED
                    else -> PassOutcomeKind.SUCCESS
                }
            } catch (e: SyncError) {
                android.util.Log.e("FluenceSync", "Sync pass failed with SyncError: ${e::class.simpleName} - ${e.message}", e)
                outcome = when (e) {
                    is SyncError.AuthRequired -> PassOutcomeKind.AUTH_REQUIRED
                    is SyncError.Retryable -> PassOutcomeKind.RETRYABLE
                    is SyncError.Fatal -> PassOutcomeKind.FATAL
                    is SyncError.Rejected -> PassOutcomeKind.REJECTED
                }
            } catch (e: Exception) {
                android.util.Log.e("FluenceSync", "Sync pass failed with unexpected Exception: ${e::class.simpleName} - ${e.message}", e)
                outcome = PassOutcomeKind.RETRYABLE
            } finally {
                scheduler.completePass(outcome)
                publish()
            }
        }
    }

    private fun publish() {
        val status = SyncStatus(
            signedIn = auth.isSignedIn(),
            account = auth.accountEmail,
            syncEnabled = isSyncEnabled(),
            running = scheduler.running,
            lastSyncAtMs = scheduler.lastSyncAtMs,
            lastError = scheduler.lastOutcome?.takeIf { it != PassOutcomeKind.SUCCESS }?.name,
        )
        _status.value = status
        listener(status)
    }

    private fun refreshFlags() {
        _status.value = _status.value.copy(
            signedIn = auth.isSignedIn(),
            account = auth.accountEmail,
            syncEnabled = isSyncEnabled(),
        )
    }

    private fun isSyncEnabled(): Boolean = SyncManager.isSyncEnabled(context)

    companion object {
        private const val PREFS_NAME = "fluence_prefs"
        private const val KEY_SYNC_ENABLED = "sync_enabled"

        fun isSyncEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SYNC_ENABLED, false)

        fun setSyncEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SYNC_ENABLED, enabled)
                .apply()
        }
    }
}