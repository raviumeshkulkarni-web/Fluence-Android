package com.groq.voicetyper.sync.scheduler

/**
 * How a sync pass ended — the scheduler's only input (mirror of Windows
 * `PassOutcomeKind`, scheduler.rs).
 */
enum class PassOutcomeKind { SUCCESS, RETRYABLE, FATAL, AUTH_REQUIRED }

/**
 * Exponential backoff for retryable passes (mirror of Windows: 1000ms start,
 * ×2, capped at 60s).
 */
class SyncBackoff(
    private val startMs: Long = 1_000L,
    private val capMs: Long = 60_000L,
    private val multiplier: Int = 2,
) {
    private var currentMs: Long = startMs

    fun next(): Long {
        val value = currentMs
        currentMs = (currentMs * multiplier).coerceAtMost(capMs)
        return value
    }

    fun reset() {
        currentMs = startMs
    }
}

/**
 * Pure scheduler core (mirror of Windows `SchedulerCore`, scheduler.rs) —
 * cadence 300s, idle poll 3.6s×1000, single-flight with pending coalescing.
 * All timing goes through [nowMs] so tests can drive it deterministically.
 *
 * - [pollTick] is called from the poll loop: it decides whether a pass should
 *   start now. While a pass is running it records `pending` instead (single
 *   flight — the run is coalesced, never queued) and the loop polls again at
 *   the next attempt time.
 * - [completePass] applies the outcome schedule:
 *   - SUCCESS → next attempt at now + cadence, backoff reset
 *   - RETRYABLE → next attempt at now + backoff (1000 → 60s cap)
 *   - FATAL → next attempt at now + cadence (skip this pass, keep schedule)
 *   - AUTH_REQUIRED → next attempt at now + idle poll (wait for reauth)
 */
class SyncSchedulerCore(
    private val cadenceMs: Long = 300_000L,
    private val idlePollMs: Long = 3_600_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val backoff = SyncBackoff()

    var running: Boolean = false
        private set
    var pending: Boolean = false
        private set
    var lastSyncAtMs: Long? = null
        private set
    var lastOutcome: PassOutcomeKind? = null
        private set
    var nextAttemptMs: Long = 0L
        private set

    fun beginPass() {
        running = true
    }

    fun completePass(outcome: PassOutcomeKind) {
        running = false
        lastOutcome = outcome
        val now = nowMs()
        when (outcome) {
            PassOutcomeKind.SUCCESS -> {
                lastSyncAtMs = now
                nextAttemptMs = now + cadenceMs
                backoff.reset()
            }
            PassOutcomeKind.RETRYABLE -> {
                nextAttemptMs = now + backoff.next()
            }
            PassOutcomeKind.FATAL -> {
                nextAttemptMs = now + cadenceMs
                backoff.reset()
            }
            PassOutcomeKind.AUTH_REQUIRED -> {
                nextAttemptMs = now + idlePollMs
                backoff.reset()
            }
        }
    }

    /** True → start a pass now. Coalesces when a pass is already running. */
    fun pollTick(): Boolean {
        val now = nowMs()
        if (running) {
            pending = true
            return false
        }
        if (pending && now >= nextAttemptMs) {
            pending = false
            return true
        }
        return now >= nextAttemptMs
    }
}