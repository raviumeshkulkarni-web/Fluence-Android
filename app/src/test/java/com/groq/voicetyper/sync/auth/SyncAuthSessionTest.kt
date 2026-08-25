package com.groq.voicetyper.sync.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.groq.voicetyper.sync.v1.SyncError
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SyncAuthSessionTest {

    private lateinit var server: MockWebServer
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private val prefsMap = mutableMapOf<String, String?>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        mockContext = mockk(relaxed = true)
        mockPrefs = mockk(relaxed = true)
        mockEditor = mockk(relaxed = true)

        every { mockContext.applicationContext } returns mockContext

        every { mockPrefs.getString(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<String?>()
            prefsMap.getOrDefault(key, default)
        }
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } answers {
            prefsMap[firstArg<String>()] = secondArg<String>()
            mockEditor
        }
        every { mockEditor.remove(any()) } answers {
            prefsMap.remove(firstArg<String>())
            mockEditor
        }
        every { mockEditor.apply() } returns Unit

        mockkStatic(EncryptedSharedPreferences::class)
        every {
            EncryptedSharedPreferences.create(
                any<Context>(),
                any<String>(),
                any<MasterKey>(),
                any<EncryptedSharedPreferences.PrefKeyEncryptionScheme>(),
                any<EncryptedSharedPreferences.PrefValueEncryptionScheme>()
            )
        } returns mockPrefs

        mockkConstructor(MasterKey.Builder::class)
        every { anyConstructed<MasterKey.Builder>().setKeyScheme(any()) } answers { self as MasterKey.Builder }
        every { anyConstructed<MasterKey.Builder>().build() } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
        unmockkAll()
        prefsMap.clear()
    }

    @Test
    fun completeSignIn_success_persistsTokensAndEmail() {
        val session = SyncAuthSession(mockContext)
        val tokens = GoogleOAuth.TokenResponse(
            accessToken = "test-access-token",
            refreshToken = "test-refresh-token",
            expiresInSecs = 3600,
        )
        session.completeSignIn(tokens, "user@example.com")

        assertEquals("user@example.com", session.accountEmail)
        assertTrue(session.isSignedIn())
        assertEquals("test-refresh-token", prefsMap["sync_refresh_token"])
        assertEquals("user@example.com", prefsMap["sync_account_email"])
    }

    @Test
    fun completeSignIn_noRefreshToken_throws() {
        val session = SyncAuthSession(mockContext)
        val tokens = GoogleOAuth.TokenResponse(
            accessToken = "at-only",
            refreshToken = null,
            expiresInSecs = 3600,
        )
        val error = assertThrows(GoogleOAuth.AuthError.NoRefreshToken::class.java) {
            session.completeSignIn(tokens, "user@example.com")
        }
        assertTrue(error.message?.contains("no refresh token") == true || error is GoogleOAuth.AuthError.NoRefreshToken)
        assertFalse(session.isSignedIn())
    }

    @Test
    fun signOut_clearsMemoryAndPrefs() {
        prefsMap["sync_refresh_token"] = "rt-to-clear"
        prefsMap["sync_account_email"] = "clear@example.com"

        val session = SyncAuthSession(mockContext)
        assertTrue(session.isSignedIn())
        assertEquals("clear@example.com", session.accountEmail)

        session.signOut()
        assertFalse(session.isSignedIn())
        assertNull(session.accountEmail)
        assertNull(session.accessTokenOrNull())
        assertNull(prefsMap["sync_refresh_token"])
        assertNull(prefsMap["sync_account_email"])
    }

    @Test
    fun refreshAccessTokenIfNeeded_revoked401_throwsAuthRequired() {
        prefsMap["sync_refresh_token"] = "revoked-rt"
        val session = SyncAuthSession(mockContext)

        mockkObject(GoogleOAuth)
        every {
            GoogleOAuth.refreshAccessToken(any(), "revoked-rt", any())
        } throws GoogleOAuth.AuthError.Http(401, "revoked")

        assertThrows(SyncError.AuthRequired::class.java) {
            session.refreshAccessTokenIfNeeded()
        }
    }

    @Test
    fun refreshAccessTokenIfNeeded_network_throwsRetryable() {
        prefsMap["sync_refresh_token"] = "rt-net"
        val session = SyncAuthSession(mockContext)

        mockkObject(GoogleOAuth)
        every {
            GoogleOAuth.refreshAccessToken(any(), "rt-net", any())
        } throws GoogleOAuth.AuthError.Network("offline")

        assertThrows(SyncError.Retryable::class.java) {
            session.refreshAccessTokenIfNeeded()
        }
    }
}
