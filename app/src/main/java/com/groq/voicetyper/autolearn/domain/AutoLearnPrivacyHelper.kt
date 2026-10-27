package com.groq.voicetyper.autolearn.domain

import android.view.inputmethod.EditorInfo

object AutoLearnPrivacyHelper {

    /**
     * Inspects EditorInfo to enforce Android native privacy rules.
     * Returns true if Auto Learn observation is permitted; false if it must be disabled.
     *
     * Converged implementation (review round: one shared field gate):
     * delegates to the [AutoLearnFieldSnapshot] decomposition so the IME
     * chain and the bubble adapter share one source of truth. Semantics
     * are identical to the legacy inline chain (equivalence-tested in
     * [com.groq.voicetyper.autolearn.AutoLearnFieldGateTest]).
     */
    fun isAutoLearnAllowed(info: EditorInfo?): Boolean =
        AutoLearnFieldSnapshot.fromEditorInfo(info).isObservationAllowedRest()
}
