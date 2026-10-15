package com.groq.voicetyper.sync.scheduler

/**
 * How a sync pass ended — the scheduler's only input (mirror of Windows
 * `PassOutcomeKind`, scheduler.rs).
 *
 * F4 Success-with-warning: DEGRADED means the pass converged on everything
 * it could but skipped some remote state it retains untouched. It records
 * the sync like a success (lastSyncAtMs advances, no backoff escalation —
 * the condition is permanent) while keeping a clearable warning, instead of
 * stalling the clock and parking a misleading error.
 */
enum class PassOutcomeKind { SUCCESS, DEGRADED, RETRYABLE, REJECTED, FATAL, AUTH_REQUIRED }

/**
 * Order-independent "worst wins" aggregation of per-domain outcomes into the
 * pass outcome (mirror of Windows PassOutcomeKind precedence). A single doomed
 * domain must never hide what another domain reported: severity
 * SUCCESS < DEGRADED < RETRYABLE < REJECTED/FATAL < AUTH_REQUIRED.
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
    PassOutcomeKind.DEGRADED -> 1
    PassOutcomeKind.RETRYABLE -> 2
    // REJECTED/FATAL stay tied (a tie returns the left argument — equivalent
    // for scheduling) and above RETRYABLE: the pre-existing order pinned by
    // worstOutcome_is_order_independent_and_severity_ordered. (Windows checks
    // retryable first instead; that cross-platform scheduler difference is
    // out of scope — only the wire/store contract must match.)
    PassOutcomeKind.REJECTED -> 3
    PassOutcomeKind.FATAL -> 3
    PassOutcomeKind.AUTH_REQUIRED -> 4
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
 *   - DEGRADED → like SUCCESS (lastSyncAtMs advances, cadence, reset) plus
 *     a clearable warning flag; a warning is not an error
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
    /**
     * F4 Success-with-warning: true while the latest finished pass converged
     * with skipped remote state. Set by DEGRADED, cleared by the next clean
     * SUCCESS; other outcomes leave it (a failed pass reports nothing new
     * about convergence).
     */
    var degraded: Boolean = false
        private set
    var nextAttemptMs: Long = 0L
        private set

    fun beginPass() {
        running = true
    }

    /** Clear an in-flight pass without touching the outcome schedule (cancellation). */
    fun cancelPass() {
        running = false
        // A cancelled pass recorded NO outcome, so any `Retry-After` it collected
        // belongs to a pass that never finished and must not survive it. Leaving
        // the hint armed let it be consumed by a LATER pass — e.g. one that gave
        // up waiting on the single-flight gate and recorded RETRYABLE, which then
        // inherited `maxOf(backoff.next(), staleHour)` and slept an hour it had no
        // reason to. This is the same stale-hint class that `completePass` clears
        // unconditionally; a cancellation is just the other way a pass can end
        // without an outcome. `resetForAccountChange` clears it for the same
        // reason.
        pendingRetryAfterMs = null
    }

    /** Clear the previous account's result before enrolling a new account. */
    fun resetForAccountChange() {
        lastOutcome = null
        degraded = false
        pending = false
        nextAttemptMs = 0L
        pendingRetryAfterMs = null
        backoff.reset()
    }

    fun completePass(outcome: PassOutcomeKind) {
        running = false
        lastOutcome = outcome
        val now = nowMs()
        // Consume the `Retry-After` hint on EVERY outcome, not just RETRYABLE.
        // It used to be cleared only in the RETRYABLE arm, so a pass that picked
        // up a one-hour header and then ended AUTH_REQUIRED / FATAL / REJECTED /
        // SUCCESS left it armed — and the next retryable pass, possibly days
        // later, inherited `maxOf(backoff.next(), thatStaleHour)` and slept an
        // hour it had no reason to. Windows takes both hints unconditionally at
        // the top of `finish` (scheduler.rs:400-401) precisely for this.
        //
        // Deliberate consequence: if one domain is rate-limited and a LATER
        // domain still succeeds, the pass ends SUCCESS and the hint is dropped.
        // That is correct — the pass succeeded, the limit was transient and
        // absorbed, and the next cadence is the right schedule. Carrying a
        // stale hour forward would be worse.
        val retryAfter = pendingRetryAfterMs
        pendingRetryAfterMs = null
        when (outcome) {
            PassOutcomeKind.SUCCESS -> {
                lastSyncAtMs = now
                nextAttemptMs = now + cadenceMs
                backoff.reset()
                degraded = false
            }
            PassOutcomeKind.DEGRADED -> {
                // F4 Success-with-warning: converged data is recorded exactly
                // like a success (the skipped state is permanent, never
                // transient) while the warning stays visible until a clean
                // pass. Never stalls the clock, never parks an error.
                lastSyncAtMs = now
                nextAttemptMs = now + cadenceMs
                backoff.reset()
                degraded = true
            }
            PassOutcomeKind.RETRYABLE -> {
                // When the response carried an explicit `Retry-After`, wait at
                // least that long too (never sooner than the header demands).
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
