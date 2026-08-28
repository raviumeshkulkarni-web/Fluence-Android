package com.groq.voicetyper.sync.auth

import android.content.Context
import android.content.SharedPreferences
import com.groq.voicetyper.sync.v1.SyncError
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Play-Services session semantics: only the account email persists; access
 * tokens live in memory and are minted per pass. GoogleAuthUtil itself is
 * device-bound — token-mint paths are exercised on-device.
 */
class SyncAuthSessionTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var session: SyncAuthSession

    private val stored = mutableMapOf<String, String?>()

    @Before
    fun setUp() {
        stored.clear()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } answers { stored[arg(0)] = arg(1); editor }
        every { editor.remove(any()) } answers { stored.remove(arg(0)); editor }
        prefs = mockk(relaxed = true)
        every { prefs.edit() } returns editor
        every { prefs.getString(eq(SyncAuthSession.KEY_ACCOUNT_EMAIL), any()) } answers { stored[SyncAuthSession.KEY_ACCOUNT_EMAIL] }
        session = SyncAuthSession(mockk<Context>(relaxed = true)) { prefs }
        session.signOut()
        session.reloadFromStorage()
    }

    @Test
    fun completeSignIn_persistsEmail_andIsSignedIn() {
        session.completeSignIn("user@example.com")
        assertEquals("user@example.com", session.accountEmail)
        assertTrue(session.isSignedIn())
        assertEquals("user@example.com", stored[SyncAuthSession.KEY_ACCOUNT_EMAIL])

        session.reloadFromStorage()
        assertEquals("user@example.com", session.accountEmail)
        assertTrue(session.isSignedIn())
    }

    @Test
    fun signOut_clearsEmailAndMemory() {
        session.completeSignIn("user@example.com")
        session.signOut()
        assertNull(session.accountEmail)
        assertFalse(session.isSignedIn())
        assertNull(session.accessTokenOrNull())
        assertFalse(session.hasValidToken())

        session.reloadFromStorage()
        assertNull(session.accountEmail)
    }

    @Test
    fun refreshAccessTokenIfNeeded_withoutAccount_throwsAuthRequired() {
        try {
            session.refreshAccessTokenIfNeeded()
            throw AssertionError("expected AuthRequired")
        } catch (e: SyncError.AuthRequired) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun hasValidToken_true_whenSignedInEvenWithoutMintedToken() {
        assertFalse(session.hasValidToken())
        session.completeSignIn("user@example.com")
        assertTrue(session.hasValidToken())
        // Not yet minted, so no usable access token:
        assertNull(session.accessTokenOrNull())
        assertFalse(session.hasValidAccessToken())
    }
}
