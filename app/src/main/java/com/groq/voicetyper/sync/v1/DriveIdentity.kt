package com.groq.voicetyper.sync.v1

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * STAGE 1 — bind the sync account identity to the AUTHENTICATED Google account.
 *
 * ## Why this exists
 *
 * Drive partitions `appDataFolder` by the account that owns the access token.
 * Before this, Android derived its partition key from `SyncAuthSession.accountEmail`
 * — a *locally persisted string* — while the token itself was minted from
 * `GoogleSignIn.getLastSignedInAccount()` (see `SyncAuthSession.refreshAccessTokenIfNeeded`).
 * Those are two independent sources, deliberately allowed to drift apart so the
 * token "survives email renames".
 *
 * The consequence was that a token minted for one Google account could be paired
 * with a partition key derived from a different, stale, persisted email — writing
 * one account's payload into another account's `appDataFolder` with no error.
 * `PassAccountGuard` could not catch it because it compared two copies of the same
 * persisted string and never inspected the token.
 *
 * ## The fix
 *
 * Ask Drive who the token actually belongs to, using the token itself:
 * `GET about?fields=user` -> `{"user":{"emailAddress":"..."}}`.
 * The resulting email is, by construction, the identity that owns the
 * `appDataFolder` this pass is about to touch. It cannot drift from the token.
 *
 * This mirrors the Windows reference implementation exactly:
 *   - `sync/scheduler.rs:57`  — `SYNC_ABOUT_URL = ".../about?fields=user"`
 *   - `sync/scheduler.rs:730` — `parse_account_email` (pure parser, blank -> None)
 *   - `sync/metadata.rs:163`  — `account_hash_from_email` = SHA-256 of lower(trim(email))
 *
 * The persisted `accountEmail` remains as a *display cache only*. It is never
 * trusted as the authoritative sync identity.
 */
object DriveIdentity {

    const val ABOUT_URL = "https://www.googleapis.com/drive/v3/about?fields=user"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Pure parser for a Drive `about` response.
     *
     * Returns null when the payload is unparseable, `user`/`emailAddress` is
     * absent, or the address is blank. Blank is treated as absent so a
     * malformed response can never be mistaken for a valid identity — the
     * caller fails closed rather than partitioning on an empty key.
     *
     * Mirrors Windows `parse_account_email`.
     */
    fun parseAccountEmail(json: String): String? = try {
        val user = org.json.JSONObject(json).optJSONObject("user")
        val email = user?.optString("emailAddress", "")?.trim()
        if (email.isNullOrEmpty()) null else email
    } catch (_: Exception) {
        null
    }

    /**
     * Outcome of resolving the token-bound identity.
     *
     * STAGE 1 review finding: collapsing every failure into a single `null` made
     * a transient Drive 5xx / 429 / connectivity timeout indistinguishable from
     * a genuine 401, so a blip surfaced to the user as "sign in again" for an
     * account that was working fine. The two cases are separated here so the
     * caller can fail closed WITHOUT misreporting a blip as an auth problem.
     */
    sealed interface IdentityResult {
        /** Identity established from the token. */
        data class Resolved(val email: String) : IdentityResult

        /** Drive refused the token (401/403). A real authorization problem. */
        data class Unauthorized(val code: Int) : IdentityResult

        /** Transient: 5xx, 429, timeout, or connectivity. Retry later. */
        data class Unavailable(val reason: String) : IdentityResult
    }

    /**
     * The account email that owns [token].
     *
     * Callers MUST fail closed on a non-[IdentityResult.Resolved] result. There
     * is deliberately no fallback to a persisted value — a fallback is precisely
     * the defect this object exists to remove.
     */
    suspend fun resolveAccountEmail(token: String): IdentityResult =
        withContext(Dispatchers.IO) { resolveAccountEmailBlocking(token) }

    /**
     * Blocking variant, for callers that are already on an I/O thread and cannot
     * suspend — notably `AccessTokenRefresher`, which is a plain `fun interface`
     * invoked from inside the sync pass. The pass runs under
     * `withContext(Dispatchers.IO)`, so blocking here does not block a UI thread.
     */
    /**
     * Pure classification of a Drive `about` outcome. Extracted so the
     * resolved/unauthorized/unavailable decision is unit-testable without a
     * network, and so BOTH call sites (the pass entry and the 401 refresher)
     * classify identically — a divergence there is how a transient 5xx ends up
     * presented to the user as "sign in again".
     */
    fun classify(httpCode: Int, body: String?): IdentityResult = when {
        httpCode == 401 || httpCode == 403 -> IdentityResult.Unauthorized(httpCode)
        httpCode !in 200..299 -> IdentityResult.Unavailable("http $httpCode")
        else -> {
            val email = body?.let { parseAccountEmail(it) }
            if (email != null) {
                IdentityResult.Resolved(email)
            } else {
                // 2xx with an unusable payload: the token was accepted but we
                // cannot prove who it belongs to. Fail closed.
                IdentityResult.Unavailable("unparseable about payload")
            }
        }
    }

    fun resolveAccountEmailBlocking(token: String): IdentityResult {
        val request = Request.Builder()
            .url(ABOUT_URL)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                classify(response.code, if (response.isSuccessful) response.body?.string() else null)
            }
        } catch (e: java.io.IOException) {
            IdentityResult.Unavailable(e.message ?: "io")
        } catch (_: Exception) {
            IdentityResult.Unavailable("unexpected")
        }
    }

    /** Convenience: the account email, or null when it cannot be established. */
    suspend fun authenticatedAccountEmail(token: String): String? =
        (resolveAccountEmail(token) as? IdentityResult.Resolved)?.email
}
