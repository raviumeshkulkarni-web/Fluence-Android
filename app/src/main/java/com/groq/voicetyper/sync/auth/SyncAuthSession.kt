package com.groq.voicetyper.sync.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.groq.voicetyper.sync.v1.SyncError
import com.groq.voicetyper.sync.v1.TokenProvider
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient

/**
 * OAuth session for the sync worker (PKCE loopback).
 *
 * The access token lives in memory and nowhere else; the refresh token and the
 * account key (email) are persisted in encrypted prefs (Keystore-backed,
 * mirroring [com.groq.voicetyper.SecurityUtils]).
 *
 * Sign-in is completed via [completeSignIn], which stores the token response
 * from the PKCE loopback flow and persists the refresh token and account email.
 * [refreshAccessTokenIfNeeded] refreshes before each pass; a 400/401
 * from the token endpoint means the refresh token was revoked — the user must
 * sign in again (PassOutcomeKind.AuthRequired).
 */
class SyncAuthSession(context: Context) : TokenProvider {

    private val prefs: SharedPreferences = buildPrefs(context.applicationContext)
    private val client: OkHttpClient = GoogleOAuth.newHttpClient()

    // Memory-only access token.
    @Volatile private var accessToken: String? = null
    private val expiresAtMs = AtomicLong(0)

    // The refresh token is mirrored here and persisted below.
    @Volatile private var refreshToken: String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    /** The signed-in account key (email), or null when signed out. */
    @Volatile var accountEmail: String? = prefs.getString(KEY_ACCOUNT_EMAIL, null)
        private set

    /**
     * Finish sign-in from a PKCE loopback token response.
     * Throws [GoogleOAuth.AuthError] on failure.
     */
    fun completeSignIn(tokens: GoogleOAuth.TokenResponse, accountEmail: String) {
        storeTokens(tokens)
        if (refreshToken == null) throw GoogleOAuth.AuthError.NoRefreshToken
        persistRefreshToken()
        accountEmail.let { this.accountEmail = it; prefs.edit().putString(KEY_ACCOUNT_EMAIL, it).apply() }
    }

    /**
     * Ensure a valid access token, refreshing when needed. A revoked refresh
     * token (HTTP 400/401) surfaces as [SyncError.AuthRequired].
     */
    fun refreshAccessTokenIfNeeded() {
        if (hasValidAccessToken()) return
        val refresh = refreshToken ?: throw SyncError.AuthRequired
        val tokens = try {
            GoogleOAuth.refreshAccessToken(client, refresh)
        } catch (e: GoogleOAuth.AuthError.Http) {
            if (e.status == 400 || e.status == 401) throw SyncError.AuthRequired
            throw SyncError.Retryable("token refresh failed: ${e.message}")
        } catch (e: GoogleOAuth.AuthError.Network) {
            throw SyncError.Retryable("token refresh failed: ${e.message}")
        }
        storeTokens(tokens)
        // A rotated refresh token replaces the old one.
        if (refreshToken != refresh) persistRefreshToken()
    }

    /** The current access token, or null when absent/expired. */
    fun accessTokenOrNull(): String? = if (hasValidAccessToken()) accessToken else null

    fun hasValidAccessToken(): Boolean {
        if (accessToken == null) return false
        return expiresAtMs.get() == 0L || System.currentTimeMillis() < expiresAtMs.get()
    }

    /** Sign out: clear memory and encrypted storage. */
    fun signOut() {
        accessToken = null
        expiresAtMs.set(0L)
        refreshToken = null
        accountEmail = null
        prefs.edit()
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCOUNT_EMAIL)
            .apply()
    }

    /** Whether a sign-in state exists (refresh token present). */
    fun isSignedIn(): Boolean = refreshToken != null

    /**
     * Re-read persisted credentials.
     */
    fun reloadFromStorage() {
        refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
        accountEmail = prefs.getString(KEY_ACCOUNT_EMAIL, null)
    }

    override fun hasValidToken(): Boolean = hasValidAccessToken() || refreshToken != null

    private fun storeTokens(tokens: GoogleOAuth.TokenResponse) {
        accessToken = tokens.accessToken
        expiresAtMs.set(
            if (tokens.expiresInSecs > 0) {
                System.currentTimeMillis() + (tokens.expiresInSecs - 60) * 1000L
            } else {
                0L
            }
        )
        tokens.refreshToken?.let { refreshToken = it }
    }

    private fun persistRefreshToken() {
        val token = refreshToken ?: return
        prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }

    private fun buildPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private companion object {
        const val PREFS_NAME = "fluence_sync_secure_prefs"
        const val KEY_REFRESH_TOKEN = "sync_refresh_token"
        const val KEY_ACCOUNT_EMAIL = "sync_account_email"
    }
}
