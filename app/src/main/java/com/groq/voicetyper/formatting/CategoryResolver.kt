package com.groq.voicetyper.formatting

/**
 * Slice V1 (+ built-in app groups): pure field-type -> [FormattingCategory]
 * resolution.
 *
 * Pure Kotlin with no Android imports so it stays JVM-testable. The variation
 * constants mirror `android.text.InputType` values; callers pass the masked
 * variation (`inputType and TYPE_MASK_VARIATION`), or null when unavailable
 * (e.g. bubble path).
 *
 * Precedence: explicit per-package user override wins, then the built-in
 * Wispr-style app-group defaults ([BuiltInApps]), then the field-type
 * heuristic, otherwise NEUTRAL (identity). Null-safe throughout; any failure
 * resolves to NEUTRAL.
 */
object CategoryResolver {

    // Mirrors of android.text.InputType variation constants (0x20/0x30/0x50/0x60).
    const val VARIATION_EMAIL_ADDRESS = 32
    const val VARIATION_EMAIL_SUBJECT = 48
    const val VARIATION_LONG_MESSAGE = 80
    const val VARIATION_SHORT_MESSAGE = 96

    fun resolve(
        variation: Int?,
        packageName: String?,
        overrides: Map<String, FormattingCategory> = emptyMap(),
        builtIn: Map<String, FormattingCategory> = BuiltInApps.PACKAGE_CATEGORY
    ): FormattingCategory {
        try {
            if (!packageName.isNullOrBlank()) {
                overrides[packageName]?.let { return it }
                builtIn[packageName]?.let { return it }
            }
            return when (variation) {
                VARIATION_EMAIL_ADDRESS,
                VARIATION_EMAIL_SUBJECT,
                VARIATION_LONG_MESSAGE -> FormattingCategory.FORMAL
                VARIATION_SHORT_MESSAGE -> FormattingCategory.CASUAL
                else -> FormattingCategory.NEUTRAL
            }
        } catch (_: Exception) {
            return FormattingCategory.NEUTRAL
        }
    }
}
