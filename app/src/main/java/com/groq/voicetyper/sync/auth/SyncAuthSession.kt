package com.groq.voicetyper.sync.auth

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.groq.voicetyper.sync.v1.SyncError
import com.groq.voicetyper.sync.v1.TokenProvider
import java.util.concurrent.atomic.AtomicLong

/**
 * OAuth session for the sync worker (Play Services pattern, mirroring the
 * proven Fluence-capture implementation).
 *
 * The only persisted state is the account email (encrypted prefs). Drive
 * access tokens are minted per pass by Play Services via
 * [GoogleOAuth.getDriveAccessToken] and live in memory only. There is no
 * refresh token and no client secret anywhere.
 */
class SyncAuthSession(
    private val context: Context,
    private val prefsProvider: (Context) -> SharedPreferences = ::buildDefaultPrefs
) : TokenProvider {

    private var prefs: SharedPreferences = prefsProvider(context)

    /**
     * True when the last [open] degraded to the in-memory fallback because the
     * Android keystore key could not be built/decrypted. In that state the
     * signed-in state is unreliable (starts signed-out) and should be
     * presented truthfully instead of as a Drive authorization failure.
     */
    @Volatile var storageDegraded: Boolean = com.groq.voicetyper.SecurePrefsStore.isDegraded(prefs)
        private set

    // Memory-only access token.
    @Volatile private var accessToken: String? = null
    private val expiresAtMs = AtomicLong(0)

    /**
     * Pending Google consent intent. When [GoogleAuthUtil] needs explicit user
     * consent, the intent is stashed here so the UI can launch it. After
     * successful token mint or explicit sign-out, this is cleared.
     */
    @Volatile var recoveryIntent: android.content.Intent? = null
        private set

    /** The signed-in account key (email), or null when signed out. */
    @Volatile var accountEmail: String? = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        private set

    /** Finish sign-in: persist the account email chosen via the account picker. */
    fun completeSignIn(accountEmail: String) {
        this.accountEmail = accountEmail
        prefs.edit().putString(KEY_ACCOUNT_EMAIL, accountEmail).apply()
        accessToken = null
        expiresAtMs.set(0L)
    }

    /**
     * Mint/renew the Drive access token via Play Services. A missing account
     * or pending consent surfaces as [SyncError.AuthRequired]; transient
     * Play Services failures are [SyncError.Retryable].
     * Prefers the stable Android Account object (Capture pattern) with email fallback.
     */
    fun refreshAccessTokenIfNeeded() {
        if (hasValidAccessToken()) return
        val email = accountEmail ?: throw SyncError.AuthRequired
        // Prefer Account object (survives email renames); fall back to email string.
        val androidAccount = runCatching { GoogleSignIn.getLastSignedInAccount(context)?.account }.getOrNull()
        accessToken = try {
            val token = if (androidAccount != null) {
                GoogleOAuth.getDriveAccessToken(context, androidAccount)
            } else {
                GoogleOAuth.getDriveAccessToken(context, email)
            }
            recoveryIntent = null // consent granted — clear pending recovery
            token
        } catch (e: GoogleOAuth.RecoveryRequired) {
            recoveryIntent = e.intent // surface to UI for consent dialog
            throw SyncError.AuthRequired
        } catch (e: com.google.android.gms.auth.GoogleAuthException) {
            throw SyncError.Retryable("token: ${e.message}")
        } catch (e: java.io.IOException) {
            throw SyncError.Retryable("token: ${e.message}")
        }
        expiresAtMs.set(System.currentTimeMillis() + 55 * 60_000L)
    }

    /** The current access token, or null when absent/expired. */
    fun accessTokenOrNull(): String? = if (hasValidAccessToken()) accessToken else null

    /**
     * Discard a Drive-rejected access token so the next
     * [refreshAccessTokenIfNeeded] mints a fresh one instead of handing back
     * the same cached token Play Services still believes is valid.
     * Also clears the Play Services token cache (mirrors Capture's clearToken on 401).
     */
    fun invalidateAccessToken() {
        accessToken?.let { stale -> runCatching { GoogleOAuth.clearDriveToken(context, stale) } }
        accessToken = null
        expiresAtMs.set(0L)
    }

    fun hasValidAccessToken(): Boolean {
        if (accessToken == null) return false
        return expiresAtMs.get() == 0L || System.currentTimeMillis() < expiresAtMs.get()
    }

    /** Sign out: clear memory, encrypted storage, and the Play Services account selection. */
    fun signOut() {
        accessToken?.let { stale -> runCatching { GoogleOAuth.clearDriveToken(context, stale) } }
        accessToken = null
        expiresAtMs.set(0L)
        accountEmail = null
        recoveryIntent = null
        prefs.edit().remove(KEY_ACCOUNT_EMAIL).apply()
        // Best-effort: also clear the native account selection. Play Services
        // classes are unavailable in JVM unit tests — ignore failures.
        runCatching {
            GoogleSignIn.getClient(
                context,
                GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().build()
            ).signOut()
        }
    }

    /** Whether a sign-in state exists (account email present). */
    fun isSignedIn(): Boolean = accountEmail != null

    /**
     * Re-read persisted state. The encrypted store is re-opened every time so
     * a transient keystore failure (degraded in-memory fallback) is not held
     * for the rest of the process: once the keystore recovers, the real
     * prefs — including a previously committed account email — are read again.
     */
    fun reloadFromStorage() {
        prefs = prefsProvider(context)
        storageDegraded = com.groq.voicetyper.SecurePrefsStore.isDegraded(prefs)
        accountEmail = prefs.getString(KEY_ACCOUNT_EMAIL, null)
    }

    override fun hasValidToken(): Boolean = hasValidAccessToken() || isSignedIn()

    private fun buildPrefs(context: Context): SharedPreferences = buildDefaultPrefs(context)

    internal companion object {
        const val PREFS_NAME = "fluence_sync_secure_prefs"
        const val KEY_ACCOUNT_EMAIL = "sync_account_email"

        fun buildDefaultPrefs(context: Context): SharedPreferences =
            com.groq.voicetyper.SecurePrefsStore.open(context, PREFS_NAME)
        }
}
