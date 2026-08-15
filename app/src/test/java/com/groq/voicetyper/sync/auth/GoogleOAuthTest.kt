package com.groq.voicetyper.sync.auth

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GoogleOAuthTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ── GoogleSignInOptions construction ────────────────────────────────────

    @Test
    fun buildGoogleSignInOptions_configuresServerAuthCodeAndScopes() {
        val options = GoogleOAuth.buildGoogleSignInOptions("test-web-client-id")
        val scopes = options.scopes.map { it.scopeUri }
        assertTrue(scopes.contains(GoogleOAuth.DRIVE_FILE_SCOPE))
        assertEquals("test-web-client-id", options.serverClientId)
    }

    // ── parsing ─────────────────────────────────────────────────────────────

    @Test
    fun parseTokenResponse_extractsFields() {
        val tokens = GoogleOAuth.parseTokenResponse(
            """{"access_token":"at-1","refresh_token":"rt-1","expires_in":3599}"""
        )
        assertEquals("at-1", tokens.accessToken)
        assertEquals("rt-1", tokens.refreshToken)
        assertEquals(3599L, tokens.expiresInSecs)
    }

    @Test
    fun parseTokenResponse_allowsMissingRefreshToken() {
        val tokens = GoogleOAuth.parseTokenResponse("""{"access_token":"at-1"}""")
        assertEquals("at-1", tokens.accessToken)
        assertNull(tokens.refreshToken)
        assertEquals(3600L, tokens.expiresInSecs)
    }

    @Test
    fun parseTokenResponse_rejectsMalformed() {
        assertThrows(GoogleOAuth.AuthError.BadResponse::class.java) {
            GoogleOAuth.parseTokenResponse("not json")
        }
        assertThrows(GoogleOAuth.AuthError.BadResponse::class.java) {
            GoogleOAuth.parseTokenResponse("""{"error":"invalid_grant"}""")
        }
    }

    @Test
    fun parseAccountEmail_extractsAndTrims() {
        assertEquals("u@example.com", GoogleOAuth.parseAccountEmail("""{"email":" u@example.com "}"""))
        assertNull(GoogleOAuth.parseAccountEmail("""{"sub":"x"}"""))
        assertNull(GoogleOAuth.parseAccountEmail("garbage"))
    }

    // ── token exchange over HTTP ────────────────────────────────────────────

    @Test
    fun exchangeServerAuthCode_postsAuthCodeAndSecretAndParsesTokens() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"at-code","refresh_token":"rt-code","expires_in":3600}""")
        )
        val tokens = GoogleOAuth.exchangeServerAuthCode(
            client = GoogleOAuth.newHttpClient(),
            serverAuthCode = "auth-code-xyz",
            webClientId = "web-client-id",
            webClientSecret = "mock-secret-val",
            tokenEndpoint = server.url("/token").toString(),
        )
        assertEquals("at-code", tokens.accessToken)
        assertEquals("rt-code", tokens.refreshToken)

        val request = server.takeRequest()
        assertEquals("/token", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("grant_type=authorization_code"))
        assertTrue(body.contains("code=auth-code-xyz"))
        assertTrue(body.contains("client_id=web-client-id"))
        assertTrue(body.contains("client_secret=mock-secret-val"))
    }

    @Test
    fun exchangeServerAuthCode_throwsMissingClientSecret_whenSecretIsBlank() {
        val error = assertThrows(GoogleOAuth.AuthError.MissingClientSecret::class.java) {
            GoogleOAuth.exchangeServerAuthCode(
                client = GoogleOAuth.newHttpClient(),
                serverAuthCode = "auth-code-xyz",
                webClientId = "web-client-id",
                webClientSecret = "",
                tokenEndpoint = server.url("/token").toString(),
            )
        }
        assertTrue(error.message?.contains("set oauth.web.client.secret in local.properties") == true)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun exchangeServerAuthCode_surfacesHttpErrors() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
        val error = assertThrows(GoogleOAuth.AuthError.Http::class.java) {
            GoogleOAuth.exchangeServerAuthCode(
                client = GoogleOAuth.newHttpClient(),
                serverAuthCode = "bad-code",
                webClientId = "web-client-id",
                webClientSecret = "mock-secret-val",
                tokenEndpoint = server.url("/token").toString(),
            )
        }
        assertEquals(400, error.status)
    }

    @Test
    fun refreshAccessToken_postsRefreshTokenAndSecret() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"at2","expires_in":3600}""")
        )
        val tokens = GoogleOAuth.refreshAccessToken(
            client = GoogleOAuth.newHttpClient(),
            refreshToken = "rt-old",
            webClientId = "web-client-id",
            webClientSecret = "mock-secret-val",
            tokenEndpoint = server.url("/token").toString(),
        )
        assertEquals("at2", tokens.accessToken)
        assertNull(tokens.refreshToken)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=rt-old"))
        assertTrue(body.contains("client_id=web-client-id"))
        assertTrue(body.contains("client_secret=mock-secret-val"))
    }

    @Test
    fun refreshAccessToken_throwsMissingClientSecret_whenSecretIsBlank() {
        val error = assertThrows(GoogleOAuth.AuthError.MissingClientSecret::class.java) {
            GoogleOAuth.refreshAccessToken(
                client = GoogleOAuth.newHttpClient(),
                refreshToken = "rt-old",
                webClientId = "web-client-id",
                webClientSecret = "",
                tokenEndpoint = server.url("/token").toString(),
            )
        }
        assertTrue(error.message?.contains("set oauth.web.client.secret in local.properties") == true)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun fetchAccountEmail_sendsBearerAndParses() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"email":"me@example.com"}"""))
        val email = GoogleOAuth.fetchAccountEmail(
            client = GoogleOAuth.newHttpClient(),
            accessToken = "at-live",
            userinfoUrl = server.url("/userinfo").toString(),
        )
        assertEquals("me@example.com", email)
        assertEquals("Bearer at-live", server.takeRequest().getHeader("Authorization"))
        assertNotNull(email)
    }

    @Test
    fun fetchAccountEmail_failsOnHttpError() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(GoogleOAuth.AuthError.Http::class.java) {
            GoogleOAuth.fetchAccountEmail(
                client = GoogleOAuth.newHttpClient(),
                accessToken = "at-dead",
                userinfoUrl = server.url("/userinfo").toString(),
            )
        }
    }

    @Test
    fun fetchAccountEmail_rejectsMissingEmail() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"sub":"x"}"""))
        assertThrows(GoogleOAuth.AuthError.BadResponse::class.java) {
            GoogleOAuth.fetchAccountEmail(
                client = GoogleOAuth.newHttpClient(),
                accessToken = "at",
                userinfoUrl = server.url("/userinfo").toString(),
            )
        }
    }

    @Test
    fun networkFailureSurfacesAsNetworkError() {
        val url = server.url("/token").toString()
        server.shutdown() // connection refused on next call
        assertThrows(GoogleOAuth.AuthError.Network::class.java) {
            GoogleOAuth.exchangeServerAuthCode(
                client = GoogleOAuth.newHttpClient(),
                serverAuthCode = "code",
                webClientId = "web-id",
                webClientSecret = "mock-secret-val",
                tokenEndpoint = url,
            )
        }
    }
}