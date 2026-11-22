package com.groq.voicetyper.voice

/**
 * Result of context-aware voice text insertion calculation.
 * [deleteBefore]: number of characters to delete before the cursor (e.g. collapsing orphaned space before punctuation).
 * [formattedText]: the text to commit at the cursor.
 */
data class InsertionPlan(
    val deleteBefore: Int,
    val formattedText: String
)

/**
 * Intelligent spacing and casing coordinator for voice text insertion.
 * Ensures speech transcription blends into existing field text without word
 * concatenation, double spaces, detached punctuation, or recognizer capitals
 * landing mid-sentence.
 */
object VoiceSpacing {

    private val ATTACHING_PUNCTUATION = setOf('.', ',', '?', '!', ':', ';', '%', ')', ']', '}')
    private val OPENING_PUNCTUATION = setOf('(', '[', '{', '"', '“', '‘')
    private val NO_TRAILING_SPACE_CHARS = setOf('(', '[', '{', '“', '‘', '@', '#', '$')

    /**
     * Calculates the optimal insertion plan given the text immediately before
     * the cursor, the transcribed text, and optionally the text after the
     * cursor.
     *
     * When [textBefore] is null (e.g. FieldStallGuard stall or IPC read
     * timeout), treats the context as append-with-space rather than an empty
     * field, avoiding accidental mid-sentence capitalization or lost spacing.
     *
     * [textAfter] prevents a redundant trailing space when the field already
     * has whitespace there, and avoids inserting one before closing
     * punctuation.
     */
    fun calculateInsertion(
        textBefore: String?,
        textToInsert: String,
        appendTrailingSpace: Boolean = true,
        textAfter: String? = null
    ): InsertionPlan {
        if (textToInsert.isBlank()) {
            return InsertionPlan(deleteBefore = 0, formattedText = "")
        }

        val trimmedInsert = textToInsert.trim()
        if (trimmedInsert.isEmpty()) {
            return InsertionPlan(deleteBefore = 0, formattedText = "")
        }

        val firstChar = trimmedInsert.first()
        val startsWithAttachingPunct = firstChar in ATTACHING_PUNCTUATION

        var deleteCount = 0
        val leadingSpace: String

        if (textBefore == null) {
            leadingSpace = if (startsWithAttachingPunct) "" else " "
        } else if (textBefore.isEmpty()) {
            leadingSpace = ""
        } else if (textBefore.endsWith(" ")) {
            if (startsWithAttachingPunct && !textBefore.endsWith("  ") && textBefore.length >= 2) {
                val charBeforeSpace = textBefore[textBefore.length - 2]
                if (charBeforeSpace.isLetterOrDigit() ||
                    charBeforeSpace in setOf('.', '?', '!') ||
                    charBeforeSpace in setOf('"', '\'', '”', '’', ')', ']', '}')
                ) {
                    // Collapse the trailing space before inserting punctuation (e.g. "hello " + "," -> "hello, ")
                    deleteCount = 1
                }
            }
            leadingSpace = ""
        } else {
            val lastChar = textBefore.last()
            if (lastChar == '\n' || lastChar in OPENING_PUNCTUATION || startsWithAttachingPunct) {
                leadingSpace = ""
            } else {
                leadingSpace = " "
            }
        }

        val sentenceStart: Boolean? = if (textBefore == null) {
            null
        } else {
            VoiceCasing.startsSentence(textBefore)
        }
        val casedInsert = VoiceCasing.apply(trimmedInsert, sentenceStart)

        val lastInsertChar = casedInsert.last()
        val trailingSpace = if (
            appendTrailingSpace &&
            lastInsertChar != '\n' &&
            lastInsertChar !in NO_TRAILING_SPACE_CHARS &&
            needsTrailingSpace(textAfter)
        ) {
            " "
        } else {
            ""
        }

        return InsertionPlan(
            deleteBefore = deleteCount,
            formattedText = "$leadingSpace$casedInsert$trailingSpace"
        )
    }

    /**
     * A trailing space is needed at the end of the field, or before following
     * text that starts with a word. Existing whitespace, closing punctuation,
     * or an opening bracket already provides separation or should stay tight.
     */
    private fun needsTrailingSpace(textAfter: String?): Boolean {
        if (textAfter.isNullOrEmpty()) return true
        return textAfter.first().isLetterOrDigit()
    }
}
