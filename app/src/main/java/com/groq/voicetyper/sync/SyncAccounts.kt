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

    private val _currentAccountHash = MutableStateFlow<String?>(null)
    val currentAccountHash: StateFlow<String?> = _currentAccountHash.asStateFlow()

    fun refresh(context: Context) {
        cachedAccount = SyncAuthSession(context.applicationContext).accountEmail
        _currentAccountHash.value = AccountHash.of(cachedAccount)
    }

    /** True when [rowSyncAccount] belongs to a different (or past) account. */
    fun isForeign(rowSyncAccount: String?): Boolean =
        rowSyncAccount != null && rowSyncAccount != AccountHash.of(cachedAccount)

    /** True for unstamped local rows or rows owned by the active account. */
    fun belongsToCurrentAccount(rowSyncAccount: String?): Boolean =
        rowSyncAccount == null || rowSyncAccount == AccountHash.of(cachedAccount)
}
