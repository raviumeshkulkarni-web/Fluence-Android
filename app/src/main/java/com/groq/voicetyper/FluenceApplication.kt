package com.groq.voicetyper

import android.app.Application

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
    }
}
