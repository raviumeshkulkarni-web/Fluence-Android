package com.groq.voicetyper.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.groq.voicetyper.snippets.SnippetPreferences
import com.groq.voicetyper.sync.auth.SyncAuthSession
import com.groq.voicetyper.sync.drive.GoogleDriveStore
import com.groq.voicetyper.sync.engine.InMemoryFileCacheStore
import com.groq.voicetyper.sync.engine.SettingsStore
import com.groq.voicetyper.sync.engine.SyncEngine
import com.groq.voicetyper.sync.engine.SyncError
import com.groq.voicetyper.sync.wire.RecordType
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.withLock

/**
 * Background sync worker (spec §27 phase 8). One periodic work per kind
 * (15-min minimum, WorkManager constraint — the foreground loop covers the
 * 5-min cadence while the activity is started) plus a one-shot "sync now"
 * work covering all kinds. Every pass goes through [SyncPassGate] so the
 * worker never runs concurrently with the foreground loop.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val rawKind = inputData.getString(SyncSchedule.KEY_KIND) ?: ""
        val kinds = if (rawKind == SyncSchedule.KIND_ALL) {
            SYNC_KINDS
        } else {
            recordTypeOf(rawKind)?.let { listOf(it) } ?: return Result.success()
        }
        return SyncPassGate.mutex.withLock {
            val auth = SyncAuthSession(applicationContext)
            if (!auth.isSignedIn()) return@withLock Result.success()
            try {
                auth.refreshAccessTokenIfNeeded()
                val token = auth.accessTokenOrNull() ?: throw SyncError.AuthRequired
                val drive = GoogleDriveStore(token)
                val account = auth.accountEmail
                val cache = InMemoryFileCacheStore()
                var retryable = false
                for (k in kinds) {
                    val local = LocalStores.forKind(applicationContext, k)
                    val o = SyncEngine.run(k, account, local, drive, auth, cache)
                    retryable = retryable || o.retryableFailures > 0
                    if (k == RecordType.Settings) {
                        // §30.3 mirror: the synced toggle becomes the local flag.
                        (local as SettingsStore).mirrorEnabled {
                            SnippetPreferences.setSnippetsEnabled(applicationContext, it)
                        }
                    }
                }
                // Retryable failures get a WorkManager retry with backoff;
                // rejected (permanent) failures wait for the next periodic run.
                if (retryable) Result.retry() else Result.success()
            } catch (e: SyncError) {
                when (e) {
                    // Reauth, permission, or permanent-rejection problems:
                    // wait for the next periodic run — retrying would only
                    // re-fail (§23).
                    is SyncError.AuthRequired,
                    is SyncError.Fatal,
                    is SyncError.Rejected -> Result.success()
                    is SyncError.Retryable -> Result.retry()
                }
            } catch (e: Exception) {
                Result.retry()
            }
        }
    }

    private fun recordTypeOf(name: String): RecordType? =
        SYNC_KINDS.firstOrNull { it.name == name }

    companion object {
        private const val PERIODIC_MINUTES = 15L
        private const val BACKOFF_SECONDS = 10L
    }
}

/** WorkManager enqueue helpers for periodic and one-shot sync works. */
object SyncSchedule {

    const val KEY_KIND = "kind"
    const val KIND_ALL = "all"

    private const val UNIQUE_PERIODIC_PREFIX = "sync_periodic_"
    private const val UNIQUE_NOW = "sync_now"

    /** Enqueue the four per-kind periodic works (idempotent, KEEP). */
    fun enqueuePeriodic(context: Context) {
        for (kind in SYNC_KINDS) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15L, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_KIND to kind.name))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "$UNIQUE_PERIODIC_PREFIX${kind.name}",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    /** One-shot full pass; REPLACE so repeated taps never pile up. */
    fun enqueueSyncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(KEY_KIND to KIND_ALL))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NOW,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

/** Pass order — Settings last so its mirror wins over user edits (§30.3). */
val SYNC_KINDS = listOf(
    RecordType.History,
    RecordType.Dictionary,
    RecordType.Snippet,
    RecordType.Settings,
)