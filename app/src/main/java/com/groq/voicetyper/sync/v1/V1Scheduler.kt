package com.groq.voicetyper.sync.v1

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Frozen v1.1 scheduler core: 300ms debounce after the last mutation, one
 * mutex per domain (dictionary/snippets/stats/settings never interleave),
 * a single-flight running flag, and WorkManager periodic cadence of 15m.
 */
object V1Scheduler {
    const val DEBOUNCE_MS = 300L
    const val WORKER_PERIOD_MIN = 15L

    private val domainMutexes: Map<DomainFile, Mutex> =
        DomainFile.values().associateWith { Mutex() }
    private val lastTriggerMs = AtomicLong(0)
    private val runningFlag = AtomicBoolean(false)
    private val lastSuccessAt = AtomicLong(0)

    @Volatile
    var lastErrorName: String? = null
        private set

    val running: Boolean get() = runningFlag.get()
    val lastSuccessAtMs: Long? get() = lastSuccessAt.get().takeIf { it > 0 }

    fun domainMutex(domain: DomainFile): Mutex = domainMutexes.getValue(domain)

    /** True when at least DEBOUNCE_MS has elapsed since the last mutation. */
    fun shouldRunNow(now: Long = System.currentTimeMillis()): Boolean =
        now - lastTriggerMs.get() >= DEBOUNCE_MS

    /** Mutation hooks call this; the next eligible tick picks the work up. */
    fun markTriggered(now: Long = System.currentTimeMillis()) {
        lastTriggerMs.set(now)
    }

    fun beginPass() {
        runningFlag.set(true)
    }

    fun endPass(success: Boolean = true, error: String? = null) {
        if (success) {
            lastSuccessAt.set(System.currentTimeMillis())
            lastErrorName = null
        } else {
            lastErrorName = error ?: "SYNC_FAILED"
        }
        runningFlag.set(false)
    }
}
