package com.groq.voicetyper.autolearn.domain

import android.content.Context
import android.util.Log
import android.view.inputmethod.EditorInfo
import com.groq.voicetyper.PrivacyPreferences
import com.groq.voicetyper.autolearn.AutoLearnPreferences
import com.groq.voicetyper.autolearn.SuggestionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AutoLearnSessionManager {
    private const val TAG = "AutoLearnSession"
    private const val MAX_FIELD_CHARS = 10_000

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var activeCommittedText: String? = null

    @Volatile
    private var isPrivacyAllowed: Boolean = false

    @Volatile
    private var targetPackage: String? = null

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

        scope.launch {
            val corrections = WordLcsExtractor.extractCorrections(committed, currentTextAroundCursor)
            for (candidate in corrections) {
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
    }
}
