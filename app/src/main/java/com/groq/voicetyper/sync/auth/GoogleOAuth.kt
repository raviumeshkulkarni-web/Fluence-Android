package com.groq.voicetyper.sync.auth

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException

/**
 * Drive token minting via Google Play Services (the proven Fluence-capture
 * pattern). No OAuth client secret, no refresh token, no browser flow:
 * Play Services issues and silently renews short-lived tokens for the
 * device account. One-time consent surfaces as [RecoveryRequired].
 */
object GoogleOAuth {

    /** appDataFolder requires the drive.appdata scope. */
    const val OAUTH_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.appdata"

    /** Play Services needs explicit user consent before minting tokens. */
    class RecoveryRequired(val intent: Intent?, message: String) : Exception(message)

    /**
     * Silent token mint/renew for [accountEmail]. Throws [RecoveryRequired]
     * when the user must approve the consent dialog once.
     */
    fun getDriveAccessToken(context: Context, accountEmail: String): String =
        try {
            GoogleAuthUtil.getToken(context, accountEmail, OAUTH_SCOPE)
        } catch (e: UserRecoverableAuthException) {
            throw RecoveryRequired(e.intent, e.message ?: "consent required")
        }

    /** Invalidate a rejected token so the next call mints a fresh one. */
    fun clearDriveToken(context: Context, token: String) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }
}
