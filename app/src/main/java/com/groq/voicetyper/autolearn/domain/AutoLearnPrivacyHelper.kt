package com.groq.voicetyper.autolearn.domain

import android.text.InputType
import android.view.inputmethod.EditorInfo

object AutoLearnPrivacyHelper {

    /**
     * Inspects EditorInfo to enforce Android native privacy rules.
     * Returns true if Auto Learn observation is permitted; false if it must be disabled.
     */
    fun isAutoLearnAllowed(info: EditorInfo?): Boolean {
        if (info == null) return false

        // 1. Respect Android native IME_FLAG_NO_PERSONALIZED_LEARNING flag
        val isNoLearning = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        if (isNoLearning) return false

        // 2. Inspect inputType for any password variation
        val typeClass = info.inputType and InputType.TYPE_MASK_CLASS
        if (typeClass == InputType.TYPE_NULL) return false

        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

        if (isPassword) return false

        return true
    }
}
