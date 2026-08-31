package com.groq.voicetyper.sync.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * User-facing copy for pass outcomes (requirement: never claim "synced" or
 * blame Drive authorization for something else; AUTH_REQUIRED now points the
 * user to reauthorization instead of "Session expired").
 */
class SyncOutcomeMessageTest {

    @Test
    fun authRequired_pointsToReauthorization_notSessionExpired() {
        assertEquals(
            "Please reconnect your Google account",
            syncOutcomeMessage("AUTH_REQUIRED")
        )
    }

    @Test
    fun retryable_promisesAutomaticRetry() {
        assertEquals(
            "Sync will retry automatically",
            syncOutcomeMessage("RETRYABLE")
        )
    }

    @Test
    fun nullIsEmpty_notSyncedClaim() {
        assertEquals("", syncOutcomeMessage(null))
    }

    @Test
    fun unknownOutcome_isRenderedRawForDiagnostics() {
        assertEquals("Sync needs attention", syncOutcomeMessage("SOME_NEW_OUTCOME"))
    }
}