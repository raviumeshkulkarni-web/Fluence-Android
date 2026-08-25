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

    // ── PKCE ────────────────────────────────────────────────────────────────

    @Test
    fun pkce_verifier_shape_and_known_vector() {
        // Known vector RFC 7636 Appendix B
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = GoogleOAuth.pkceS256(verifier)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)

        // Verifier shape: 32 bytes base64UrlNoPad -> 43 chars, URL-safe alphabet
        val generated = GoogleOAuth.pkceVerifier()
        assertEquals(43, generated.length)
        assertTrue(generated.matches(Regex("^[A-Za-z0-9_\\-]{43}$")))
    }

    @Test
    fun authorization_url_contains_required_params() {
        val redirect = "http://127.0.0.1:12345"
        val state = "test-state-xyz"
        val challenge = "testChallenge123"
        val url = GoogleOAuth.buildAuthorizationUrl(redirect, state, challenge)
        // Must contain base auth endpoint
        assertTrue(url.startsWith(GoogleOAuth.AUTH_BASE_URL))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("client_id=${GoogleOAuth.DESKTOP_CLIENT_ID}"))
        // scope is url-encoded drive.appdata
        assertTrue(url.contains("scope="))
        // decoded scope should contain drive.appdata
        val scopeParam = url.substringAfter("scope=").substringBefore("&")
        assertTrue(java.net.URLDecoder.decode(scopeParam, "UTF-8").contains("drive.appdata"))
        assertTrue(url.contains("prompt=select_account"))
        assertTrue(url.contains("state=$state"))
        assertTrue(url.contains("code_challenge=$challenge"))
        assertTrue(url.contains("code_challenge_method=S256"))
        // redirect_uri is url-encoded
        val redirectParam = url.substringAfter("redirect_uri=").substringBefore("&")
        assertEquals(redirect, java.net.URLDecoder.decode(redirectParam, "UTF-8"))
    }

    @Test
    fun token_exchange_body_has_no_client_secret() {
        val exchangeForm = GoogleOAuth.exchangeCodeForm("mycode", "myverifier", "http://127.0.0.1:8080")
        // Must have exactly 5 keys, no client_secret
        assertEquals(5, exchangeForm.size)
        val exchangeKeys = (0 until exchangeForm.size).map { exchangeForm.name(it) }.toSet()
        assertEquals(setOf("grant_type", "client_id", "code", "code_verifier", "redirect_uri"), exchangeKeys)
        assertTrue((0 until exchangeForm.size).none { exchangeForm.name(it) == "client_secret" })
        // Verify values
        val exchangeMap = (0 until exchangeForm.size).associate { exchangeForm.name(it) to exchangeForm.value(it) }
        assertEquals("authorization_code", exchangeMap["grant_type"])
        assertEquals(GoogleOAuth.DESKTOP_CLIENT_ID, exchangeMap["client_id"])
        assertEquals("mycode", exchangeMap["code"])
        assertEquals("myverifier", exchangeMap["code_verifier"])
        assertEquals("http://127.0.0.1:8080", exchangeMap["redirect_uri"])

        val refreshForm = GoogleOAuth.refreshForm("rt-old")
        assertEquals(3, refreshForm.size)
        val refreshKeys = (0 until refreshForm.size).map { refreshForm.name(it) }.toSet()
        assertEquals(setOf("grant_type", "refresh_token", "client_id"), refreshKeys)
        assertTrue((0 until refreshForm.size).none { refreshForm.name(it) == "client_secret" })
        val refreshMap = (0 until refreshForm.size).associate { refreshForm.name(it) to refreshForm.value(it) }
        assertEquals("refresh_token", refreshMap["grant_type"])
        assertEquals("rt-old", refreshMap["refresh_token"])
        assertEquals(GoogleOAuth.DESKTOP_CLIENT_ID, refreshMap["client_id"])
    }

    @Test
    fun parse_about_email_extracts_address() {
        assertEquals("me@example.com", GoogleOAuth.parseAboutEmail("""{"user":{"emailAddress":"me@example.com"}}"""))
        assertEquals("me@example.com", GoogleOAuth.parseAboutEmail("""{"user":{"emailAddress":" me@example.com "}}"""))
        assertNull(GoogleOAuth.parseAboutEmail("""{"user":{}}"""))
        assertNull(GoogleOAuth.parseAboutEmail("""{"notuser":"x"}"""))
        assertNull(GoogleOAuth.parseAboutEmail("garbage"))
        // Also test parseAccountEmail backward compat
        assertEquals("u@example.com", GoogleOAuth.parseAccountEmail("""{"email":" u@example.com "}"""))
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

    // ── token exchange over HTTP via PKCE (no secret) ────────────────────────

    @Test
    fun refreshAccessToken_postsRefreshTokenWithoutSecret() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"access_token":"at2","expires_in":3600}""")
        )
        val tokens = GoogleOAuth.refreshAccessToken(
            client = GoogleOAuth.newHttpClient(),
            refreshToken = "rt-old",
            tokenEndpoint = server.url("/token").toString(),
        )
        assertEquals("at2", tokens.accessToken)
        assertNull(tokens.refreshToken)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("grant_type=refresh_token"))
        assertTrue(body.contains("refresh_token=rt-old"))
        assertTrue(body.contains("client_id=${GoogleOAuth.DESKTOP_CLIENT_ID}"))
        assertTrue(!body.contains("client_secret"))
    }

    @Test
    fun fetchAccountEmail_sendsBearerAndParsesAbout() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"user":{"emailAddress":"me@example.com"}}"""))
        val email = GoogleOAuth.fetchAccountEmail(
            client = GoogleOAuth.newHttpClient(),
            accessToken = "at-live",
            aboutUrl = server.url("/about").toString(),
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
                aboutUrl = server.url("/about").toString(),
            )
        }
    }

    @Test
    fun fetchAccountEmail_rejectsMissingEmail() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"user":{}}"""))
        assertThrows(GoogleOAuth.AuthError.BadResponse::class.java) {
            GoogleOAuth.fetchAccountEmail(
                client = GoogleOAuth.newHttpClient(),
                accessToken = "at",
                aboutUrl = server.url("/about").toString(),
            )
        }
    }

    @Test
    fun networkFailureSurfacesAsNetworkError() {
        val url = server.url("/token").toString()
        server.shutdown() // connection refused on next call
        assertThrows(GoogleOAuth.AuthError.Network::class.java) {
            GoogleOAuth.refreshAccessToken(
                client = GoogleOAuth.newHttpClient(),
                refreshToken = "rt-old",
                tokenEndpoint = url,
            )
        }
    }
}
