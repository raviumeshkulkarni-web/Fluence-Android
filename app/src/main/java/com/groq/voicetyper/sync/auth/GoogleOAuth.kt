package com.groq.voicetyper.sync.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Android OAuth 2.0 implementation using loopback PKCE (RFC 7636 S256).
 * No secret is stored or transmitted. The desktop client ID is public
 * (mirrors Windows flow) and the verifier is generated per sign-in.
 *
 * Uses Drive appDataFolder scope via PKCE S256 code challenge.
 */
object GoogleOAuth {

    const val DESKTOP_CLIENT_ID: String = "236666538373-005rdohmcf6cgh0in10v5v8nhcc1m85k.apps.googleusercontent.com"
    const val DRIVE_APPDATA_SCOPE: String = "https://www.googleapis.com/auth/drive.appdata"
    const val TOKEN_ENDPOINT: String = "https://oauth2.googleapis.com/token"
    const val ABOUT_URL: String = "https://www.googleapis.com/drive/v3/about?fields=user"
    const val AUTH_BASE_URL: String = "https://accounts.google.com/o/oauth2/v2/auth"

    const val CONNECT_TIMEOUT_SECS: Long = 8
    const val READ_TIMEOUT_SECS: Long = 30

    fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECS, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECS, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /** The outcome of a successful token exchange. */
    data class TokenResponse(
        val accessToken: String,
        /** `null` when the endpoint does not rotate the refresh token. */
        val refreshToken: String?,
        val expiresInSecs: Long,
    )

    sealed class AuthError(message: String) : Exception(message) {
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

    /** Extract the account key (email) from the Drive about response. */
    fun parseAboutEmail(json: String): String? {
        val value = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val user = value.optJSONObject("user") ?: return null
        val email = user.optString("emailAddress", "").trim()
        return email.ifEmpty { null }
    }

    /** Legacy helper kept for compatibility: extract email from userinfo style JSON. */
    fun parseAccountEmail(json: String): String? {
        val value = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val email = value.optString("email", "").trim()
        if (email.isNotEmpty()) return email
        // Fallback to about-style if present
        return parseAboutEmail(json)
    }

    // PKCE (RFC 7636 S256) — same as Windows auth.rs
    fun pkceVerifier(): String {
        val rnd = SecureRandom()
        val bytes = ByteArray(32)
        rnd.nextBytes(bytes)
        return base64UrlNoPad(bytes)
    }

    fun pkceS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64UrlNoPad(hash)
    }

    private fun base64UrlNoPad(b: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    fun buildAuthorizationUrl(redirectUri: String, state: String, challenge: String): String {
        val scopeEncoded = URLEncoder.encode(DRIVE_APPDATA_SCOPE, "UTF-8")
        val redirectEncoded = URLEncoder.encode(redirectUri, "UTF-8")
        return "$AUTH_BASE_URL?response_type=code&client_id=$DESKTOP_CLIENT_ID&scope=$scopeEncoded&redirect_uri=$redirectEncoded&prompt=select_account&state=$state&code_challenge=$challenge&code_challenge_method=S256"
    }

    internal fun exchangeCodeForm(code: String, verifier: String, redirectUri: String): FormBody =
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", DESKTOP_CLIENT_ID)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("redirect_uri", redirectUri)
            .build()

    internal fun refreshForm(refreshToken: String): FormBody =
        FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", DESKTOP_CLIENT_ID)
            .build()

    suspend fun signInWithLoopback(context: Context): TokenResponse = withContext(Dispatchers.IO) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket()
            serverSocket.reuseAddress = true
            serverSocket.bind(InetSocketAddress("127.0.0.1", 0))
            val redirectUri = "http://127.0.0.1:${serverSocket.localPort}"
            val state = UUID.randomUUID().toString()
            val verifier = pkceVerifier()
            val challenge = pkceS256(verifier)
            val authUrl = buildAuthorizationUrl(redirectUri, state, challenge)
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            val (code, returnedState) = withTimeout(300_000L) {
                serverSocket.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
                    val requestLine = reader.readLine() ?: throw AuthError.BadResponse
                    // Consume headers until blank line
                    var line: String?
                    while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                        // skip
                    }
                    val path = requestLine.split(" ").getOrNull(1) ?: throw AuthError.BadResponse
                    val query = path.substringAfter("?", "")
                    val params = query.split("&").mapNotNull {
                        if (it.isEmpty()) return@mapNotNull null
                        val kv = it.split("=", limit = 2)
                        if (kv.size == 2) {
                            val k = URLDecoder.decode(kv[0], "UTF-8")
                            val v = URLDecoder.decode(kv[1], "UTF-8")
                            k to v
                        } else null
                    }.toMap()
                    val c = params["code"]
                    val s = params["state"]
                    val body = "Sign-in complete \u2014 you can close this window."
                    val bodyBytes = body.toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n$body"
                    writer.write(response)
                    writer.flush()
                    Pair(c, s)
                }
            }
            if (code == null) throw AuthError.BadResponse
            if (returnedState != state) throw AuthError.Http(400, "state mismatch")
            val client = newHttpClient()
            val form = exchangeCodeForm(code, verifier, redirectUri)
            return@withContext postTokenRequest(client, form, TOKEN_ENDPOINT)
        } finally {
            runCatching { serverSocket?.close() }
        }
    }

    /** Refresh the access token with the stored refresh token. */
    fun refreshAccessToken(
        client: OkHttpClient,
        refreshToken: String,
        tokenEndpoint: String = TOKEN_ENDPOINT,
    ): TokenResponse {
        val form = refreshForm(refreshToken)
        return postTokenRequest(client, form, tokenEndpoint)
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
        aboutUrl: String = ABOUT_URL,
    ): String {
        val request = Request.Builder()
            .url(aboutUrl)
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
            return parseAboutEmail(text) ?: throw AuthError.BadResponse
        }
    }
}
