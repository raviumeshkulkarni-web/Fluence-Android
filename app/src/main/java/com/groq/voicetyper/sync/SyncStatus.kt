package com.groq.voicetyper.sync

/**
 * The sync feature's user-visible state (mirror of Windows `SyncStatus`).
 * Exposed as a [kotlinx.coroutines.flow.StateFlow] by [SyncManager] and shown
 * by the SettingsScreen sync section.
 */
data class SyncStatus(
    val signedIn: Boolean = false,
    val account: String? = null,
    val syncEnabled: Boolean = false,
    val running: Boolean = false,
    val lastSyncAtMs: Long? = null,
    val lastError: String? = null,
    /**
     * F4 Success-with-warning (mirror of Windows `SyncStatus.degraded`): the
     * latest pass converged with skipped remote state. Render as a warning,
     * never as an error and never as "not synced" — lastSyncAtMs advanced.
     */
    val degraded: Boolean = false,
    /** A Google consent dialog is needed before sync can proceed. */
    val recoveryPending: Boolean = false,
    /** Secure storage degraded: signed-in state is unknown, not a Drive auth failure. */
    val secureStorageUnavailable: Boolean = false,
)