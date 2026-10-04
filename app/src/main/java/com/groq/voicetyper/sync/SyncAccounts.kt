package com.groq.voicetyper.sync

import android.content.Context
import com.groq.voicetyper.sync.auth.SyncAuthSession
import com.groq.voicetyper.sync.v1.AccountHash
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The current signed-in sync account, cached process-wide so the UI and
 * repositories can check ownership without re-opening encrypted prefs on
 * every call. Refreshed by [SyncManager] on sign-in/out and every pass.
 *
 * Rows stamped with a different account (or with any account while signed
 * out) are foreign: read-only with an ownership indicator (§29 blocker #3b
 * resolution) — delete paths must skip them.
 */
object SyncAccounts {
    @Volatile
    var cachedAccount: String? = null

    /**
     * STAGE 1 — true once a sync pass has resolved the account identity from the
     * live access token (Drive `about?fields=user`).
     *
     * While false, [cachedAccount] is only a *provisional* value derived from the
     * persisted sign-in email, suitable for display before any pass has run. Once
     * a pass has verified the identity against the token, that value is
     * authoritative and a provisional refresh must not overwrite it — otherwise
     * the UI and repositories would evaluate ownership against a different key
     * than the one sync actually wrote, and the account's own rows would be
     * misclassified as foreign.
     */
    @Volatile
    var tokenVerified: Boolean = false
        private set

    private val _currentAccountHash = MutableStateFlow<String?>(null)
    val currentAccountHash: StateFlow<String?> = _currentAccountHash.asStateFlow()

    /**
     * Provisional refresh from the persisted sign-in email. Never downgrades an
     * identity that has already been verified against the access token.
     */
    fun refresh(context: Context) {
        if (tokenVerified) return
        apply(SyncAuthSession(context.applicationContext).accountEmail)
    }

    /**
     * STAGE 1 — publish the identity proven by the access token. This is the only
     * writer of [cachedAccount] that carries authority.
     */
    fun publishAuthenticated(authenticatedEmail: String?) {
        tokenVerified = true
        apply(authenticatedEmail)
    }

    /**
     * Drop the verified identity. Required on sign-out and when a *different*
     * account signs in, so a stale verified value can never outlive its token.
     */
    fun clearAuthentication() {
        tokenVerified = false
        apply(null)
    }

    private fun apply(email: String?) {
        cachedAccount = email
        _currentAccountHash.value = AccountHash.of(email)
    }

    /** True when [rowSyncAccount] belongs to a different (or past) account. */
    fun isForeign(rowSyncAccount: String?): Boolean =
        rowSyncAccount != null && rowSyncAccount != AccountHash.of(cachedAccount)

    /** True for unstamped local rows or rows owned by the active account. */
    fun belongsToCurrentAccount(rowSyncAccount: String?): Boolean =
        rowSyncAccount == null || rowSyncAccount == AccountHash.of(cachedAccount)
}
