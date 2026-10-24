package com.groq.voicetyper

import android.app.Application
import android.util.Log
import com.groq.voicetyper.autolearn.SuggestionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-level composition root.
 *
 * Single attachment point for process-wide observers that must cover every
 * recording entry path (IME, bubble, future entries). Audio focus is attached
 * here so no service (VoiceInputIME / FloatingBubbleService) needs its own hook.
 */
class FluenceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AudioFocusManager.attach(this)

        // Stale-pending sweep fired from process start (Windows parity:
        // rows whose pair no longer passes the shared gates are demoted).
        // Off the main thread and fail-open: a sweep error must never
        // affect app start.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                SuggestionRepository.purgeInvalidPending(this@FluenceApplication)
            } catch (e: Exception) {
                Log.w("AutoLearnPurge", "stale-pending sweep failed", e)
            }
        }
    }
}
