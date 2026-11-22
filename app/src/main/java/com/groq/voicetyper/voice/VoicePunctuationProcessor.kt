package com.groq.voicetyper.voice

/**
 * Deterministic spoken punctuation and symbols converter.
 *
 * The word tables follow wmkeyboard's behavior: a phrase is matched by its
 * core word (so "Period." and "line," still name a mark), a mark replaces a
 * recognizer's guessed punctuation immediately before it, and the language
 * table is chosen from the dictation language. A language without a table is
 * returned verbatim.
 *
 * English also keeps Fluence's noun/verb guards: "a comma" and "the period of
 * time" stay words instead of turning into marks.
 */
object VoicePunctuationProcessor {

    private enum class Join {
        AFTER,
        BEFORE,
        BOTH,
        APART
    }

    private class Rule(
        val phrase: String,
        val symbol: String,
        val join: Join,
        val guarded: Boolean = true
    ) {
        val words: List<String> = phrase.split(' ').filter { it.isNotEmpty() }
    }

    private fun rule(phrase: String, symbol: String, join: Join, guarded: Boolean = true) =
        Rule(phrase, symbol, join, guarded)

    private fun rules(vararg entries: Rule): List<Rule> =
        entries.sortedByDescending { it.words.size }

    private fun paired(
        open: String,
        close: String,
        names: List<String>,
        opens: List<String> = listOf("open", "left", "begin", "start"),
        closes: List<String> = listOf("close", "right", "end")
    ): Array<Rule> =
        (names.flatMap { name -> opens.map { rule("$it $name", open, Join.BEFORE, guarded = false) } } +
            names.flatMap { name -> closes.map { rule("$it $name", close, Join.AFTER, guarded = false) } })
            .toTypedArray()

    private val NOUN_DETERMINERS = setOf(
        "a", "an", "the", "this", "that", "these", "those", "each", "every",
        "my", "your", "his", "her", "our", "their"
    )

    private val VERB_PREDECESSORS = setOf(
        "to", "will", "would", "must", "can", "could", "should", "shall", "may", "might",
        "further", "clearly", "help", "helps", "does", "did", "do", "not", "and", "or", "also"
    )

    private val TIME_PERIOD_MODIFIERS = setOf(
        "trial", "grace", "waiting", "time", "brief", "long", "short", "same", "first",
        "second", "third", "extended", "school", "class", "pay", "probationary", "rest",
        "gestation", "incubation", "accounting", "reporting", "transitional", "cooling",
        "during", "throughout", "over", "within"
    )

    private val PREPOSITIONS = setOf(
        "of", "for", "in", "from", "with", "between", "after", "before", "during", "to"
    )

    private val PREPOSITION_GUARDED = setOf("period", "full stop", "new line", "newline", "new paragraph")

    private val ENGLISH = rules(
        rule("new paragraph", "\n\n", Join.BOTH),
        rule("new line", "\n", Join.BOTH),
        rule("newline", "\n", Join.BOTH),
        rule("question mark", "?", Join.AFTER),
        rule("exclamation mark", "!", Join.AFTER),
        rule("exclamation point", "!", Join.AFTER),
        rule("full stop", ".", Join.AFTER),
        rule("period", ".", Join.AFTER),
        rule("comma", ",", Join.AFTER),
        rule("colon", ":", Join.AFTER),
        rule("semicolon", ";", Join.AFTER),
        rule("ellipsis", "…", Join.AFTER),
        rule("dot dot dot", "…", Join.AFTER),
        rule("apostrophe", "'", Join.BOTH),
        rule("hyphen", "-", Join.BOTH),
        rule("underscore", "_", Join.BOTH),
        rule("dash", "-", Join.APART),
        rule("en dash", "–", Join.BOTH),
        rule("em dash", "—", Join.BOTH),
        rule("forward slash", "/", Join.BOTH),
        rule("slash", "/", Join.BOTH),
        rule("backslash", "\\", Join.BOTH),
        rule("back slash", "\\", Join.BOTH),
        rule("at sign", "@", Join.BOTH),
        rule("hashtag", "#", Join.BEFORE),
        rule("hash tag", "#", Join.BEFORE),
        rule("hash sign", "#", Join.BEFORE),
        rule("pound sign", "#", Join.BEFORE),
        rule("dollar sign", "$", Join.BEFORE),
        rule("percent sign", "%", Join.AFTER),
        rule("degree sign", "°", Join.AFTER),
        rule("ampersand", "&", Join.APART),
        rule("asterisk", "*", Join.AFTER),
        rule("plus sign", "+", Join.APART),
        rule("equals sign", "=", Join.APART),
        rule("greater than sign", ">", Join.APART),
        rule("less than sign", "<", Join.APART),
        rule("vertical bar", "|", Join.APART),
        rule("tilde", "~", Join.BEFORE),
        rule("caret", "^", Join.BOTH),
        rule("copyright sign", "©", Join.AFTER),
        rule("registered sign", "®", Join.AFTER),
        rule("trademark sign", "™", Join.AFTER),
        *paired("\"", "\"", listOf("quote", "quotes"), opens = listOf("open", "begin", "start"), closes = listOf("close", "end")),
        rule("unquote", "\"", Join.AFTER, guarded = false),
        *paired("(", ")", listOf("parenthesis", "parentheses", "paren")),
        *paired("[", "]", listOf("bracket", "brackets", "square bracket", "square brackets")),
        *paired("{", "}", listOf("brace", "braces", "curly brace", "curly braces", "curly bracket", "curly brackets")),
        rule("dot com", ".com", Join.AFTER, guarded = false),
        rule("dot org", ".org", Join.AFTER, guarded = false),
        rule("dot net", ".net", Join.AFTER, guarded = false),
        rule("dot io", ".io", Join.AFTER, guarded = false)
    )

    private val BENGALI = rules(
        rule("নতুন প্যারা", "\n\n", Join.BOTH, guarded = false),
        rule("নতুন লাইন", "\n", Join.BOTH, guarded = false),
        rule("প্রশ্নবোধক চিহ্ন", "?", Join.AFTER, guarded = false),
        rule("প্রশ্নবোধক", "?", Join.AFTER, guarded = false),
        rule("বিস্ময়সূচক চিহ্ন", "!", Join.AFTER, guarded = false),
        rule("বিস্ময়বোধক", "!", Join.AFTER, guarded = false),
        rule("দাঁড়ি", "।", Join.AFTER, guarded = false),
        rule("কমা", ",", Join.AFTER, guarded = false),
        rule("কোলন", ":", Join.AFTER, guarded = false),
        rule("সেমিকোলন", ";", Join.AFTER, guarded = false)
    )

    private val PORTUGUESE = rules(
        rule("novo parágrafo", "\n\n", Join.BOTH, guarded = false),
        rule("nova linha", "\n", Join.BOTH, guarded = false),
        rule("ponto de interrogação", "?", Join.AFTER, guarded = false),
        rule("interrogação", "?", Join.AFTER, guarded = false),
        rule("ponto de exclamação", "!", Join.AFTER, guarded = false),
        rule("exclamação", "!", Join.AFTER, guarded = false),
        rule("ponto final", ".", Join.AFTER, guarded = false),
        rule("ponto e vírgula", ";", Join.AFTER, guarded = false),
        rule("vírgula", ",", Join.AFTER, guarded = false),
        rule("dois pontos", ":", Join.AFTER, guarded = false),
        rule("reticências", "…", Join.AFTER, guarded = false),
        rule("abre aspas", "\"", Join.BEFORE, guarded = false),
        rule("fecha aspas", "\"", Join.AFTER, guarded = false),
        rule("abre parênteses", "(", Join.BEFORE, guarded = false),
        rule("fecha parênteses", ")", Join.AFTER, guarded = false),
        rule("hífen", "-", Join.BOTH, guarded = false),
        rule("travessão", "—", Join.APART, guarded = false),
        rule("arroba", "@", Join.BOTH, guarded = false)
    )

    private val SPANISH = rules(
        rule("nuevo párrafo", "\n\n", Join.BOTH, guarded = false),
        rule("nueva línea", "\n", Join.BOTH, guarded = false),
        rule("signo de interrogación", "?", Join.AFTER, guarded = false),
        rule("signo de exclamación", "!", Join.AFTER, guarded = false),
        rule("punto final", ".", Join.AFTER, guarded = false),
        rule("punto y coma", ";", Join.AFTER, guarded = false),
        rule("coma", ",", Join.AFTER, guarded = false),
        rule("dos puntos", ":", Join.AFTER, guarded = false),
        rule("puntos suspensivos", "…", Join.AFTER, guarded = false),
        rule("abrir comillas", "\"", Join.BEFORE, guarded = false),
        rule("cerrar comillas", "\"", Join.AFTER, guarded = false),
        rule("abrir paréntesis", "(", Join.BEFORE, guarded = false),
        rule("cerrar paréntesis", ")", Join.AFTER, guarded = false),
        rule("guion", "-", Join.BOTH, guarded = false),
        rule("arroba", "@", Join.BOTH, guarded = false)
    )

    private const val TRAILING = ".,!?;:…"
    private const val LEADING = "¿¡"

    private val SENTENCE_MARKS = setOf(".", ",", "?", "!", ":", ";", "…", "।")

    private fun tableFor(languageTag: String): List<Rule>? {
        val tag = languageTag.trim().lowercase()
        return when {
            tag.isEmpty() || tag == "auto" || tag.startsWith("en") -> ENGLISH
            tag.startsWith("bn") -> BENGALI
            tag.startsWith("pt") -> PORTUGUESE
            tag.startsWith("es") -> SPANISH
            else -> null
        }
    }

    private fun core(word: String): String =
        word.trimStart { it in LEADING }.trimEnd { it in TRAILING }

    /**
     * Converts spoken punctuation commands in [text] for [languageTag].
     * Returns [text] verbatim when [enabled] is false, or when the language
     * has no spoken-punctuation table.
     */
    fun process(
        text: String,
        enabled: Boolean = true,
        languageTag: String = "en"
    ): String {
        if (!enabled || text.isBlank()) return text
        val table = tableFor(languageTag) ?: return text

        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return text
        val cores = words.map(::core)
        val sb = StringBuilder()
        var tight = true
        var guessAt = -1
        var i = 0

        outer@ while (i < words.size) {
            for (rule in table) {
                val phrase = rule.words
                if (i + phrase.size <= words.size &&
                    phrase.indices.all { cores[i + it].equals(phrase[it], ignoreCase = true) }
                ) {
                    if (isGuarded(rule, i, cores, languageTag)) {
                        if (!tight) sb.append(' ')
                        sb.append(words.subList(i, i + phrase.size).joinToString(" "))
                        tight = false
                        guessAt = tailStart(sb, words[i + phrase.size - 1])
                        i += phrase.size
                        continue@outer
                    }

                    if (guessAt >= 0 && rule.symbol in SENTENCE_MARKS) {
                        sb.setLength(guessAt)
                        while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\t')) {
                            sb.setLength(sb.length - 1)
                        }
                    }

                    val spaceBefore = rule.join == Join.BEFORE || rule.join == Join.APART
                    if (spaceBefore && !tight) sb.append(' ')
                    sb.append(rule.symbol)
                    tight = rule.join == Join.BEFORE || rule.join == Join.BOTH
                    guessAt = -1
                    i += phrase.size
                    continue@outer
                }
            }

            appendPlainWord(sb, words[i], tight)
            tight = false
            guessAt = tailStart(sb, words[i])
            i++
        }

        return sb.toString()
    }

    private fun appendPlainWord(sb: StringBuilder, word: String, tight: Boolean) {
        if (!tight) sb.append(' ')
        sb.append(word)
    }

    private fun tailStart(sb: StringBuilder, word: String): Int {
        val tail = word.length - word.trimEnd { it in TRAILING }.length
        return if (tail in 1..word.length) sb.length - tail else -1
    }

    private fun isGuarded(
        rule: Rule,
        index: Int,
        cores: List<String>,
        languageTag: String
    ): Boolean {
        if (!rule.guarded || !languageTag.trim().lowercase().startsWith("en")) return false
        val previous = cores.getOrNull(index - 1)?.lowercase()
        val next = cores.getOrNull(index + rule.words.size)?.lowercase()

        if (previous in NOUN_DETERMINERS) return true
        if (rule.phrase == "underscore" &&
            (previous in VERB_PREDECESSORS || next in NOUN_DETERMINERS)
        ) {
            return true
        }
        if (rule.phrase == "period" && previous in TIME_PERIOD_MODIFIERS) return true
        if (rule.phrase in PREPOSITION_GUARDED && next in PREPOSITIONS) return true
        return false
    }
}
