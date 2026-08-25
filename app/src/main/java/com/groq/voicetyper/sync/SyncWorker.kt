package com.groq.voicetyper.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.groq.voicetyper.sync.auth.SyncAuthSession
import com.groq.voicetyper.sync.scheduler.PassOutcomeKind
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Background sync worker (frozen v1.2). One 15-min periodic work plus a
 * one-shot "sync now" covering all four domains. Every pass goes through
 * [SyncPassGate] so the worker never runs concurrently with the foreground
 * loop, and requeues when a request arrives mid-pass.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val auth = SyncAuthSession(applicationContext)
        if (!auth.isSignedIn()) return Result.success()
        if (!SyncManager.isSyncEnabled(applicationContext)) return Result.success()
        val manager = SyncManager(applicationContext, auth, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO))
        return try {
            val outcome = withTimeout(120_000L) { manager.runPass() }
            when (outcome) { PassOutcomeKind.RETRYABLE -> Result.retry(); else -> Result.success() }
        } catch (e: TimeoutCancellationException) {
            android.util.Log.w("FluenceSync", "worker pass timed out")
            Result.retry()
        } catch (e: Exception) {
            android.util.Log.w("FluenceSync", "worker pass failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC_MINUTES = 15L
        private const val BACKOFF_SECONDS = 10L
    }
}

/** WorkManager enqueue helpers for periodic and one-shot sync works. */
object SyncSchedule {

    const val KEY_KIND = "kind"
    const val KIND_ALL = "all"

    private const val UNIQUE_PERIODIC = "sync_periodic_v12"
    private const val UNIQUE_NOW = "sync_now"

    /** Enqueue the periodic full pass (idempotent, KEEP). */
    fun enqueuePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15L, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_KIND to KIND_ALL))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** One-shot full pass; REPLACE so repeated taps never pile up. */
    fun enqueueSyncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(workDataOf(KEY_KIND to KIND_ALL))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NOW,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
