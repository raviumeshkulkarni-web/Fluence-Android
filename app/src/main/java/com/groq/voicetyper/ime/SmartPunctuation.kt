package com.groq.voicetyper.ime

/**
 * Result of resolving punctuation insertion against current text context.
 * [deleteCount]: number of characters to delete before the cursor (0 or 1).
 * [insertText]: the text to commit at the cursor.
 */
data class PunctuationAction(val deleteCount: Int, val insertText: String)

object SmartPunctuation {

    private val SENTENCE_PUNCTUATION = setOf(".", ",", "?", "!", ":", ";")

    /**
     * Resolves punctuation insertion with minimal, conservative rules.
     *
     * The ONLY case that modifies existing text by deleting 1 trailing space is:
     * 1. No active selection (`hasSelection == false`).
     * 2. The punctuation is a standard sentence mark (. , ? ! : ;).
     * 3. Text before cursor ends with exactly one whitespace character.
     * 4. That whitespace is immediately preceded by an alphanumeric character (letter or digit).
     * 5. The cursor is at the end (`textAfter.isEmpty()`).
     *
     * In EVERY other case (cursor in middle of text, decimals, URLs, emails, abbreviations,
     * multiple spaces, active selection), ordinary insertion is performed with zero text deletion.
     */
    fun resolve(
        symbol: String,
        textBefore: String,
        textAfter: String,
        hasSelection: Boolean = false
    ): PunctuationAction {
        if (hasSelection || textBefore.isEmpty()) {
            return PunctuationAction(deleteCount = 0, insertText = symbol)
        }

        if (symbol in SENTENCE_PUNCTUATION &&
            textAfter.isEmpty() &&
            textBefore.endsWith(" ") &&
            !textBefore.endsWith("  ") &&
            textBefore.length >= 2
        ) {
            val charBeforeSpace = textBefore[textBefore.length - 2]
            if (charBeforeSpace.isLetterOrDigit()) {
                return PunctuationAction(
                    deleteCount = 1,
                    insertText = "$symbol "
                )
            }
        }

        // Ordinary insertion fallback: never delete anything
        return PunctuationAction(deleteCount = 0, insertText = symbol)
    }
}
