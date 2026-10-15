package com.groq.voicetyper.sync

import com.groq.voicetyper.sync.scheduler.PassOutcomeKind
import com.groq.voicetyper.sync.scheduler.SyncSchedulerCore
import com.groq.voicetyper.sync.v1.SyncError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F18 — a cancelled pass recorded as SUCCESS.
 *
 * `runPass` seeded the pass outcome with the `SUCCESS` sentinel and completed
 * the pass from a `finally`. A cancellation delivered inside `runV12Pass()`
 * (`destroy()` -> `passScope.cancel()`) is an ordinary `Exception` — the
 * catch-all swallowed it, and the `finally` then completed the pass with
 * whatever was in the variable: `SUCCESS`. `lastSyncAtMs` advanced and
 * "Last synced" was persisted for a pass that never finished, which the UI then
 * reported as a clean sync.
 *
 * Cancellation is now classified FIRST and reported as "record nothing":
 * `guardedPass` invokes its settle hook with `null`, `runPass` clears `running`
 * via `cancelPass()` without completing a pass, and the cancellation keeps
 * propagating so structured concurrency still observes it.
 */
class CancelledPassFixTest {

    @Test
    fun a_cancelled_pass_records_nothing_and_never_success() = runBlocking {
        var recorded: PassOutcomeKind? = PassOutcomeKind.DEGRADED
        var settled = 0
        val job = launch(Dispatchers.Default) {
            runCatching {
                guardedPass(
                    pass = {
                        delay(10_000L)
                        PassOutcomeKind.SUCCESS
                    },
                    onSettled = { settled++; recorded = it },
                )
            }
        }
        delay(50L)
        job.cancelAndJoin()

        assertEquals("the settle hook must run exactly once", 1, settled)
        assertNull(
            "a cancelled pass must record NO outcome (recorded $recorded), never SUCCESS",
            recorded,
        )
    }

    @Test
    fun cancellation_is_classified_as_record_nothing() = runBlocking {
        assertNull(passFailureOutcome(CancellationException("destroy()")))
        // TimeoutCancellationException IS a CancellationException, so the
        // worker's `withTimeout(120_000)` also lands here — and must record
        // nothing rather than a completed pass.
        var captured: Throwable? = null
        try {
            withTimeout(1L) { delay(10_000L) }
        } catch (e: Throwable) {
            captured = e
        }
        assertTrue(
            "expected a TimeoutCancellationException, was ${captured?.javaClass?.name}",
            captured is TimeoutCancellationException,
        )
        assertNull(
            "an enclosing timeout must record nothing either",
            passFailureOutcome(captured!!),
        )
    }

    @Test
    fun classified_failures_keep_their_previous_mapping() {
        assertEquals(PassOutcomeKind.AUTH_REQUIRED, passFailureOutcome(SyncError.AuthRequired))
        assertEquals(
            PassOutcomeKind.RETRYABLE,
            passFailureOutcome(SyncError.RateLimited(1_000L)),
        )
        assertEquals(PassOutcomeKind.RETRYABLE, passFailureOutcome(SyncError.Retryable("x")))
        assertEquals(PassOutcomeKind.RETRYABLE, passFailureOutcome(SyncError.StaleVersion("7")))
        assertEquals(PassOutcomeKind.FATAL, passFailureOutcome(SyncError.Fatal("x")))
        assertEquals(PassOutcomeKind.FATAL, passFailureOutcome(SyncError.Rejected("x")))
        assertEquals(PassOutcomeKind.RETRYABLE, passFailureOutcome(IllegalStateException("x")))
    }

    @Test
    fun a_completed_pass_still_records_its_outcome() = runBlocking {
        val recorded = mutableListOf<PassOutcomeKind?>()
        val returned = guardedPass(pass = { PassOutcomeKind.SUCCESS }, onSettled = { recorded += it })
        assertEquals(PassOutcomeKind.SUCCESS, returned)
        assertEquals(listOf<PassOutcomeKind?>(PassOutcomeKind.SUCCESS), recorded)
    }

    @Test
    fun a_failed_pass_still_records_its_classified_outcome() = runBlocking {
        val recorded = mutableListOf<PassOutcomeKind?>()
        val returned = guardedPass(
            pass = { throw SyncError.Fatal("permanent") },
            onSettled = { recorded += it },
        )
        assertEquals(PassOutcomeKind.FATAL, returned)
        assertEquals(listOf<PassOutcomeKind?>(PassOutcomeKind.FATAL), recorded)
    }

    @Test
    fun recording_a_cancelled_pass_clears_running_without_advancing_the_clock() {
        // Exactly what `runPass` now does when the settle hook receives `null`.
        val core = SyncSchedulerCore(nowMs = { 1_000L })
        core.beginPass()
        assertTrue(core.running)
        core.cancelPass()

        assertFalse("the UI must never stick on \"Syncing…\"", core.running)
        assertNull("no outcome may be recorded for a pass that never finished", core.lastOutcome)
        assertNull("\"Last synced\" must not advance for a pass that never finished", core.lastSyncAtMs)
    }

    @Test
    fun contrast_a_recorded_success_does_advance_the_clock() {
        // The mutation target: with the old `finally { completePass(outcome) }`
        // the sentinel SUCCESS reached this call and produced exactly the state
        // the test above forbids.
        val core = SyncSchedulerCore(nowMs = { 1_000L })
        core.beginPass()
        core.completePass(PassOutcomeKind.SUCCESS)
        assertEquals(1_000L, core.lastSyncAtMs)
        assertEquals(PassOutcomeKind.SUCCESS, core.lastOutcome)
    }
}