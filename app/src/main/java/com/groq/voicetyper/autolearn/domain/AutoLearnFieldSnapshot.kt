package com.groq.voicetyper.autolearn.domain

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Owner surface of an observation session (documentation pre-review round).
 * Sessions carry their origin so a teardown hook of one surface can never
 * flush another surface's pending state (review finding: an IME input-view
 * finish must not exit-flush a bubble-armed session; AOSP
 * `InputMethodService.doFinishInput()` calls `finishViews(true)` BEFORE the
 * next target starts, so the next field's security posture is unknowable at
 * that moment). Security exits ignore origin: a downgrade observed by any
 * surface ends any session without a flush.
 */
enum class AutoLearnSurface { IME, BUBBLE }

/**
 * Normalized description of the observed input field, shared by both
 * surfaces. Boolean fields are tri-state on purpose:
 *
 *  - the IME adapter derives them precisely from [EditorInfo], so every
 *    value is known;
 *  - the accessibility adapter (bubble) cannot derive
 *    `IME_FLAG_NO_PERSONALIZED_LEARNING` from an [android.view.accessibility.AccessibilityNodeInfo]
 *    on most platforms, so it passes `null` there - and the session
 *    manager treats ANY unknown privacy-relevant metadata as "do not arm"
 *    (fail-closed, review requirement: prefer a missed learning
 *    opportunity over ever observing a password field).
 */
data class AutoLearnFieldSnapshot(
    /** Target package, or null when unknown. Arming the bubble surface fails closed on null/blank. */
    val packageName: String?,
    val isPassword: Boolean?,
    val isNullInputType: Boolean?,
    /** `IME_FLAG_NO_PERSONALIZED_LEARNING`; null = unknown/unavailable on this surface. */
    val noPersonalizedLearning: Boolean?
) {
    /** Privacy-relevant tri-state consolidation: any positive or unknown member denies. */
    internal fun isObservationAllowedRest(): Boolean {
        if (isPassword == true) return false
        if (isNullInputType == true) return false
        // Unknown no-learning state (bubble surface) fails closed; an
        // asserted flag denies the surface itself.
        if (noPersonalizedLearning != false) return false
        return true
    }

    companion object {
        /**
         * EditorInfo-derived snapshot for the IME surface. Semantics are
         * exactly the check chain of [AutoLearnPrivacyHelper.isAutoLearnAllowed]
         * decomposed into data (Stage 2 unifies the duplicate
         * `EditorInfoHelper.isAutoLearnAllowed` variant onto this).
         */
        fun fromEditorInfo(info: EditorInfo?): AutoLearnFieldSnapshot {
            if (info == null) {
                // Mirror of the helper's null -> false: everything unknown.
                return AutoLearnFieldSnapshot(
                    packageName = null,
                    isPassword = null,
                    isNullInputType = null,
                    noPersonalizedLearning = null
                )
            }
            val typeClass = info.inputType and InputType.TYPE_MASK_CLASS
            val variation = info.inputType and InputType.TYPE_MASK_VARIATION
            val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            return AutoLearnFieldSnapshot(
                packageName = info.packageName?.toString(),
                isPassword = isPassword,
                isNullInputType = typeClass == InputType.TYPE_NULL,
                noPersonalizedLearning = (info.imeOptions and
                        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
            )
        }
    }
}
