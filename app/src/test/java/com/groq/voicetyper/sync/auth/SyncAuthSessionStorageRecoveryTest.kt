package com.groq.voicetyper.sync.auth

import android.content.Context
import android.content.SharedPreferences
import com.groq.voicetyper.SecurePrefsStore.DegradedPrefs
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A transient keystore failure degrades to a non-persistent store. Reloading
 * must re-open the store so a recovered keystore restores the committed
 * account email instead of keeping the process "signed out" (the false
 * "Session expired" loop); and the storage state must be surfaced truthfully
 * so the UI doesn't blame Drive authorization.
 */
class SyncAuthSessionStorageRecoveryTest {

    private val email = "user@example.com"

    private fun healthyPrefsWith(email: String?): SharedPreferences {
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { edit() } returns mockk(relaxed = true)
            every { getString(eq(SyncAuthSession.KEY_ACCOUNT_EMAIL), any()) } returns email
        }
        return prefs
    }

    private fun degradedPrefs(): SharedPreferences =
        mockk<SharedPreferences>(relaxed = true, moreInterfaces = arrayOf(DegradedPrefs::class)) {
            every { edit() } returns mockk(relaxed = true)
            every { getString(eq(SyncAuthSession.KEY_ACCOUNT_EMAIL), any()) } returns null
        }

    @Test
    fun reloadFromStorage_reopensStore_andRecoversAccountEmail() {
        val degraded = degradedPrefs()
        val healthy = healthyPrefsWith(email)
        var call = 0
        val session = SyncAuthSession(mockk<Context>(relaxed = true)) {
            if (call++ == 0) degraded else healthy
        }

        // Initial open degraded: signed out, storage flagged as unavailable.
        assertTrue(session.storageDegraded)
        assertFalse(session.isSignedIn())
        assertFalse(session.hasValidToken())

        // Keystore recovered: the next reload re-opens and finds the email.
        session.reloadFromStorage()
        assertFalse(session.storageDegraded)
        assertTrue(session.isSignedIn())
        assertEquals(email, session.accountEmail)
    }

    @Test
    fun storageDegraded_isFalseForHealthyStore() {
        val session = SyncAuthSession(mockk<Context>(relaxed = true)) {
            healthyPrefsWith(email)
        }
        assertFalse(session.storageDegraded)
        assertTrue(session.isSignedIn())
    }

    @Test
    fun reloadFromStorage_afterSignOut_staysDegradedUntilStoreRecovers() {
        val degraded = degradedPrefs()
        val healthy = healthyPrefsWith(null)
        var call = 0
        val session = SyncAuthSession(mockk<Context>(relaxed = true)) {
            if (call++ == 0) degraded else healthy
        }
        session.reloadFromStorage()
        assertFalse(session.storageDegraded)
        assertNullEmail(session)
    }

    private fun assertNullEmail(session: SyncAuthSession) {
        assertEquals(null, session.accountEmail)
        assertFalse(session.isSignedIn())
    }
}