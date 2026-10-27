package com.groq.voicetyper.autolearn.domain

import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
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
 * Normalized description of the observed input field - THE single shared
 * field gate for both surfaces (review round: converge the duplicate
 * `AutoLearnPrivacyHelper` / `EditorInfoHelper.isAutoLearnAllowed` chains
 * so the IME dictation path and the bubble path cannot drift apart).
 * Boolean fields are tri-state on purpose:
 *
 *  - the IME adapter derives every value precisely from [EditorInfo];
 *  - the accessibility adapter (bubble) can never derive
 *    `IME_FLAG_NO_PERSONALIZED_LEARNING` or a trustworthy `inputType`
 *    from an [android.view.accessibility.AccessibilityNodeInfo]
 *    (nodes of WebView/Compose content routinely report `TYPE_NULL`/0 even
 *    for plain text fields), so those two stay `null` there - see
 *    [isObservationAllowedForBubble] for the bubble policy that follows.
 *
 * Documented divergence (design-consensus note, Coverage A): the IME
 * surface fails closed on any unknown member; the bubble surface only
 * fails closed on security insights it CAN read (password, excluded
 * package, asserted positive evidence) because the IMF-contract fields
 * are structurally unobtainable over accessibility - denying on them
 * would permanently disable bubble coverage, not protect it.
 */
data class AutoLearnFieldSnapshot(
    /** Target package, or null when unknown. Both surfaces fail closed on null/blank. */
    val packageName: String?,
    val isPassword: Boolean?,
    val isNullInputType: Boolean?,
    /** `IME_FLAG_NO_PERSONALIZED_LEARNING`; null = unknown/unavailable on this surface. */
    val noPersonalizedLearning: Boolean?
) {
    /**
     * IME-surface gate: the full EditorInfo chain (no personalized learning,
     * TYPE_NULL, password variations). Every member is concrete on this
     * surface, so "any unknown or positive denies" is exact fail-closed
     * behavior - identical to the legacy helper chain.
     */
    internal fun isObservationAllowedRest(): Boolean {
        if (isPassword == true) return false
        if (isNullInputType == true) return false
        // Unknown no-learning state fails closed on the IME surface; an
        // asserted flag denies it too.
        if (noPersonalizedLearning != false) return false
        return true
    }

    /**
     * Bubble-surface gate (Coverage A): deny on every security field that
     * reports positive evidence - a password attribute, an asserted
     * TYPE_NULL inputType, an asserted no-learning flag - or on unknown
     * package coverage, but tolerate the `null` (unknowable) IMF members,
     * which no [android.view.accessibility.AccessibilityNodeInfo] can
     * ever report. Privacy weight over bubbles is carried by
     * `node.isPassword`, the excluded-package check, the target-package
     * identity check and the per-update positive-evidence revocations.
     */
    internal fun isObservationAllowedForBubble(excluded: Boolean = false): Boolean {
        if (isPassword != false) return false
        if (isNullInputType == true || noPersonalizedLearning == true) return false
        return !packageName.isNullOrBlank() && !excluded
    }

    companion object {
        /**
         * EditorInfo-derived snapshot for the IME surface. Semantics are
         * exactly the check chain of [AutoLearnPrivacyHelper.isAutoLearnAllowed]
         * decomposed into data - the legacy chains in
         * `EditorInfoHelper.isAutoLearnAllowed` (now removed) delegate here.
         */
        fun fromEditorInfo(info: EditorInfo?): AutoLearnFieldSnapshot {
            if (info == null) {
                // Mirror of the helper's null -> false: everything unknown.
                return allUnknown()
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

        /**
         * AccessibilityNodeInfo-derived snapshot for the bubble surface.
         *
         * `inputType` is deliberately NOT read from the node: AOSP nodes
         * of WebView/Compose content report `TYPE_NULL`/0 for ordinary
         * text fields (design-review finding), so a node-sourced
         * `isNullInputType` would be a false positive, not a privacy
         * insight - and `isPassword` ([AccessibilityNodeInfo.isPassword])
         * is the actual privacy read the platform maintains for us. The
         * two IMF members stay `null` (unknowable without serving as the
         * IME) and are handled by [isObservationAllowedForBubble].
         */
        fun fromAccessibilityNode(node: AccessibilityNodeInfo?): AutoLearnFieldSnapshot {
            if (node == null) return allUnknown()
            return AutoLearnFieldSnapshot(
                packageName = node.packageName?.toString()?.ifBlank { null },
                isPassword = node.isPassword,
                isNullInputType = null,
                noPersonalizedLearning = null
            )
        }

        /** Everything unknown - denies on every surface's gate. */
        private fun allUnknown() = AutoLearnFieldSnapshot(
            packageName = null,
            isPassword = null,
            isNullInputType = null,
            noPersonalizedLearning = null
        )
    }
}
