package com.groq.voicetyper.sync.auth

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.groq.voicetyper.BuildConfig
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Android OAuth 2.0 implementation using native Google Sign-In with offline
 * server auth code exchange (spec §24 deviation note).
 *
 * Uses [GoogleSignInOptions] with `requestServerAuthCode(webClientId, forceCodeForRefreshToken = true)`
 * and `requestScopes(drive.file)` to prompt the native Android account-chooser bottom sheet.
 * The resulting server auth code is exchanged at the Google token endpoint using the Web client ID
 * and Web client secret (configured from local.properties via [BuildConfig]).
 *
 * The access token is memory-only; the refresh token and account email live in encrypted prefs
 * ([SyncAuthSession]). Client secrets are never logged or committed.
 */
object GoogleOAuth {

    const val DRIVE_FILE_SCOPE: String = "https://www.googleapis.com/auth/drive.file"
    const val TOKEN_ENDPOINT: String = "https://oauth2.googleapis.com/token"
    const val USERINFO_URL: String = "https://www.googleapis.com/oauth2/v3/userinfo"

    const val CONNECT_TIMEOUT_SECS: Long = 8
    const val READ_TIMEOUT_SECS: Long = 30

    fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECS, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECS, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun buildGoogleSignInOptions(
        webClientId: String = BuildConfig.OAUTH_WEB_CLIENT_ID
    ): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestServerAuthCode(webClientId, /* forceCodeForRefreshToken = */ true)
            .requestScopes(Scope(DRIVE_FILE_SCOPE))
            .requestEmail()
            .build()

    fun getGoogleSignInClient(
        context: Context,
        webClientId: String = BuildConfig.OAUTH_WEB_CLIENT_ID
    ): GoogleSignInClient =
        GoogleSignIn.getClient(context, buildGoogleSignInOptions(webClientId))

    /** The outcome of a successful token exchange. */
    data class TokenResponse(
        val accessToken: String,
        /** `null` when the endpoint does not rotate the refresh token. */
        val refreshToken: String?,
        val expiresInSecs: Long,
    )

    sealed class AuthError(message: String) : Exception(message) {
        class MissingClientSecret(message: String = "set oauth.web.client.secret in local.properties") : AuthError(message)
        class Network(message: String) : AuthError(message)
        class Http(val status: Int, message: String) : AuthError(message)
        object BadResponse : AuthError("malformed token response")
        object NoRefreshToken : AuthError("no refresh token — sign in again")
        class AccessDenied(message: String) : AuthError(message)
    }

    /** Parse the provider's token JSON: access_token, optional refresh_token, expires_in. */
    fun parseTokenResponse(json: String): TokenResponse {
        val value = runCatching { JSONObject(json) }.getOrElse { throw AuthError.BadResponse }
        val access = value.optString("access_token", "").ifEmpty { throw AuthError.BadResponse }
        val refresh = if (value.has("refresh_token") && !value.isNull("refresh_token")) {
            value.optString("refresh_token").ifEmpty { null }
        } else {
            null
        }
        val expires = value.optLong("expires_in", 3600L)
        return TokenResponse(accessToken = access, refreshToken = refresh, expiresInSecs = expires)
    }

    /** Extract the account key (email) from the Google userinfo response. */
    fun parseAccountEmail(json: String): String? {
        val value = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val email = value.optString("email", "").trim()
        return email.ifEmpty { null }
    }

    /** Exchange the server auth code for access + refresh tokens. */
    fun exchangeServerAuthCode(
        client: OkHttpClient,
        serverAuthCode: String,
        webClientId: String = BuildConfig.OAUTH_WEB_CLIENT_ID,
        webClientSecret: String = BuildConfig.OAUTH_WEB_CLIENT_SECRET,
        tokenEndpoint: String = TOKEN_ENDPOINT,
    ): TokenResponse {
        if (webClientSecret.isBlank()) {
            throw AuthError.MissingClientSecret("set oauth.web.client.secret in local.properties")
        }
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", webClientId)
            .add("client_secret", webClientSecret)
            .add("code", serverAuthCode)
            .build()
        return postTokenRequest(client, body, tokenEndpoint)
    }

    /** Refresh the access token with the stored refresh token. */
    fun refreshAccessToken(
        client: OkHttpClient,
        refreshToken: String,
        webClientId: String = BuildConfig.OAUTH_WEB_CLIENT_ID,
        webClientSecret: String = BuildConfig.OAUTH_WEB_CLIENT_SECRET,
        tokenEndpoint: String = TOKEN_ENDPOINT,
    ): TokenResponse {
        if (webClientSecret.isBlank()) {
            throw AuthError.MissingClientSecret("set oauth.web.client.secret in local.properties")
        }
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", webClientId)
            .add("client_secret", webClientSecret)
            .build()
        return postTokenRequest(client, body, tokenEndpoint)
    }

    private fun postTokenRequest(client: OkHttpClient, body: FormBody, tokenEndpoint: String): TokenResponse {
        val request = Request.Builder()
            .url(tokenEndpoint)
            .post(body)
            .build()
        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { throw AuthError.Network(it.message ?: "token request failed") }
        response.use {
            val text = runCatching { it.body?.string().orEmpty() }
                .getOrElse { throw AuthError.Network("token response read failed") }
            if (!it.isSuccessful) {
                throw AuthError.Http(it.code, text)
            }
            return parseTokenResponse(text)
        }
    }

    /** Fetch the account email with a memory-only access token. Never logs token material. */
    fun fetchAccountEmail(
        client: OkHttpClient,
        accessToken: String,
        userinfoUrl: String = USERINFO_URL,
    ): String {
        val request = Request.Builder()
            .url(userinfoUrl)
            .header("Authorization", "Bearer $accessToken")
            .build()
        val response = runCatching { client.newCall(request).execute() }
            .getOrElse { throw AuthError.Network(it.message ?: "account lookup failed") }
        response.use {
            val text = runCatching { it.body?.string().orEmpty() }
                .getOrElse { throw AuthError.Network("account lookup failed") }
            if (!it.isSuccessful) {
                throw AuthError.Http(it.code, "account lookup failed (HTTP ${it.code})")
            }
            return parseAccountEmail(text) ?: throw AuthError.BadResponse
        }
    }
}