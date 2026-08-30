package com.groq.voicetyper.sync

import com.groq.voicetyper.sync.scheduler.PassOutcomeKind
import com.groq.voicetyper.sync.v1.V1SyncEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncManagerOutcomeTest {

    @Test
    fun corrupt_remote_with_no_usable_local_state_is_retryable_not_success() {
        val result = V1SyncEngine.SyncResult(uploaded = false, merged = false, skippedCorrupt = true)
        assertEquals(PassOutcomeKind.RETRYABLE, domainOutcome(result))
    }

    @Test
    fun converged_remote_apply_is_success() {
        assertEquals(
            PassOutcomeKind.SUCCESS,
            domainOutcome(V1SyncEngine.SyncResult(uploaded = false, merged = true))
        )
    }

    @Test
    fun uploaded_pass_is_success() {
        assertEquals(
            PassOutcomeKind.SUCCESS,
            domainOutcome(V1SyncEngine.SyncResult(uploaded = true, merged = true))
        )
    }

    @Test
    fun empty_noop_pass_is_success() {
        assertEquals(
            PassOutcomeKind.SUCCESS,
            domainOutcome(V1SyncEngine.SyncResult(uploaded = false, merged = false))
        )
    }
}