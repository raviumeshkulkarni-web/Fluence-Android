package com.groq.voicetyper.autolearn.domain

data class CorrectionCandidate(
    val spokenText: String,
    val correctedText: String
)

object WordLcsExtractor {

    private const val MAX_TEXT_CHARS = 10_000
    private const val MAX_WORDS = 1_000

    /**
     * Extracts high-confidence word-level corrections by comparing committed text vs user edited text.
     */
    fun extractCorrections(committedText: String, editedText: String): List<CorrectionCandidate> {
        if (committedText.isBlank() || editedText.isBlank() || committedText == editedText) {
            return emptyList()
        }

        if (committedText.length > MAX_TEXT_CHARS || editedText.length > MAX_TEXT_CHARS) {
            return emptyList()
        }

        val committedWords = tokenize(committedText)
        val editedWords = tokenize(editedText)

        if (committedWords.isEmpty() || editedWords.isEmpty() ||
            committedWords.size > MAX_WORDS || editedWords.size > MAX_WORDS
        ) {
            return emptyList()
        }

        val substitutions = findSubstitutions(committedWords, editedWords)

        // If > 50% of words changed, treat as a complete rewrite, not word-level corrections
        if (substitutions.size > committedWords.size / 2) {
            return emptyList()
        }

        val candidates = mutableListOf<CorrectionCandidate>()
        val seen = mutableSetOf<String>()

        for ((original, corrected) in substitutions) {
            val key = "$original->$corrected"
            if (!seen.add(key)) continue

            if (isValidCorrection(original, corrected)) {
                candidates.add(CorrectionCandidate(spokenText = original, correctedText = corrected))
            }
        }

        return candidates
    }

    private fun tokenize(text: String): List<String> {
        return text.trim()
            .split("\\s+".toRegex())
            .map { stripPunctuation(it) }
            .filter { it.isNotEmpty() }
    }

    private fun stripPunctuation(word: String): String {
        return word.replace("^[^\\w\\d]+|[^\\w\\d]+$".toRegex(), "")
    }

    private fun findSubstitutions(original: List<String>, edited: List<String>): List<Pair<String, String>> {
        val m = original.size
        val n = edited.size
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 1..m) {
            for (j in 1..n) {
                if (original[i - 1] == edited[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                } else {
                    dp[i][j] = maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }

        // Backtrack to find substitutions
        val substitutions = mutableListOf<Pair<String, String>>()
        var i = m
        var j = n

        while (i > 0 && j > 0) {
            if (original[i - 1] == edited[j - 1]) {
                i--
                j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                if (dp[i - 1][j] == dp[i][j - 1] && i > 0 && j > 0) {
                    substitutions.add(original[i - 1] to edited[j - 1])
                    i--
                    j--
                } else {
                    i--
                }
            } else {
                j--
            }
        }

        return substitutions.reversed()
    }

    private fun isValidCorrection(original: String, corrected: String): Boolean {
        if (original == corrected) return false
        if (original.length < 2 && corrected.length < 2) return false

        // Exclude pure numbers unless both are numbers
        val origIsNum = original.all { it.isDigit() }
        val corrIsNum = corrected.all { it.isDigit() }
        if (origIsNum != corrIsNum) return false

        return true
    }
}
