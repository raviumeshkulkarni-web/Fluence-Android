package com.groq.voicetyper.snippets

import android.content.Context
import android.util.Log

/**
 * Deterministic text-in/text-out expansion of the final transcript.
 *
 * Pure core ([expand]) knows nothing about recording, STT engines, injection,
 * IME, accessibility, databases, or apps — it only maps a transcript plus a
 * list of [Snippet]s to a new transcript.
 *
 * Fail-safe: [process] never throws and never blocks transcript delivery. Any
 * failure returns the original [rawText] unchanged.
 */
object SnippetProcessor {
    private const val TAG = "SnippetProcessor"

    /**
     * Guarded entry point used by the post-processing seam. Reads the enabled
     * flag and stored snippets from [SnippetPreferences].
     *
     * Exception policy (narrowest appropriate):
     * - `Exception`: every realistic failure of this feature (matcher index
     *   bugs, JSON decode/schema drift, malformed user data). Mirrors the
     *   per-rule catch in DictionaryTextPostProcessor.
     * - `OutOfMemoryError`: the only `Error` reachable in this bounded,
     *   non-recursive scan. The fallback `return rawText` allocates nothing,
     *   so capturing it here genuinely preserves the "original transcript is
     *   delivered" invariant. Other errors (StackOverflowError is
     *   structurally impossible here; AssertionError, LinkageError, ...)
     *   are VM/programmer failures unrelated to this feature and are not
     *   captured.
     */
    fun process(context: Context, rawText: String): String {
        if (rawText.isBlank()) return rawText
        if (!SnippetPreferences.isSnippetsEnabled(context)) return rawText
        return try {
            expand(rawText, SnippetPreferences.loadSnippets(context))
        } catch (e: Exception) {
            Log.w(TAG, "Snippet expansion failed; delivering original transcript", e)
            rawText
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Snippet expansion ran out of memory; delivering original transcript", e)
            rawText
        }
    }

    /**
     * Applies all snippets to [rawText]:
     *
     * - exact phrase matching, case-insensitive via an unconditional
     *   (locale-independent) case fold on both sides,
     * - word/phrase boundaries (the character before/after the trigger must be
     *   absent or a non-letter/digit; punctuation is therefore a valid
     *   boundary and is preserved),
     * - when several snippets match at the same position the longest trigger
     *   wins,
     * - all non-overlapping occurrences in one pass are replaced,
     * - replacements are applied right-to-left so offsets stay valid,
     * - expansion text is never re-scanned (no cascading expansion).
     *
     * Unicode note: matching uses per-code-point folding so astral-plane
     * characters (emoji, CJK, Cyrillic, ...) are handled correctly, including
     * word-boundary decisions. Only the character-level unconditional case
     * mapping is used (e.g. the Turkish dotted I is not lowercased like a
     * locale would); this is deterministic and documented as a V1 limitation.
     *
     * Invalid snippets (blank trigger or expansion) are skipped silently.
     */
    fun expand(rawText: String, snippets: List<Snippet>): String {
        if (rawText.isBlank() || snippets.isEmpty()) return rawText

        val matches = ArrayList<Match>()
        for (snippet in snippets) {
            val trigger = snippet.trigger.trim()
            if (trigger.isEmpty() || snippet.expansion.isBlank()) continue
            collectMatches(rawText, snippet, trigger, matches)
        }
        if (matches.isEmpty()) return rawText

        // Longest trigger wins at each position, then keep non-overlapping
        // matches greedily (also drops the shorter sibling sharing a start).
        matches.sortWith(compareBy<Match> { it.start }.thenByDescending { it.end })
        val chosen = ArrayList<Match>()
        var lastEnd = 0
        for (match in matches) {
            if (match.start >= lastEnd) {
                chosen.add(match)
                lastEnd = match.end
            }
        }
        if (chosen.isEmpty()) return rawText

        // Right-to-left application keeps every splice offset valid against
        // the original string; expansion text is inserted verbatim.
        val result = StringBuilder(rawText)
        for (match in chosen.asReversed()) {
            result.replace(match.start, match.end, match.snippet.expansion)
        }
        return result.toString()
    }

    private fun collectMatches(
        rawText: String,
        snippet: Snippet,
        trigger: String,
        out: MutableList<Match>
    ) {
        val firstCodePoint = trigger.codePointAt(0)
        var i = 0
        while (i < rawText.length) {
            val textCodePoint = rawText.codePointAt(i)
            if (fold(textCodePoint) == fold(firstCodePoint)) {
                val end = matchAt(rawText, trigger, i)
                if (end > 0 && isWordBoundary(rawText, i, end)) {
                    out.add(Match(snippet, i, end))
                    i = end
                    continue
                }
            }
            i += Character.charCount(textCodePoint)
        }
    }

    /** Returns the index just past a fold-equal match at [start], or -1. */
    private fun matchAt(rawText: String, trigger: String, start: Int): Int {
        var i = start
        var j = 0
        while (j < trigger.length) {
            if (i >= rawText.length) return -1
            val triggerCodePoint = trigger.codePointAt(j)
            val textCodePoint = rawText.codePointAt(i)
            if (fold(textCodePoint) != fold(triggerCodePoint)) return -1
            j += Character.charCount(triggerCodePoint)
            i += Character.charCount(textCodePoint)
        }
        return i
    }

    /** True when the span [start, end) is delimited by non-word characters. */
    private fun isWordBoundary(rawText: String, start: Int, end: Int): Boolean {
        val beforeOk = start == 0 || !Character.isLetterOrDigit(rawText.codePointBefore(start))
        val afterOk = end >= rawText.length || !Character.isLetterOrDigit(rawText.codePointAt(end))
        return beforeOk && afterOk
    }

    private fun fold(codePoint: Int): Int = Character.toLowerCase(codePoint)

    private data class Match(val snippet: Snippet, val start: Int, val end: Int)
}