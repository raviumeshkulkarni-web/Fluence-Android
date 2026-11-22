package com.groq.voicetyper.voice

/**
 * Deterministic text normalizer for voice dictation.
 * Cleans up common ASR speech-to-text artifacts such as orphaned spaces before punctuation,
 * missing spaces after punctuation, spacing inside quotes/brackets, spacing around symbols,
 * and normalizes capitalization after internal sentence boundaries and line breaks.
 *
 * Preserves technical tokens (URLs, host:port, URI schemes like mailto:, double colons like ::,
 * numeric grouping/decimals, timestamps, and dotfiles like .env).
 */
object VoiceTextNormalizer {

    // Space before standard punctuation and closing brackets/typographic quotes
    // (dot is handled separately so dotfiles like "open .env" stay intact).
    private val SPACE_BEFORE_PUNCTUATION = Regex("[ \\t]+([,:;?!%)\\]}”’])")
    private val SPACE_BEFORE_PERIOD = Regex("[ \\t]+\\.(?![\\p{L}\\p{N}])")

    // Space after opening brackets and typographic quotes
    private val SPACE_AFTER_OPENING_BRACKET = Regex("([(\\[{“‘])\\s+")

    // Space inside quotation marks
    private val SPACE_AFTER_OPENING_QUOTE = Regex("(^|[\\s(\\[{])\"[ \\t]+(\\S)")
    private val SPACE_BEFORE_CLOSING_QUOTE = Regex("(\\S)[ \\t]+\"($|[\\s)\\]}.,!?:;])")

    // Space after currency symbols when followed by a digit: e.g. "$ 50" -> "$50"
    private val SPACE_AFTER_CURRENCY = Regex("([$€£¥₹])\\s+(\\d)")

    // Space after @ or # symbols: e.g. "@ user" -> "@user", "# tag" -> "#tag"
    private val SPACE_AFTER_HANDLE_OR_HASH = Regex("([@#])\\s+([a-zA-Z0-9_])")

    // Missing space after semicolon followed by alphanumeric
    private val MISSING_SPACE_AFTER_SEMICOLON = Regex(";([\\p{L}\\p{N}])")

    // Missing space after comma followed by letter, or comma preceded by non-digit followed by digit.
    // Preserves numeric grouping and decimals like "1,000", "1,500,000", or "3,14".
    private val MISSING_SPACE_AFTER_COMMA = Regex("(?<!\\d),([\\p{L}\\p{N}])|(?<=\\d),(\\p{L})")

    // Colon immediately followed by letter or digit (filtered in normalizeColons)
    private val COLON_BEFORE_ALNUM = Regex(":(?=[\\p{L}\\p{N}])")

    // Missing space after sentence punctuation followed by alphanumeric (excluding dot)
    private val MISSING_SPACE_AFTER_SENTENCE_PUNCT = Regex("([?!])([\\p{L}\\p{N}])")

    private val URI_SCHEMES = setOf(
        "http", "https", "mailto", "tel", "ftp", "ftps", "ssh", "git", "file", "data", "urn", "ws", "wss"
    )

    private val ABBREVIATIONS = setOf(
        "e.g", "i.e", "etc", "vs", "al", "approx", "dept", "fig", "inc", "jr", "sr",
        "no", "vol", "mr", "mrs", "ms", "dr", "prof", "st", "a.m", "p.m",
        "co", "corp", "ltd", "ave", "rd", "blvd", "apt", "univ", "est"
    )

    private val MULTIPLE_HORIZONTAL_SPACES = Regex("[ \\t]{2,}")
    private val SENTENCE_START_REGEX = Regex("([.!?]\\s+)(\\p{Ll})")
    private val NEWLINE_START_REGEX = Regex("(\\n\\s*)(\\p{Ll})")

    fun normalize(text: String): String {
        if (text.isBlank()) return text

        var result = text

        // 1. Collapse multiple horizontal spaces (preserve newlines)
        result = MULTIPLE_HORIZONTAL_SPACES.replace(result, " ")

        // 2. Remove unwanted spaces before punctuation and closing brackets
        result = SPACE_BEFORE_PUNCTUATION.replace(result) { match -> match.groupValues[1] }
        result = SPACE_BEFORE_PERIOD.replace(result, ".")

        // 3. Remove spaces inside opening brackets
        result = SPACE_AFTER_OPENING_BRACKET.replace(result) { match -> match.groupValues[1] }

        // 4. Remove spaces inside quotation marks
        result = SPACE_AFTER_OPENING_QUOTE.replace(result) { match ->
            match.groupValues[1] + "\"" + match.groupValues[2]
        }
        result = SPACE_BEFORE_CLOSING_QUOTE.replace(result) { match ->
            match.groupValues[1] + "\"" + match.groupValues[2]
        }

        // 5. Remove space between currency/symbols and their operand
        result = SPACE_AFTER_CURRENCY.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2]
        }
        result = SPACE_AFTER_HANDLE_OR_HASH.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2]
        }

        // 6. Ensure single space after punctuation when followed by alphanumeric
        result = MISSING_SPACE_AFTER_SEMICOLON.replace(result) { match ->
            "; " + match.groupValues[1]
        }
        result = MISSING_SPACE_AFTER_COMMA.replace(result) { match ->
            val after = match.groupValues[1].ifEmpty { match.groupValues[2] }
            ", $after"
        }
        result = normalizeColons(result)
        result = MISSING_SPACE_AFTER_SENTENCE_PUNCT.replace(result) { match ->
            match.groupValues[1] + " " + match.groupValues[2]
        }

        // 7. Sentence capitalization after internal sentence boundaries and newlines
        result = capitalizeInternalSentences(result)

        return result
    }

    private fun normalizeColons(text: String): String {
        return COLON_BEFORE_ALNUM.replace(text) { match ->
            val idx = match.range.first
            val prevChar = if (idx > 0) text[idx - 1] else null
            val nextChar = if (idx + 1 < text.length) text[idx + 1] else null

            // Preserve double colons (e.g. std::vector)
            if (prevChar == ':' || nextChar == ':') return@replace ":"

            // Preserve times, ratios, and IPv4:port (digit:digit)
            if (prevChar != null && prevChar.isDigit() && nextChar != null && nextChar.isDigit()) {
                return@replace ":"
            }

            // Inspect the surrounding whitespace-delimited token to preserve URLs and schemes
            var tokenStart = idx
            while (tokenStart > 0 && !text[tokenStart - 1].isWhitespace()) tokenStart--
            var tokenEnd = idx + 1
            while (tokenEnd < text.length && !text[tokenEnd].isWhitespace()) tokenEnd++
            val token = text.substring(tokenStart, tokenEnd)
            val beforeInToken = text.substring(tokenStart, idx).trimStart('(', '[', '{', '"', '\'').lowercase()
            val afterInToken = text.substring(idx + 1, tokenEnd)

            // Preserve URI schemes (e.g. mailto:user@example.com, tel:+15551234)
            if (beforeInToken in URI_SCHEMES) return@replace ":"

            // Preserve host:port inside URLs or dotted hostnames / localhost
            val startsWithPort = afterInToken.isNotEmpty() &&
                afterInToken.takeWhile { it.isDigit() }.isNotEmpty() &&
                afterInToken.dropWhile { it.isDigit() }.let { it.isEmpty() || it.startsWith("/") || it.startsWith("?") || it.startsWith("#") || it[0] in setOf('.', ',', ')', ']', '}') }
            if (startsWithPort && (token.contains("://") || beforeInToken == "localhost" || beforeInToken.contains('.'))) {
                return@replace ":"
            }

            ": "
        }
    }

    private fun capitalizeInternalSentences(text: String): String {
        if (text.isEmpty()) return text

        var res = SENTENCE_START_REGEX.replace(text) { match ->
            val punct = match.groupValues[1]
            val letter = match.groupValues[2]
            if (punct.startsWith(".")) {
                val matchIndex = match.range.first
                val preceding = text.substring(0, matchIndex).trimEnd()
                val lastWord = preceding.split(Regex("\\s+")).lastOrNull()?.lowercase() ?: ""
                val cleanWord = lastWord.trim('(', '[', '{', '"', '\'', '“', '‘', '”', '’')
                val normalized = cleanWord.trimEnd('.')

                val isSingleLetter = normalized.length == 1 && normalized[0].isLowerCase()
                val isAbbrev = cleanWord in ABBREVIATIONS ||
                    normalized in ABBREVIATIONS ||
                    cleanWord.endsWith(".g") ||
                    cleanWord.endsWith(".e")

                if (isSingleLetter || isAbbrev) {
                    return@replace match.value
                }
            }
            match.groupValues[1] + letter.uppercase()
        }

        res = NEWLINE_START_REGEX.replace(res) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase()
        }

        return res
    }
}
