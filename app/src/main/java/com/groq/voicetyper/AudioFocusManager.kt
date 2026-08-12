package com.groq.voicetyper

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Isolated observer that mirrors the existing Fluence recording lifecycle onto
 * Android audio focus. It does NOT participate in recording in any way.
 *
 * Contract:
 *  - [TranscriptionSessionManager.recordingState] == RECORDING → request
 *    AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK (other apps' media is ducked).
 *  - Anything else (TRANSCRIBING / IDLE / ERROR) → abandon focus immediately,
 *    so focus is never held during transcription or after the session ends.
 *
 * Safety:
 *  - Idempotent: reconciles against the CURRENT state, never counts transitions.
 *  - Failure-tolerant: request/abandon failures and exceptions are logged only;
 *    recording/transcription is never blocked, altered, or thrown into.
 *  - Never re-requests after focus loss; abandons cleanly instead.
 *  - When the preference is OFF no AudioManager interaction happens at all.
 *  - Process death relies on Android's normal audio-focus cleanup.
 *
 * Attached once from [FluenceApplication.onCreate] (single process-level
 * attachment point covering every recording entry path).
 */
object AudioFocusManager {

    private const val TAG = "AudioFocusManager"

    /** Duck-only V1 focus gain. Exposed for unit-test verification. */
    internal const val DUCKING_FOCUS_GAIN = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK

    // Lives for the entire app process lifetime; never cancelled. Mirror of the
    // documented TranscriptionSessionManager.scope pattern.
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val lock = Any()

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var collectJob: Job? = null
    private var attached = false
    private var focusHeld = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        // Focus was taken by another app (e.g. a phone call). Abandon cleanly;
        // never touch Fluence recording state and never re-request mid-session.
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(TAG, "Audio focus lost ($change); abandoning cleanly")
                release()
            }
            else -> Unit
        }
    }

    /**
     * Single process-level attachment point. Idempotent; safe to call more than once.
     */
    fun attach(context: Context) {
        attachCore(context)
        if (collectJob == null) {
            synchronized(lock) {
                if (collectJob == null) {
                    collectJob = scope.launch {
                        TranscriptionSessionManager.recordingState.collect { state ->
                            reconcile(state)
                        }
                    }
                }
            }
        }
    }

    /**
     * Test seam: wires the manager to a context without starting the live
     * observer, so unit tests can drive reconcile() directly and deterministically.
     */
    internal fun attachForTest(context: Context) {
        attachCore(context)
    }

    private fun attachCore(context: Context) {
        val appCtx = context.applicationContext
        synchronized(lock) {
            if (!attached) {
                appContext = appCtx
                audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                attached = true
            }
        }
    }

    /**
     * Reconciles focus ownership with the CURRENT recording state. Internal so
     * unit tests can drive it directly; the live collector calls it per state change.
     */
    internal fun reconcile(state: RecordingState) {
        val ctx = appContext ?: return
        val enabled = try {
            AudioFocusPreferences.isDuckingEnabled(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "Audio focus preference read failed; treating as disabled", e)
            false
        }
        if (!enabled) {
            release()
            return
        }
        if (state == RecordingState.RECORDING) {
            acquire()
        } else {
            release()
        }
    }

    private fun acquire() {
        synchronized(lock) {
            if (focusHeld) return
            val manager = audioManager ?: return
            val request = ensureFocusRequest() ?: return
            try {
                val result = manager.requestAudioFocus(request)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    focusHeld = true
                } else {
                    // Enhancement only: a denied request must never affect dictation.
                    Log.w(TAG, "Audio focus request failed (result=$result); recording continues")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio focus request threw; recording continues", e)
            }
        }
    }

    private fun ensureFocusRequest(): AudioFocusRequest? {
        focusRequest?.let { return it }
        try {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(DUCKING_FOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(focusChangeListener, Handler(Looper.getMainLooper()))
                .build()
            focusRequest = request
            return request
        } catch (e: Exception) {
            Log.w(TAG, "Could not build AudioFocusRequest; recording continues", e)
            return null
        }
    }

    private fun release() {
        synchronized(lock) {
            if (!focusHeld) return
            val manager = audioManager
            val request = focusRequest
            if (manager == null || request == null) {
                focusHeld = false
                return
            }
            try {
                manager.abandonAudioFocusRequest(request)
            } catch (e: Exception) {
                Log.w(TAG, "Audio focus abandon threw; recording continues", e)
            } finally {
                focusHeld = false
            }
        }
    }

    /** Test-only reset (mirrors the PrivacyPreferences.resetForTests convention). */
    internal fun resetForTests() {
        synchronized(lock) {
            collectJob?.cancel()
            collectJob = null
            attached = false
            focusHeld = false
            focusRequest = null
            appContext = null
            audioManager = null
        }
    }
}
