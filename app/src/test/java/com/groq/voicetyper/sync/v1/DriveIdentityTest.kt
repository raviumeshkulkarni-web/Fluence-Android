package com.groq.voicetyper.sync.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAGE 1 — authenticated identity binding and the cross-platform hash contract.
 *
 * Two properties are locked here:
 *
 *  1. `DriveIdentity.parseAccountEmail` fails CLOSED. A malformed, partial or
 *     blank `about` response must yield null so the sync pass aborts, rather
 *     than partitioning on an empty or fabricated key.
 *
 *  2. `AccountHash.of` is byte-identical to Windows
 *     `metadata::account_hash_from_email` — full 64-char lowercase hex of
 *     SHA-256 over `lower(trim(email))`. The literal vectors below were
 *     computed independently and are asserted on BOTH platforms, so a future
 *     divergence (such as reintroducing a 16-char truncation) fails a test
 *     rather than silently splitting every account partition.
 */
class DriveIdentityTest {

    // ------------------------------------------------------------------
    // parseAccountEmail — fail-closed
    // ------------------------------------------------------------------

    @Test
    fun parses_the_authenticated_email() {
        assertEquals(
            "me@example.com",
            DriveIdentity.parseAccountEmail("""{"user":{"emailAddress":"me@example.com"}}"""),
        )
    }

    @Test
    fun trims_surrounding_whitespace() {
        assertEquals(
            "me@example.com",
            DriveIdentity.parseAccountEmail("""{"user":{"emailAddress":"  me@example.com  "}}"""),
        )
    }

    @Test
    fun missing_user_object_fails_closed() {
        assertNull(DriveIdentity.parseAccountEmail("""{}"""))
        assertNull(DriveIdentity.parseAccountEmail("""{"kind":"drive#about"}"""))
    }

    @Test
    fun missing_email_address_fails_closed() {
        assertNull(DriveIdentity.parseAccountEmail("""{"user":{}}"""))
        assertNull(DriveIdentity.parseAccountEmail("""{"user":{"displayName":"Me"}}"""))
    }

    @Test
    fun blank_email_fails_closed() {
        // A blank address must never be treated as a valid identity — that
        // would partition on an empty key instead of aborting.
        assertNull(DriveIdentity.parseAccountEmail("""{"user":{"emailAddress":""}}"""))
        assertNull(DriveIdentity.parseAccountEmail("""{"user":{"emailAddress":"   "}}"""))
        assertNull(DriveIdentity.parseAccountEmail("""{"user":{"emailAddress":null}}"""))
    }

    @Test
    fun malformed_json_fails_closed() {
        assertNull(DriveIdentity.parseAccountEmail(""))
        assertNull(DriveIdentity.parseAccountEmail("not json"))
        assertNull(DriveIdentity.parseAccountEmail("""{"user":"""))
    }

    @Test
    fun error_shaped_payload_yields_no_identity() {
        // A Drive error body must not be mistaken for an identity.
        assertNull(
            DriveIdentity.parseAccountEmail("""{"error":{"code":401,"message":"Invalid Credentials"}}"""),
        )
    }

    // ------------------------------------------------------------------
    // Outcome classification (shared by the pass entry AND the 401 refresher)
    // ------------------------------------------------------------------

    @Test
    fun unauthorized_statuses_classify_as_unauthorized() {
        // These must become AuthRequired: consent revoked / token genuinely dead.
        assertTrue(DriveIdentity.classify(401, null) is DriveIdentity.IdentityResult.Unauthorized)
        assertTrue(DriveIdentity.classify(403, null) is DriveIdentity.IdentityResult.Unauthorized)
    }

    @Test
    fun transient_statuses_classify_as_unavailable_not_unauthorized() {
        // The regression this pins: a Drive blip must NOT be reported to the
        // user as "sign in again". Both the pass entry and the token refresher
        // route Unavailable to Retryable.
        for (code in listOf(429, 500, 502, 503, 504)) {
            val r = DriveIdentity.classify(code, null)
            assertTrue(
                "http $code must be Unavailable, was $r",
                r is DriveIdentity.IdentityResult.Unavailable,
            )
        }
    }

    @Test
    fun success_with_a_usable_payload_resolves() {
        val r = DriveIdentity.classify(200, """{"user":{"emailAddress":"a@b.com"}}""")
        assertEquals("a@b.com", (r as DriveIdentity.IdentityResult.Resolved).email)
    }

    @Test
    fun success_with_an_unusable_payload_fails_closed_as_unavailable() {
        // 2xx but we cannot prove ownership: must not resolve, must not be
        // treated as authorized.
        for (body in listOf(null, "", "{}", """{"user":{}}""", "not json")) {
            val r = DriveIdentity.classify(200, body)
            assertTrue(
                "body=$body must not resolve, was $r",
                r is DriveIdentity.IdentityResult.Unavailable,
            )
        }
    }

    @Test
    fun only_resolved_carries_an_identity() {
        // Guards the `as? Resolved` casts at both call sites: a non-Resolved
        // outcome must never yield an email, so no caller can accidentally
        // proceed on an unproven identity.
        val outcomes = listOf(
            DriveIdentity.classify(401, null),
            DriveIdentity.classify(503, null),
            DriveIdentity.classify(200, "{}"),
        )
        for (o in outcomes) {
            assertTrue(o !is DriveIdentity.IdentityResult.Resolved)
        }
    }

    // ------------------------------------------------------------------
    // C2 — a 403 is only an auth problem when Drive says so
    // ------------------------------------------------------------------

    /** Drive API v3 nests the reason under `error.errors[0]`. */
    private fun forbiddenBody(reason: String) =
        """{"error":{"code":403,"message":"Forbidden","errors":[{"reason":"$reason","domain":"global"}]}}"""

    /** Some Drive errors carry the reason directly on `error`. */
    private fun forbiddenBodyFlat(reason: String) =
        """{"error":{"code":403,"reason":"$reason"}}"""

    @Test
    fun a_throttled_403_is_retryable_not_an_auth_problem() {
        // The regression: every 403 used to become Unauthorized, so a per-user
        // throttle told the user to sign in again for a condition that clears
        // on its own, on an account that was working fine.
        for (reason in listOf(
            "userRateLimitExceeded", "rateLimitExceeded", "dailyLimitExceeded",
            "sharedLimitExceeded", "quotaExceeded", "backendError"
        )) {
            for (body in listOf(forbiddenBody(reason), forbiddenBodyFlat(reason))) {
                val r = DriveIdentity.classify(403, body)
                assertTrue(
                    "403/$reason must be Unavailable, was $r",
                    r is DriveIdentity.IdentityResult.Unavailable,
                )
            }
        }
    }

    @Test
    fun a_genuine_403_denial_stays_unauthorized() {
        for (reason in listOf("insufficientFilePermissions", "notFound", "insufficientPermissions")) {
            val r = DriveIdentity.classify(403, forbiddenBody(reason))
            assertTrue(
                "403/$reason is a real permission denial and must stay Unauthorized, was $r",
                r is DriveIdentity.IdentityResult.Unauthorized,
            )
        }
    }

    @Test
    fun a_403_whose_reason_cannot_be_read_stays_unauthorized() {
        // Unchanged on purpose: an uninspectable 403 must not be guessed into
        // "just a blip". Both outcomes fail closed with no read/write/upload.
        for (body in listOf(null, "", "not json", "{}", """{"error":{}}""")) {
            val r = DriveIdentity.classify(403, body)
            assertTrue(
                "body=$body must stay Unauthorized, was $r",
                r is DriveIdentity.IdentityResult.Unauthorized,
            )
        }
    }

    @Test
    fun the_reason_agrees_with_the_data_path_on_every_shared_case() {
        // DriveIdentity and AppDataDriveStore must not disagree about what a
        // 403 means. DriveIdentity reuses the data path's reason set; this test
        // is what stops the two from drifting apart again.
        for (reason in listOf(
            "userRateLimitExceeded", "rateLimitExceeded", "dailyLimitExceeded",
            "sharedLimitExceeded", "quotaExceeded", "backendError",
            "insufficientFilePermissions", "notFound"
        )) {
            val body = forbiddenBody(reason)
            // `classifyForbidden` RETURNS the SyncError; its callers throw it.
            val dataPath = classifyForbidden(body)
            val dataPathRetryable = dataPath is SyncError.Retryable
            val identity = DriveIdentity.classify(403, body)
            val identityRetryable = identity is DriveIdentity.IdentityResult.Unavailable
            assertEquals(
                "classification of 403/$reason diverged: data path=$dataPath, identity=$identity",
                dataPathRetryable,
                identityRetryable,
            )
        }
    }

    @Test
    fun retry_after_is_preserved_on_429() {
        val r = DriveIdentity.classify(429, null, retryAfterMs = 5_000L)
        assertTrue(r is DriveIdentity.IdentityResult.Unavailable)
        assertEquals(5_000L, (r as DriveIdentity.IdentityResult.Unavailable).retryAfterMs)
    }

    @Test
    fun retry_after_is_preserved_on_a_throttled_403() {
        val r = DriveIdentity.classify(403, forbiddenBody("userRateLimitExceeded"), retryAfterMs = 2_500L)
        assertTrue(r is DriveIdentity.IdentityResult.Unavailable)
        assertEquals(2_500L, (r as DriveIdentity.IdentityResult.Unavailable).retryAfterMs)
    }

    @Test
    fun retry_after_is_absent_when_the_server_sent_none() {
        for (code in listOf(429, 503)) {
            val r = DriveIdentity.classify(code, null)
            assertNull(
                "http $code must not invent a Retry-After",
                (r as DriveIdentity.IdentityResult.Unavailable).retryAfterMs,
            )
        }
    }

    // ------------------------------------------------------------------
    // Cross-platform hash contract (locked literals)
    // ------------------------------------------------------------------

    @Test
    fun account_hash_matches_the_windows_reference_vector() {
        // Independently computed; asserted identically in
        // windows-main/src-tauri/src/sync/metadata.rs tests.
        assertEquals(
            "973dfe463ec85785f5f95af5ba3906eedb2d931c24e69824a89ea65dba4e813b",
            AccountHash.of("test@example.com"),
        )
        assertEquals(
            "7b8a6e03ce872608896365b0d9de0e8a3bf4c400c409d5620af6d710fb8ebb00",
            AccountHash.of("user+tag@Example.co.uk"),
        )
    }

    @Test
    fun account_hash_normalises_case_and_whitespace() {
        // Windows metadata.rs asserts the same normalisation.
        val canonical = AccountHash.of("test@example.com")
        assertEquals(canonical, AccountHash.of("  Test@Example.COM "))
        assertEquals(canonical, AccountHash.of("TEST@EXAMPLE.COM"))
    }

    @Test
    fun account_hash_is_exactly_64_lowercase_hex_chars() {
        val hash = AccountHash.of("someone@example.com")!!
        assertEquals(64, hash.length)
        assertEquals(hash.lowercase(), hash)
        assertEquals(true, hash.all { it in "0123456789abcdef" })
    }

    @Test
    fun account_hash_is_not_truncated_to_16_chars() {
        // Regression guard for the stale `AccountStore.kt` comment that claimed
        // a 16-char truncation. The hash becomes a Drive path segment in Stage 3,
        // so a truncation here would split every account partition silently.
        val hash = AccountHash.of("test@example.com")!!
        assertNotEquals(hash.take(16), hash)
        assertEquals(64, hash.length)
        assertTrue("16-char prefix must be a strict prefix", hash.startsWith(hash.take(16)))
    }

    @Test
    fun blank_or_null_account_yields_no_hash() {
        assertNull(AccountHash.of(null))
        assertNull(AccountHash.of(""))
        assertNull(AccountHash.of("   "))
    }

    @Test
    fun distinct_accounts_yield_distinct_partitions() {
        // The partition key must separate accounts, otherwise two Google
        // accounts could address the same Drive partition.
        assertNotEquals(AccountHash.of("a@example.com"), AccountHash.of("b@example.com"))
    }
}
