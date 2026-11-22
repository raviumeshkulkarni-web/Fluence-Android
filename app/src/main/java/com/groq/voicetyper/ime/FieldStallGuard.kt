package com.groq.voicetyper.ime

import android.os.Build
import android.os.SystemClock
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText
import androidx.annotation.RequiresApi

/**
 * Service-level InputConnection stall and ANR guard inspired by wmkeyboard.
 *
 * Prevents a slow or non-responsive target app from freezing the IME main thread.
 * Synchronous Binder IPC calls to the target app (such as getTextBeforeCursor,
 * getSelectedText, getCursorCapsMode) wait for up to 2 seconds by default on Android.
 * If the target app's main thread is locked, chained reads will exceed 5 seconds and cause an ANR.
 *
 * If any single read takes >= [SLOW_READ_MS], FieldStallGuard marks the field as stalled
 * and returns safe defaults (null or 0) for [HOLD_MS] to protect the IME main thread,
 * until the field signals responsiveness via [noteResponsive].
 *
 * Implements [InputConnection] via Kotlin interface delegation (`by base`) to avoid
 * Android framework stub constructor coupling while intercepting all read operations.
 */
class FieldStallGuard(
    private val base: InputConnection,
    private val clock: () -> Long = SystemClock::uptimeMillis,
) : InputConnection by base {

    @Volatile
    private var stalledUntil = 0L

    val isStalled: Boolean get() = clock() < stalledUntil

    fun noteResponsive() {
        stalledUntil = 0L
    }

    private inline fun <T> read(skipped: T, call: () -> T): T {
        val start = clock()
        if (start < stalledUntil) return skipped
        val result = try {
            call()
        } catch (_: Exception) {
            val end = clock()
            if (end - start >= SLOW_READ_MS) {
                stalledUntil = end + HOLD_MS
            }
            return skipped
        }
        val end = clock()
        if (end - start >= SLOW_READ_MS) {
            stalledUntil = end + HOLD_MS
        }
        return result
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        read(null) { base.getTextBeforeCursor(n, flags) }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
        read(null) { base.getTextAfterCursor(n, flags) }

    override fun getSelectedText(flags: Int): CharSequence? =
        read(null) { base.getSelectedText(flags) }

    override fun getCursorCapsMode(reqModes: Int): Int =
        read(0) { base.getCursorCapsMode(reqModes) }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
        read(null) { base.getExtractedText(request, flags) }

    @RequiresApi(Build.VERSION_CODES.S)
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? =
        read(null) { base.getSurroundingText(beforeLength, afterLength, flags) }

    companion object {
        const val SLOW_READ_MS = 1_000L
        const val HOLD_MS = 3_000L
    }
}
