package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §27 categories: Drive 403 classification mirrors Windows `classify_forbidden`
 * (drive.rs) — transient/quota reasons surface as [SyncError.Retryable] (next
 * pass retries with backoff) instead of [SyncError.Fatal]; a genuine
 * ownership/scope denial stays [SyncError.Fatal]; unparseable bodies default to
 * Retryable (fail-closed retry is safer than wedging the account).
 */
class AppDataDriveStoreForbiddenTest {

    @Test
    fun transient_reasons_are_retryable() {
        for (reason in listOf(
            "userRateLimitExceeded",
            "rateLimitExceeded",
            "dailyLimitExceeded",
            "sharedLimitExceeded",
            "quotaExceeded",
            "backendError"
        )) {
            val e = classifyForbidden("""{"error":{"reason":"$reason"}}""")
            assertTrue("$reason should classify as Retryable", e is SyncError.Retryable)
        }
    }

    @Test
    fun non_transient_reason_is_fatal() {
        assertTrue(classifyForbidden("""{"error":{"reason":"insufficientFilePermissions"}}""") is SyncError.Fatal)
        assertTrue(classifyForbidden("""{"error":{"reason":"appNotAuthorizedToFile"}}""") is SyncError.Fatal)
    }

    @Test
    fun nested_errors_array_reason_is_read() {
        val body = """{"error":{"code":403,"errors":[{"reason":"quotaExceeded"}]}}"""
        assertTrue(classifyForbidden(body) is SyncError.Retryable)
    }

    @Test
    fun unparseable_or_empty_body_is_retryable() {
        assertTrue(classifyForbidden("not json at all") is SyncError.Retryable)
        assertTrue(classifyForbidden("") is SyncError.Retryable)
        assertTrue(classifyForbidden("{}") is SyncError.Retryable)
        assertTrue(classifyForbidden("""{"error":{}}""") is SyncError.Retryable)
    }
}