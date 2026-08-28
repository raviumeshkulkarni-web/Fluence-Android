package com.groq.voicetyper.sync.auth

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Play-Services token flow (Fluence-capture pattern): no client secret, no
 * refresh token, no browser. GoogleAuthUtil itself is device-bound and cannot
 * run on the JVM — these tests cover the contract surface that can.
 */
class GoogleOAuthTest {

    @Test
    fun scope_is_drive_appdata_with_oauth2_prefix() {
        assertEquals(
            "oauth2:https://www.googleapis.com/auth/drive.appdata",
            GoogleOAuth.OAUTH_SCOPE
        )
    }

    @Test
    fun scope_matches_appdata_folder_requirement() {
        assertTrue(GoogleOAuth.OAUTH_SCOPE.endsWith("/drive.appdata"))
        assertTrue(GoogleOAuth.OAUTH_SCOPE.startsWith("oauth2:"))
    }

    @Test
    fun recovery_required_carries_intent_and_message() {
        val intent = Intent("com.test.RECOVER")
        val e = GoogleOAuth.RecoveryRequired(intent, "consent required")
        assertNotNull(e.intent)
        assertEquals("consent required", e.message)
        assertTrue(e is Exception)
    }

    @Test
    fun recovery_required_tolerates_null_intent() {
        val e = GoogleOAuth.RecoveryRequired(null, "no intent available")
        assertEquals(null, e.intent)
    }
}
