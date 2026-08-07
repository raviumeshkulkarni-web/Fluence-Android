package com.groq.voicetyper

import android.os.SystemClock
import android.util.Log

/**
 * Debug-only runtime tracing for the floating-bubble flash investigation.
 * Every call is a no-op in release builds. Format:
 *   <event>|<elapsedRealtimeNanos>|<detail>
 */
object BubbleTrace {
    private const val TAG = "BubbleTrace"

    fun log(event: String, detail: String = "") {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "$event|${SystemClock.elapsedRealtimeNanos()}|$detail")
        }
    }
}
