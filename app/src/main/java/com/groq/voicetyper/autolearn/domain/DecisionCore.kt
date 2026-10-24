package com.groq.voicetyper.autolearn.domain

import java.util.Locale
import java.util.regex.Pattern

/**
 * A learned spoken -> corrected candidate. Moved here (same package) from
 * the legacy WordLcsExtractor file; both legacy and live extractors emit it.
 */
data class CorrectionCandidate(
    val spokenText: String,
    val correctedText: String
)

/**
 * Kotlin port of the Windows Tauri decision core
 * (`src-tauri/src/auto_learn/decision_core.rs`), keeping extraction and
 * gating equivalent so a suggestion accepted on Windows is exactly what
 * Android would surface.
 *
 * Deliberate divergences (all Android-only operational caps, not behavior):
 *   - Android caps inputs at [MAX_TEXT_CHARS] chars / [MAX_WORDS] tokens;
 *     Windows applies no caps at this layer.
 *   - Tokenizer edge stripping uses `isLetterOrDigit` (Letter | Digit), the
 *     closest JVM equivalent of Rust `is_alphanumeric`;
 *     identical for the BMP Latin/Cyrillic text this app processes.
 *
 * Case handling: Windows compares with Rust `to_lowercase()` (locale
 * independent). Here all lowercasing goes through [lowercaseWord], i.e.
 * `String.lowercase(Locale.ROOT)`, never locale-default a-z checks.
 * This is intentional: the suggestion store's own original gate was
 * case-equal `String.equals`, which is why `groq` -> `Groq` previously
 * surfaced as a suggestion; the shared gates reject it (Windows-verified).
 */
object DecisionCore {

    const val MAX_TEXT_CHARS = 10_000
    const val MAX_WORDS = 1_000

    private const val SIMILARITY_FLOOR = 0.65
    private const val MIN_CORRECTED_CHARS = 3

    private fun lowercaseWord(word: String): String = word.lowercase(Locale.ROOT)

    // UNICODE_CHARACTER_CLASS so NBSP and other Unicode whitespace split
    // like Rust `split_whitespace`; plain "\s+" would leave them glued to
    // words. Still a JVM approximation of Rust `char::is_whitespace`.
    private val wordSplit: Pattern = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS)

    /**
     * Canonical pair identity (Windows `dictionary::canonical_entry_key`).
     * Trimmed, lowercased, NUL joined so the DB's exact-match unique index
     * can co-exist with case-insensitive pair semantics handled here.
     */
    fun canonicalPairKey(spoken: String, corrected: String): String {
        return lowercaseWord(spoken.trim()) + '\u0000' + lowercaseWord(corrected.trim())
    }

    /** Levenshtein edit distance between two strings. */
    fun editDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        val aChars = a.toCharArray()
        val bChars = b.toCharArray()
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (aChars[i - 1] == bChars[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
                }
            }
        }
        return dp[m][n]
    }

    /**
     * Tokenize text into words, stripping punctuation from edges
     * (`[^\p{L}\p{N}_]` per edge, matching Windows `tokenize`).
     */
    fun tokenize(text: String): List<String> {
        return wordSplit.split(text)
            .map { w -> w.trimEdges() }
            .filter { w -> w.isNotEmpty() }
    }

    private fun String.trimEdges(): String {
        var start = 0
        var end = length
        while (start < end && !(this[start].isLetterOrDigit() || this[start] == '_')) start++
        while (end > start && !(this[end - 1].isLetterOrDigit() || this[end - 1] == '_')) end--
        return substring(start, end)
    }

    /** Word-level LCS length between two token lists (rewrite guard seam). */
    fun lcsLength(origWords: List<String>, editedWords: List<String>): Int {
        val m = origWords.size
        val n = editedWords.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (lowercaseWord(origWords[i - 1]) == lowercaseWord(editedWords[j - 1])) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        return dp[m][n]
    }

    /**
     * Word-level LCS traceback: substitution pairs formed ONLY from
     * consecutive `[orig, null] + [null, edited]` alignment windows; ties
     * prefer the insertion branch (`dp[i][j-1] >= dp[i-1][j]`); the diagonal
     * is taken only when the words are equal (case-insensitive). The retired
     * Fluence tie rule (pairing two differing words on a raw dp tie) must
     * NOT come back - it manufactured `cool->stuff` / `was->agent` /
     * `it->Good` candidates on desktop.
     */
    fun findSubstitutions(origWords: List<String>, editedWords: List<String>): List<Pair<String, String>> {
        val m = origWords.size
        val n = editedWords.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (lowercaseWord(origWords[i - 1]) == lowercaseWord(editedWords[j - 1])) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        val aligned = ArrayList<Pair<String?, String?>>()
        var i = m
        var j = n
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 &&
                lowercaseWord(origWords[i - 1]) == lowercaseWord(editedWords[j - 1])
            ) {
                aligned.add(Pair(origWords[i - 1], editedWords[j - 1]))
                i--
                j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                aligned.add(Pair(null, editedWords[j - 1]))
                j--
            } else {
                aligned.add(Pair(origWords[i - 1], null))
                i--
            }
        }
        aligned.reverse()

        val subs = ArrayList<Pair<String, String>>()
        for (k in 0 until aligned.size - 1) {
            val (origWord, origEdit) = aligned[k]
            val (nextOrig, nextEdit) = aligned[k + 1]
            if (origWord != null && origEdit == null && nextOrig == null && nextEdit != null) {
                subs.add(Pair(origWord, nextEdit))
            }
        }
        return subs
    }

    fun isValidPair(
        spoken: String,
        corrected: String,
        knownPairKeys: Set<String>
    ): Boolean = invalidPairReason(spoken, corrected, knownPairKeys) == null

    /**
     * Debug-facing mirror of [isValidPair]: walks the gates in the SAME
     * order as Windows `invalid_pair_reason` and returns the first
     * failure's reason, or null when the pair passes every gate.
     *
     * Gate order (Windows parity):
     *   1. pair key already known,
     *   2. identical after lowercasing (case-only pairs skipped),
     *   3. corrected word shorter than [MIN_CORRECTED_CHARS] chars,
     *   4. `editDistance / maxLen > [SIMILARITY_FLOOR]`
     *      (floor 0.35 similarity: keeps `shunade` -> `Sinead`
     *      at dist 4/7, filters `stuck` -> `agent` and `do` -> `add`).
     */
    fun invalidPairReason(
        spoken: String,
        corrected: String,
        knownPairKeys: Set<String>
    ): String? {
        if (knownPairKeys.contains(canonicalPairKey(spoken, corrected))) {
            return "already in dictionary"
        }

        if (lowercaseWord(spoken) == lowercaseWord(corrected)) {
            return "case-only difference"
        }

        if (corrected.length < MIN_CORRECTED_CHARS) {
            return "corrected shorter than 3 chars"
        }

        val origLc = lowercaseWord(spoken)
        val corrLc = lowercaseWord(corrected)
        val dist = editDistance(origLc, corrLc)
        val maxLen = maxOf(origLc.length, corrLc.length)
        if (maxLen == 0) {
            return "empty pair"
        }

        if (dist.toDouble() / maxLen.toDouble() <= SIMILARITY_FLOOR) {
            return null
        }
        return "below similarity floor"
    }

    /**
     * Extract spoken -> corrected pair candidates (Windows
     * `extract_corrections`). Rewrites are rejected by TWO whole-input
     * guards before any pair is judged: LCS-coverage collapse, then the
     * >50% substitutions veto. Both exist because a mis-learned pair
     * rewrites future dictations via the custom dictionary.
     *
     * [knownPairKeys] is the account's current enabled dictionary reduced
     * to canonical pair keys (separate caller concern: computed once per
     * extraction batch, not per candidate).
     */
    fun extractCorrections(
        originalText: String,
        editedRegion: String,
        knownPairKeys: Set<String>
    ): List<CorrectionCandidate> {
        if (originalText.isEmpty() || editedRegion.isEmpty()) return emptyList()
        if (originalText == editedRegion) return emptyList()

        // Android-only extra (Windows has no caps at this layer).
        if (originalText.length > MAX_TEXT_CHARS || editedRegion.length > MAX_TEXT_CHARS) {
            return emptyList()
        }

        val origWords = tokenize(originalText)
        val editedWords = tokenize(editedRegion)
        if (origWords.isEmpty() || editedWords.isEmpty()) return emptyList()

        // Android-only extra (Windows has no caps at this layer).
        if (origWords.size > MAX_WORDS || editedWords.size > MAX_WORDS) {
            return emptyList()
        }

        // RC-5 rewrite guard: shared coverage collapsed.
        if (lcsLength(origWords, editedWords) * 2 < origWords.size) {
            return emptyList()
        }

        val subs = findSubstitutions(origWords, editedWords)

        // >50% of words changed: rewrite, not corrections.
        if (subs.size * 2 > origWords.size) {
            return emptyList()
        }

        val seenPairs = HashSet<String>()
        val results = ArrayList<CorrectionCandidate>()
        for ((origWord, correctedWord) in subs) {
            if (!isValidPair(origWord, correctedWord, knownPairKeys)) continue
            val pairKey = canonicalPairKey(origWord, correctedWord)
            if (!seenPairs.add(pairKey)) continue
            results.add(CorrectionCandidate(spokenText = origWord, correctedText = correctedWord))
        }
        return results
    }
}
