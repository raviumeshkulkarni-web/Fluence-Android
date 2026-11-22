package com.groq.voicetyper.voice

/**
 * Matches the casing of dictated text to the place it is inserted.
 *
 * Recognizers commonly capitalize the first word of every utterance because
 * each utterance looks like a new sentence to them. The keyboard knows what
 * precedes the caret, so an automatic capital is lowered when the phrase lands
 * mid-sentence, and a missing capital is raised when it starts one. When the
 * preceding context is unavailable (`sentenceStart == null`), opening casing
 * is preserved verbatim.
 */
object VoiceCasing {

    const val CONTEXT_CHARS = 8

    private val TERMINATORS = setOf(
        '.', '!', '?', '…', '。', '！', '？', '।', '؟', '۔', '።', '။', '།'
    )

    private val CLOSERS = setOf('"', '\'', '”', '’', '»', '›', ')', ']', '}')

    private val TRAILING = TERMINATORS + CLOSERS + setOf(',', ';', ':')

    private val ABBREVIATIONS = setOf(
        "e.g", "i.e", "etc", "vs", "al", "approx", "dept", "fig", "inc", "jr", "sr",
        "no", "vol", "mr", "mrs", "ms", "dr", "prof", "st", "a.m", "p.m",
        "co", "corp", "ltd", "ave", "rd", "blvd", "apt", "univ", "est"
    )

    /**
     * [text] with its opening capital judged against [sentenceStart], and with
     * the first letter of later sentences raised. When [sentenceStart] is null
     * (unknown cursor context), the opening casing is left untouched.
     */
    fun apply(text: String, sentenceStart: Boolean?, raise: Boolean = true): String {
        val opened = if (sentenceStart == null) {
            text
        } else {
            openingCase(text, sentenceStart, raise)
        }
        return capitalizeInnerSentences(opened)
    }

    /**
     * Whether a phrase landing after [before] starts a sentence. An empty
     * field, a new line, or a terminator behind any closing quote or bracket
     * counts as a sentence start.
     */
    fun startsSentence(before: CharSequence?): Boolean {
        if (before.isNullOrEmpty()) return true
        val blanks = before.takeLastWhile { it.isWhitespace() }
        if (blanks.any { it == '\n' || it == '\r' }) return true
        var i = before.length - blanks.length - 1
        if (i < 0) return true
        while (i >= 0 && before[i] in CLOSERS) i--
        return i >= 0 && before[i] in TERMINATORS
    }

    private fun openingCase(text: String, sentenceStart: Boolean, raise: Boolean): String {
        if (text.isEmpty()) return text
        val index = text.indexOfFirst { it.isLetter() }
        if (index < 0) return text

        if (sentenceStart) {
            if (!raise) return text
            return raisedOpening(text, index)
        }

        val first = text[index]
        if (!first.isUpperCase()) return text
        val word = wordAt(text, index)
        if (isPronounI(word)) return text
        if (word.drop(1).any { it.isUpperCase() }) return text
        return text.substring(0, index) + first.lowercaseChar() + text.substring(index + 1)
    }

    private fun raisedOpening(text: String, index: Int): String {
        val first = text[index]
        if (!first.isLowerCase()) return text
        val word = wordAt(text, index)
        if (word.drop(1).any { it.isUpperCase() }) return text
        return text.substring(0, index) + first.titlecaseChar() + text.substring(index + 1)
    }

    private fun wordAt(text: String, start: Int): String {
        var end = start
        while (end < text.length && !text[end].isWhitespace()) end++
        return text.substring(start, end)
    }

    private fun isPronounI(word: String): Boolean {
        val trimmed = word.trimEnd { it in TRAILING }
        return trimmed == "I" ||
            (trimmed.length > 1 && trimmed[0] == 'I' && (trimmed[1] == '\'' || trimmed[1] == '’'))
    }

    private fun capitalizeInnerSentences(text: String): String {
        var out: StringBuilder? = null
        var i = 1
        while (i < text.length) {
            val mark = text[i]
            if (mark !in TERMINATORS || (mark == '.' && isAbbreviationDot(text, i))) {
                i++
                continue
            }
            var j = i + 1
            while (j < text.length && text[j] in CLOSERS) j++
            val gap = j
            while (j < text.length && text[j].isWhitespace()) j++
            if (j == gap || j == text.length) {
                i++
                continue
            }
            val letter = text[j]
            if (letter.isLowerCase()) {
                val sb = out ?: StringBuilder(text).also { out = it }
                sb.setCharAt(j, letter.uppercaseChar())
            }
            i = j
        }
        return out?.toString() ?: text
    }

    private fun isAbbreviationDot(text: String, at: Int): Boolean {
        var start = at
        while (start > 0 && !text[start - 1].isWhitespace()) {
            start--
        }
        val rawWord = text.substring(start, at)
            .trim('(', '[', '{', '"', '\'', '“', '‘', '”', '’')
            .lowercase()
        val normalized = rawWord.trimEnd('.')
        if (normalized.length == 1 && normalized[0].isLetter()) return true
        return rawWord in ABBREVIATIONS ||
            normalized in ABBREVIATIONS ||
            rawWord.endsWith(".g") ||
            rawWord.endsWith(".e")
    }
}
