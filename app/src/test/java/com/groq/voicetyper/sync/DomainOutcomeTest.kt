package com.groq.voicetyper.sync

import com.groq.voicetyper.sync.scheduler.PassOutcomeKind
import com.groq.voicetyper.sync.v1.V1SyncEngine.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F4 — [domainOutcome] must distinguish "converged with skips" (DEGRADED:
 * record the sync, warn) from "nothing converged" (RETRYABLE: try again).
 * A skipped-corrupt domain that uploaded or merged data proved convergence;
 * one that did neither proved nothing and must not read as synced.
 */
class DomainOutcomeTest {

    @Test
    fun converged_with_skips_is_degraded_not_retryable() {
        assertEquals(
            PassOutcomeKind.DEGRADED,
            domainOutcome(SyncResult(uploaded = true, merged = true, skippedCorrupt = true))
        )
        assertEquals(
            PassOutcomeKind.DEGRADED,
            domainOutcome(SyncResult(uploaded = false, merged = true, skippedCorrupt = true))
        )
        assertEquals(
            PassOutcomeKind.DEGRADED,
            domainOutcome(SyncResult(uploaded = true, merged = false, skippedCorrupt = true))
        )
    }

    @Test
    fun nothing_converged_with_skips_stays_retryable() {
        assertEquals(
            PassOutcomeKind.RETRYABLE,
            domainOutcome(SyncResult(uploaded = false, merged = false, skippedCorrupt = true))
        )
    }

    @Test
    fun clean_domains_stay_success() {
        assertEquals(
            PassOutcomeKind.SUCCESS,
            domainOutcome(SyncResult(uploaded = true, merged = true))
        )
        assertEquals(
            PassOutcomeKind.SUCCESS,
            domainOutcome(SyncResult(uploaded = false, merged = false))
        )
    }
}
