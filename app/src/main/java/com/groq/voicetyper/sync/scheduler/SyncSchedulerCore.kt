package com.groq.voicetyper.sync.scheduler

/**
 * How a sync pass ended — the scheduler's only input (mirror of Windows
 * `PassOutcomeKind`, scheduler.rs).
 */
enum class PassOutcomeKind { SUCCESS, RETRYABLE, REJECTED, FATAL, AUTH_REQUIRED }

/**
 * Order-independent "worst wins" aggregation of per-domain outcomes into the
 * pass outcome (mirror of Windows PassOutcomeKind precedence). A single doomed
 * domain must never hide what another domain reported: severity
 * SUCCESS < RETRYABLE < REJECTED/FATAL < AUTH_REQUIRED.
 */
fun worstOutcome(a: PassOutcomeKind, b: PassOutcomeKind): PassOutcomeKind {
    if (a == b) return a
    // Ties between equally severe kinds (REJECTED vs FATAL) return the left
    // argument — deterministic because both are equivalent for scheduling.
    return if (a.severity() >= b.severity()) a else b
}

/** [worstOutcome] fold over every domain in the pass. */
fun worstOutcome(outcomes: Iterable<PassOutcomeKind>): PassOutcomeKind =
    outcomes.fold(PassOutcomeKind.SUCCESS) { acc, o -> worstOutcome(acc, o) }

private fun PassOutcomeKind.severity(): Int = when (this) {
    PassOutcomeKind.SUCCESS -> 0
    PassOutcomeKind.RETRYABLE -> 1
    PassOutcomeKind.REJECTED -> 2
    PassOutcomeKind.FATAL -> 2
    PassOutcomeKind.AUTH_REQUIRED -> 3
}

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
 * cadence 300s, auth-required park 15min, single-flight with pending coalescing.
 * All timing goes through [nowMs] so tests can drive it deterministically.
 *
 * - [pollTick] is called from the poll loop: it decides whether a pass should
 *   start now. While a pass is running it records `pending` instead (single
 *   flight — the run is coalesced, never queued) and the loop polls again at
 *   the next attempt time.
 * - [completePass] applies the outcome schedule:
 *   - SUCCESS → next attempt at now + cadence, backoff reset
 *   - RETRYABLE → next attempt at now + backoff (1000 → 60s cap)
 *   - REJECTED → next attempt at now + cadence, backoff reset; the pass is
 *     surfaced non-success (lastSyncAtMs NOT advanced) but never backoff-
 *     escalated (§23 / Phase 0 remediation)
 *   - FATAL → next attempt at now + cadence (skip this pass, keep schedule)
 *   - AUTH_REQUIRED → next attempt at now + auth park (15 min, aligned with
 *     the periodic cadence so token recovery is prompt)
 */
class SyncSchedulerCore(
    private val cadenceMs: Long = 300_000L,
    private val idlePollMs: Long = 900_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val backoff = SyncBackoff()

    /**
     * An explicit `Retry-After` delay (ms) surfaced by a throttled Drive
     * response; consumed by the `RETRYABLE` branch of [completePass] so a
     * rate-limited API is honored rather than retried too eagerly. Set by the
     * sync manager when a [com.groq.voicetyper.sync.v1.SyncError.RateLimited]
     * is classified during a pass.
     */
    var pendingRetryAfterMs: Long? = null
        private set

    /** Record an explicit `Retry-After` delay (ms) to be honored by the next
     *  retryable [completePass]. Callers may pass a smaller value than one
     *  already pending — the pending max is kept, mirroring Windows. */
    fun noteRetryAfter(ms: Long?) {
        if (ms == null) return
        pendingRetryAfterMs = maxOf(ms, pendingRetryAfterMs ?: 0L)
    }

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

    /** Clear an in-flight pass without touching the outcome schedule (cancellation). */
    fun cancelPass() {
        running = false
    }

    /** Clear the previous account's result before enrolling a new account. */
    fun resetForAccountChange() {
        lastOutcome = null
        pending = false
        nextAttemptMs = 0L
        pendingRetryAfterMs = null
        backoff.reset()
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
                // When the response carried an explicit `Retry-After`, wait at
                // least that long too (never sooner than the header demands).
                val retryAfter = pendingRetryAfterMs
                pendingRetryAfterMs = null
                val delay = retryAfter?.let { maxOf(backoff.next(), it) } ?: backoff.next()
                nextAttemptMs = now + delay
            }
            PassOutcomeKind.REJECTED -> {
                // Permanent rejections must NOT backoff-escalate: reset and let
                // the next attempt run at the cadence. Non-success surfaced by
                // NOT advancing lastSyncAtMs.
                nextAttemptMs = now + cadenceMs
                backoff.reset()
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
