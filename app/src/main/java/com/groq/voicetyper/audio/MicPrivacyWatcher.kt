package com.groq.voicetyper.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Android 10+ microphone privacy mute detector inspired by wmkeyboard's
 * MicBlockWatcher.
 *
 * Detects when Android feeds dictation silence (isClientSilenced == true) due
 * to the system microphone privacy toggle / tile or developer sensors-off
 * switch.
 *
 * When [start] is given an audio session id, only that capture is watched.
 * Otherwise all active captures are watched, because Android does not expose
 * the owning package of a recording configuration. A silenced capture is
 * checked again after [CONFIRM_MS] before the callback fires, and the callback
 * fires at most once.
 */
class MicPrivacyWatcher(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val onMuteConfirmed: () -> Unit
) {
    private var callback: AudioManager.AudioRecordingCallback? = null
    private var isRegistered = false
    private var sessionId = NO_SESSION

    private val confirmRunnable = Runnable {
        if (!isRegistered || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@Runnable
        val am = audioManager() ?: return@Runnable
        if (isBlocked(am.activeRecordingConfigurations, sessionId)) {
            Log.w(TAG, "Microphone privacy mute confirmed by system switch")
            stop()
            onMuteConfirmed()
        }
    }

    fun start(sessionId: Int = NO_SESSION) {
        stop()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val am = audioManager() ?: return

        this.sessionId = sessionId
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                super.onRecordingConfigChanged(configs)
                evaluate(configs)
            }
        }

        try {
            am.registerAudioRecordingCallback(cb, handler)
            callback = cb
            isRegistered = true
            evaluate(am.activeRecordingConfigurations)
        } catch (e: Exception) {
            callback = null
            isRegistered = false
            Log.w(TAG, "Failed to register AudioRecordingCallback", e)
        }
    }

    fun stop() {
        handler.removeCallbacks(confirmRunnable)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cb = callback ?: return
        callback = null
        isRegistered = false

        try {
            audioManager()?.unregisterAudioRecordingCallback(cb)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister AudioRecordingCallback", e)
        }
    }

    internal fun evaluate(configs: List<AudioRecordingConfiguration>?) {
        handler.removeCallbacks(confirmRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            isBlocked(configs, sessionId)
        ) {
            handler.postDelayed(confirmRunnable, CONFIRM_MS)
        }
    }

    private fun audioManager(): AudioManager? =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    companion object {
        private const val TAG = "MicPrivacyWatcher"
        const val NO_SESSION = -1
        const val CONFIRM_MS = 750L

        /**
         * Pure evaluation logic.
         *
         * A specific [sessionId] watches only that capture; [NO_SESSION]
         * watches every active capture. Returns true only when at least one
         * watched capture exists and every watched capture is marked as
         * silenced by the system.
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        fun isBlocked(
            configs: List<AudioRecordingConfiguration>?,
            sessionId: Int = NO_SESSION
        ): Boolean {
            if (Build.VERSION.SDK_INT in 1 until Build.VERSION_CODES.Q) return false
            if (configs.isNullOrEmpty()) return false
            val watched = if (sessionId == NO_SESSION) {
                configs
            } else {
                configs.filter { it.clientAudioSessionId == sessionId }
            }
            return watched.isNotEmpty() && watched.all { it.isClientSilenced }
        }
    }
}
