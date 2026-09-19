package com.groq.voicetyper.formatting

import android.content.Context

/**
 * Slice V1: rules-only, offline-safe transcript formatting.
 *
 * The [format] core is pure (no Context) and total: it never deletes words,
 * never throws, and returns its input unchanged for NEUTRAL, overlong input,
 * or any internal failure. [maybeFormat] is the single session-pipeline entry
 * point: with the master switch OFF (the default) it is the identity function,
 * so default behavior is bit-for-bit unchanged.
 */
object AppAwareFormatter {

    const val MAX_LENGTH = 5000

    private val sentenceStartRegex = Regex("([.!?]\\s+)(\\p{Ll})")

    /**
     * Applies [category] styling to [text]. NEUTRAL returns [text] unchanged.
     */
    fun format(text: String, category: FormattingCategory): String {
        try {
            if (text.isEmpty() || text.length > MAX_LENGTH) return text
            return when (category) {
                FormattingCategory.FORMAL -> applyFormal(text)
                FormattingCategory.CASUAL -> applyCasual(text)
                FormattingCategory.VERY_CASUAL -> applyVeryCasual(text)
                FormattingCategory.NEUTRAL -> text
            }
        } catch (_: Exception) {
            return text
        }
    }

    /**
     * Session-pipeline gate: identity unless the user opted in via the master
     * switch. Resolves the category (package override, else built-in default,
     * else field-type hint, else NEUTRAL) and formats with it directly: the
     * detected category IS the output style. Never throws; any failure
     * returns [text] unchanged.
     */
    fun maybeFormat(
        context: Context,
        packageName: String?,
        text: String,
        inputTypeVariation: Int? = null
    ): String {
        try {
            if (text.isEmpty() || text.length > MAX_LENGTH) return text
            if (!FormattingPreferences.isMasterEnabled(context)) return text
            val resolved = CategoryResolver.resolve(
                variation = inputTypeVariation,
                packageName = packageName,
                overrides = FormattingPreferences.getOverrides(context)
            )
            if (resolved == FormattingCategory.NEUTRAL) return text
            return format(text, resolved)
        } catch (_: Exception) {
            return text
        }
    }

    private fun applyFormal(text: String): String {
        if (text.isBlank()) return text
        val capitalized = capitalizeSentences(text)
        val trimmed = capitalized.trimEnd()
        if (trimmed.isEmpty()) return text
        val last = trimmed.last()
        if (last == '.' || last == '!' || last == '?') return capitalized
        // Ensure a trailing period. A single trailing space collapses to ". "
        // (SmartPunctuation parity); only whitespace is ever removed, never words.
        return if (capitalized.endsWith(" ") && !capitalized.endsWith("  ")) {
            "$trimmed. "
        } else {
            "$trimmed."
        }
    }

    private fun applyCasual(text: String): String {
        // Keep capitalization; just drop a single trailing period.
        return stripSingleTrailingPeriod(text)
    }

    private fun applyVeryCasual(text: String): String {
        val lowered = text[0].lowercaseChar() + text.substring(1)
        return stripSingleTrailingPeriod(lowered)
    }

    private fun capitalizeSentences(text: String): String {
        val firstLetter = text.indexOfFirst { it.isLetter() }
        var result = if (firstLetter >= 0) {
            text.substring(0, firstLetter) +
                text[firstLetter].uppercaseChar() +
                text.substring(firstLetter + 1)
        } else {
            text
        }
        // Capitalize the first lowercase letter following sentence-ending
        // punctuation plus whitespace. "U.S."-style abbreviations without a
        // following space are left alone.
        result = sentenceStartRegex.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase()
        }
        return result
    }

    private fun stripSingleTrailingPeriod(text: String): String {
        if (text.isEmpty()) return text
        val trailingWs = text.takeLastWhile { it.isWhitespace() }
        val core = text.dropLast(trailingWs.length)
        if (core.isEmpty() || !core.endsWith(".")) return text
        // Only a lone trailing period: leave "..", "!." and "?." untouched.
        if (core.length >= 2) {
            val previous = core[core.length - 2]
            if (previous == '.' || previous == '!' || previous == '?') return text
        }
        return core.dropLast(1) + trailingWs
    }
}
