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

    /** Drive API v3 nests the reason under `error.errors[0]`. */
    private fun forbiddenBody(reason: String) =
        """{"error":{"code":403,"message":"Forbidden","errors":[{"reason":"$reason","domain":"global"}]}}"""

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

    /**
     * A quota-shaped 403 that carries `Retry-After` must become [SyncError.RateLimited]
     * so the scheduler honours the delay. Without this the `dailyLimitExceeded`
     * header was dropped on the floor and the scheduler re-polled on its 60 s
     * exponential cap forever — roughly 1,440 doomed requests per day against a
     * server that asked for a day, each re-tripping the quota counter. Windows
     * fixed this in `classify_forbidden_with_retry_after` (drive.rs); this is the
     * Android twin. Mirrors that function's `non_quota_403_ignores_retry_after`
     * case: a permission denial must stay Fatal even when a header is present,
     * so a stray header can never convert a fatal stop into a retry loop.
     */
    @Test
    fun quota_403_with_retry_after_becomes_rate_limited() {
        val body = forbiddenBody("dailyLimitExceeded")
        val err = classifyForbidden(body, 86_400_000L)
        assertTrue(
            "a quota 403 with an explicit delay must be RateLimited, got $err",
            err is SyncError.RateLimited
        )
        assertEquals(86_400_000L, (err as SyncError.RateLimited).retryAfterMs)
    }

    @Test
    fun quota_403_without_retry_after_stays_retryable() {
        val err = classifyForbidden(forbiddenBody("dailyLimitExceeded"), null)
        assertTrue("no header must stay Retryable, got $err", err is SyncError.Retryable)
    }

    @Test
    fun non_quota_403_with_retry_after_stays_fatal() {
        // The negative that matters most: honouring a Retry-After must never
        // rescue a genuine permission denial into a retry loop.
        for (reason in listOf("insufficientFilePermissions", "forbidden", "notFound")) {
            val err = classifyForbidden(forbiddenBody(reason), 5_000L)
            assertTrue(
                "$reason must stay Fatal even with a header, got $err",
                err is SyncError.Fatal
            )
        }
    }

    @Test
    fun unparseable_403_body_is_retryable_even_with_a_header() {
        val err = classifyForbidden("not json at all", 5_000L)
        assertTrue("unparseable body must fail open to Retryable, got $err", err is SyncError.Retryable)
    }
}