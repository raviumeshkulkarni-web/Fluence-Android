package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun parse_retry_after_reads_integer_seconds() {
        assertNull(parseRetryAfterMs(null))
        assertEquals(5_000L, parseRetryAfterMs("5")!!)
        assertEquals(5_000L, parseRetryAfterMs(" 5 ")!!)
        assertNull("zero (immediate) is not a delay", parseRetryAfterMs("0"))
        assertNull(parseRetryAfterMs("-3"))
        assertNull(parseRetryAfterMs("abc"))
        assertNull("absent header -> null", parseRetryAfterMs(""))
    }

    @Test
    fun rate_limited_carries_the_retry_after_delay() {
        // A 429 with a parsed delay surfaces RateLimited { Some(delay) }; the
        // steady-state 429 (no usable header) stays a plain Retryable.
        assertTrue(SyncError.RateLimited(5_000L).retryAfterMs == 5_000L)
        assertNull(SyncError.RateLimited(null).retryAfterMs)
        assertTrue(SyncError.RateLimited(5_000L).message.orEmpty().contains("rate limited"))
    }
}