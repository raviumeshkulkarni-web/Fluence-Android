package com.groq.voicetyper.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

object EditorInfoHelper {

    /**
     * Fail-closed evaluation of whether the focused input field is safe for voice dictation.
     * Returns false if metadata is missing, malformed, or indicates a password or sensitive field.
     */
    fun isFieldSafeForVoice(info: EditorInfo?): Boolean {
        if (info == null) return false

        val typeClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION

        // TYPE_NULL is treated as unsafe by default in initial release
        if (typeClass == InputType.TYPE_NULL) {
            return false
        }

        // Text password variations
        if (typeClass == InputType.TYPE_CLASS_TEXT) {
            val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            if (isPassword) return false
        }

        // Numeric password variations (e.g. PIN)
        if (typeClass == InputType.TYPE_CLASS_NUMBER) {
            val isNumberPassword = variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            if (isNumberPassword) return false
        }

        return true
    }

    /**
     * Checks if AutoLearn observation is permitted.
     * Respects IME_FLAG_NO_PERSONALIZED_LEARNING and field safety.
     */
    fun isAutoLearnAllowed(info: EditorInfo?): Boolean {
        if (!isFieldSafeForVoice(info)) return false
        val isNoLearning = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        return !isNoLearning
    }

    /**
     * Checks if the target field is a multiline text editor.
     */
    fun isMultiline(info: EditorInfo?): Boolean {
        if (info == null) return false
        return (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
    }

    /**
     * Extracts the primary action ID from EditorInfo (e.g. SEARCH, SEND, GO, DONE, NEXT).
     */
    fun getActionId(info: EditorInfo?): Int {
        if (info == null) return EditorInfo.IME_ACTION_NONE
        if (info.actionId != 0) return info.actionId
        return info.imeOptions and EditorInfo.IME_MASK_ACTION
    }

    /**
     * Checks if the field is strictly numeric or phone input.
     */
    fun isNumericField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val typeClass = info.inputType and InputType.TYPE_MASK_CLASS
        return typeClass == InputType.TYPE_CLASS_NUMBER || typeClass == InputType.TYPE_CLASS_PHONE
    }
}
