package com.groq.voicetyper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log

private const val TAG = "TranscriptRescue"

/**
 * Key for ClipDescription extras to indicate sensitive content.
 * Constant introduced in Android 13 (Tiramisu) as ClipDescription.EXTRA_IS_SENSITIVE,
 * spelled out here so it functions consistently across all API levels without class-loading errors.
 */
internal const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

internal var sdkIntOverride: Int? = null

/**
 * Last-resort delivery when the dictation target is gone: keep the completed
 * transcript on the clipboard so accepting without a focused input never
 * silently drops it.
 *
 * Attaches EXTRA_IS_SENSITIVE via ClipDescription extras on API 24+ (Android 7.0+ /
 * Build.VERSION_CODES.N), which Android 13+ (API 33+) honors to suppress on-screen
 * clipboard overlay popups and inform password managers not to retain or expose
 * the rescued transcript.
 */
internal fun rescueTranscriptToClipboard(context: Context, text: String): Boolean {
    val rescued = text.trim()
    if (rescued.isEmpty()) return false
    return try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        val clip = ClipData.newPlainText("Fluence transcript", rescued)
        val sdk = sdkIntOverride ?: Build.VERSION.SDK_INT
        if (sdk >= Build.VERSION_CODES.N) {
            try {
                val extras = clip.description.extras ?: PersistableBundle()
                extras.putBoolean(EXTRA_IS_SENSITIVE, true)
                clip.description.extras = extras
            } catch (e: Exception) {
                try { Log.w(TAG, "Failed to attach EXTRA_IS_SENSITIVE to clip description", e) } catch (_: Throwable) {}
            }
        }
        clipboard.setPrimaryClip(clip)
        true
    } catch (e: Exception) {
        try { Log.e(TAG, "Failed to rescue transcript to clipboard", e) } catch (_: Throwable) {}
        false
    }
}
