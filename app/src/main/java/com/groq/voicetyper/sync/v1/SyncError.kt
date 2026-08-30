package com.groq.voicetyper.sync.v1

/**
 * Engine-level error kinds shared by the Drive layer and the v1.2 engine:
 * - [Retryable]: transient (timeouts, 429, 5xx) — caller may retry within the
 *   pass or the scheduler backs off.
 * - [StaleVersion]: a concurrent writer changed the remote domain file between
 *   our GET and PUT (detected via Drive's per-file `version` revision). The
 *   engine re-fetches, re-merges and retries. This is the v1.2 replacement
 *   for If-Match/412, which Drive API v3 does not honor.
 * - [Fatal]: permanent permission/scope problem — surface to UI, never
 *   retry-bomb.
 * - [Rejected]: permanent client rejection (4xx outside the mapped set).
 * - [AuthRequired]: token missing/expired/revoked — re-authenticate.
 */
sealed class SyncError(message: String? = null) : Exception(message) {
    class Retryable(message: String) : SyncError(message)
    /**
     * A transient 429 that carried a `Retry-After` header. Same retryable
     * scheduling path as [Retryable], but the header's explicit delay (ms)
     * must gate the next attempt (see SyncSchedulerCore) so a throttled API
     * is not hammered before it says we may retry. `null` when no usable
     * header was present — the scheduler falls back to its backoff.
     */
    class RateLimited(val retryAfterMs: Long?) : SyncError("rate limited")
    class StaleVersion(val liveVersion: String?) : SyncError("remote changed during sync: ${liveVersion ?: "<none>"}")
    class Fatal(message: String) : SyncError(message)
    class Rejected(message: String) : SyncError(message)
    object AuthRequired : SyncError("authentication required")
}

/** Token liveness seam — the engine never sees token material. */
interface TokenProvider {
    fun hasValidToken(): Boolean
}
