package com.groq.voicetyper.autolearn.domain

import android.content.Context
import android.util.Log
import android.view.inputmethod.EditorInfo
import com.groq.voicetyper.PrivacyPreferences
import com.groq.voicetyper.autolearn.AutoLearnPreferences
import com.groq.voicetyper.autolearn.SuggestionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AutoLearnSessionManager {
    private const val TAG = "AutoLearnSession"
    private const val MAX_FIELD_CHARS = 10_000

    private val productionScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Test seam: when set, every extraction runs on this dispatcher
     * (tests inject an UnconfinedTestDispatcher). Must be reset to null
     * after each test in @After.
     */
    @Volatile
    internal var dispatcherOverride: CoroutineDispatcher? = null

    private fun scopeForCall(): CoroutineScope {
        val override = dispatcherOverride ?: return productionScope
        return CoroutineScope(override + SupervisorJob())
    }

    @Volatile
    private var activeCommittedText: String? = null

    @Volatile
    private var isPrivacyAllowed: Boolean = false

    @Volatile
    private var targetPackage: String? = null

    /**
     * Windows monitor session dedup (monitor.rs sensor stutter seam): a
     * canonical pair key is counted at most once per observation session;
     * cursor moves within one session must not inflate frequency. Cleared
     * whenever a session starts or ends.
     */
    private val pairLock = Any()
    private val sessionPairKeys = HashSet<String>()

    fun onStartInput(info: EditorInfo?, context: Context) {
        targetPackage = info?.packageName?.toString()
        val masterEnabled = AutoLearnPreferences.isAutoLearnEnabled(context)
        isPrivacyAllowed = masterEnabled &&
            AutoLearnPrivacyHelper.isAutoLearnAllowed(info) &&
            !PrivacyPreferences.isPackageExcluded(context, targetPackage)

        if (!isPrivacyAllowed) {
            endSession()
        }
    }

    fun startSession(committedText: String, context: Context) {
        if (!isPrivacyAllowed || committedText.isBlank()) {
            endSession()
            return
        }

        activeCommittedText = committedText.trim()
        synchronized(pairLock) { sessionPairKeys.clear() }
        Log.d(TAG, "Started observation session")
    }

    fun onTextUpdated(currentTextAroundCursor: String, context: Context) {
        if (PrivacyPreferences.isPackageExcluded(context, targetPackage)) {
            endSession()
            isPrivacyAllowed = false
            return
        }

        val committed = activeCommittedText ?: return
        if (!isPrivacyAllowed || currentTextAroundCursor.isBlank()) return

        if (committed.length > MAX_FIELD_CHARS || currentTextAroundCursor.length > MAX_FIELD_CHARS) {
            endSession()
            return
        }

        scopeForCall().launch {
            // Known set once per batch (Windows monitor parity), then the
            // shared decision core replaces the legacy extraction.
            val known = SuggestionRepository.knownPairKeys(context)
            val corrections = DecisionCore.extractCorrections(
                originalText = committed,
                editedRegion = currentTextAroundCursor,
                knownPairKeys = known
            )
            for (candidate in corrections) {
                // Dedup BEFORE any suspend call: insert into the session
                // set first, skip the DB when the key already exists.
                val key = DecisionCore.canonicalPairKey(
                    candidate.spokenText,
                    candidate.correctedText
                )
                val fresh = synchronized(pairLock) { sessionPairKeys.add(key) }
                if (!fresh) continue
                SuggestionRepository.recordCorrectionCandidate(
                    context = context,
                    spokenText = candidate.spokenText,
                    correctedText = candidate.correctedText
                )
            }
        }
    }

    fun endSession() {
        if (activeCommittedText != null) {
            Log.d(TAG, "Ended observation session")
            activeCommittedText = null
        }
        targetPackage = null
        isPrivacyAllowed = false
        synchronized(pairLock) { sessionPairKeys.clear() }
    }
}
