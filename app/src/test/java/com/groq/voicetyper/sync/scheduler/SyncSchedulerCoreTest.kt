package com.groq.voicetyper.sync.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncSchedulerCoreTest {

    private var now = 0L
    private val clock = { now }

    private fun core(): SyncSchedulerCore = SyncSchedulerCore(nowMs = clock)

    @Test
    fun firstPollRunsImmediately() {
        val core = core()
        assertTrue(core.pollTick())
    }

    @Test
    fun successPushesNextAttemptToCadence() {
        val core = core()
        now = 10_000L
        core.beginPass()
        core.completePass(PassOutcomeKind.SUCCESS)
        assertEquals(10_000L, core.lastSyncAtMs!!)
        assertEquals(310_000L, core.nextAttemptMs)
        assertFalse(core.pollTick())
        now = 310_000L
        assertTrue(core.pollTick())
    }

    @Test
    fun retryableBacksOffExponentiallyToCap() {
        val core = core()
        // nextAttempt = now + backoff; backoff doubles 1000 → 60s cap.
        // 0→1000→3000→7000→15000→31000→63000→123000→183000
        val expected = longArrayOf(1_000, 3_000, 7_000, 15_000, 31_000, 63_000, 123_000, 183_000)
        for (target in expected) {
            now = core.nextAttemptMs
            core.beginPass()
            core.completePass(PassOutcomeKind.RETRYABLE)
            assertEquals(target, core.nextAttemptMs)
        }
        // The last backoff increment is capped at 60s.
        assertEquals(60_000L, expected[7] - expected[6])
    }

    @Test
    fun successResetsBackoff() {
        val core = core()
        now = 0L
        core.beginPass()
        core.completePass(PassOutcomeKind.RETRYABLE)
        assertEquals(1_000L, core.nextAttemptMs)
        now = 1_000L
        core.beginPass()
        core.completePass(PassOutcomeKind.SUCCESS)
        assertEquals(301_000L, core.nextAttemptMs)
        now = 301_000L
        core.beginPass()
        core.completePass(PassOutcomeKind.RETRYABLE)
        assertEquals(302_000L, core.nextAttemptMs) // backoff restarted at 1000
    }

    @Test
    fun fatalKeepsCadenceSchedule() {
        val core = core()
        now = 5_000L
        core.beginPass()
        core.completePass(PassOutcomeKind.FATAL)
        assertEquals(305_000L, core.nextAttemptMs)
        assertNull(core.lastSyncAtMs)
    }

    @Test
    fun authRequiredWaitsIdlePollForReauth() {
        val core = core()
        now = 5_000L
        core.beginPass()
        core.completePass(PassOutcomeKind.AUTH_REQUIRED)
        assertEquals(3_605_000L, core.nextAttemptMs)
        assertFalse(core.pollTick())
        now = 3_605_000L
        assertTrue(core.pollTick())
    }

    @Test
    fun runningPassCoalescesInsteadOfQueuing() {
        val core = core()
        // A pass starts and is still running; polls coalesce into pending.
        core.beginPass()
        assertFalse(core.pollTick())
        assertTrue(core.pending)
        assertFalse(core.pollTick())
        assertTrue(core.pending)
        // Pass finishes (success); the pending poll waits for the cadence
        // before running again — single flight, never a queue.
        core.completePass(PassOutcomeKind.SUCCESS)
        assertFalse(core.pollTick())
        now = core.nextAttemptMs
        assertTrue(core.pollTick())
        assertFalse(core.pending)
    }

    @Test
    fun pendingPassRunsAtBackoffElapsedTime() {
        val core = core()
        core.beginPass()
        core.pollTick() // sets pending
        core.completePass(PassOutcomeKind.RETRYABLE)
        assertEquals(1_000L, core.nextAttemptMs)
        assertFalse(core.pollTick())
        now = 1_000L
        assertTrue(core.pollTick())
        assertFalse(core.pending)
    }
}

class SyncBackoffTest {

    @Test
    fun doublesUntilCap() {
        val backoff = SyncBackoff()
        assertEquals(1_000L, backoff.next())
        assertEquals(2_000L, backoff.next())
        assertEquals(4_000L, backoff.next())
        assertEquals(8_000L, backoff.next())
        assertEquals(16_000L, backoff.next())
        assertEquals(32_000L, backoff.next())
        assertEquals(60_000L, backoff.next())
        assertEquals(60_000L, backoff.next())
    }

    @Test
    fun resetRestarts() {
        val backoff = SyncBackoff()
        backoff.next()
        backoff.next()
        backoff.reset()
        assertEquals(1_000L, backoff.next())
    }
}