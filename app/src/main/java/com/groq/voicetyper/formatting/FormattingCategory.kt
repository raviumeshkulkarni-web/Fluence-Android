package com.groq.voicetyper.formatting

/**
 * Slice V1: rules-only app-aware formatting styles.
 *
 * NEUTRAL is the default everywhere and is always the identity transform:
 * when formatting is disabled (the default) or a category resolves to
 * NEUTRAL, text passes through byte-for-byte unchanged.
 */
enum class FormattingCategory {
    FORMAL,
    CASUAL,
    VERY_CASUAL,
    NEUTRAL;

    companion object {
        /** Lenient parse; unknown or blank names fail to NEUTRAL (identity). */
        fun fromName(name: String?): FormattingCategory {
            if (name.isNullOrBlank()) return NEUTRAL
            return try {
                valueOf(name.trim().uppercase())
            } catch (_: Exception) {
                NEUTRAL
            }
        }

        /** Short human label for settings UI. NEUTRAL displays as "Off". */
        fun label(category: FormattingCategory): String = when (category) {
            FORMAL -> "Formal"
            CASUAL -> "Casual"
            VERY_CASUAL -> "Very casual"
            NEUTRAL -> "Off"
        }
    }
}
