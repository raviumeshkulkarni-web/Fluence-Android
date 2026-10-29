package com.groq.voicetyper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

private const val TAG = "TranscriptRescue"

/**
 * Last-resort delivery when the dictation target is gone: keep the completed
 * transcript on the clipboard so accepting without a focused input never
 * silently drops it.
 */
internal fun rescueTranscriptToClipboard(context: Context, text: String): Boolean {
    val rescued = text.trim()
    if (rescued.isEmpty()) return false
    return try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("Fluence transcript", rescued))
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to rescue transcript to clipboard", e)
        false
    }
}
