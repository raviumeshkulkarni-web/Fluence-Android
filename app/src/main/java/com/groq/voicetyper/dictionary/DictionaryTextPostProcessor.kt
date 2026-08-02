package com.groq.voicetyper.dictionary

import android.content.Context
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import java.util.regex.Matcher

object DictionaryTextPostProcessor {

    /**
     * Applies custom dictionary replacements to [rawText].
     * Zero DB queries and zero Regex allocation during transcription.
     * Pre-compiled Regex rules are fetched from in-memory AtomicReference cache.
     */
    fun process(context: Context, rawText: String): String {
        if (rawText.isBlank()) return rawText

        if (!DictionaryPreferences.isDictionaryEnabled(context)) {
            return rawText
        }

        val compiledRules = DictionaryRepository.getCompiledRules(context)
        return processWithCompiledRules(rawText, compiledRules)
    }

    /**
     * Execution engine using pre-compiled Regex rules.
     * Lock-free, zero heap allocation for Regex patterns during execution.
     */
    fun processWithCompiledRules(rawText: String, rules: List<CompiledDictionaryRule>): String {
        if (rawText.isBlank() || rules.isEmpty()) return rawText

        var result = rawText
        for (rule in rules) {
            result = try {
                rule.regex.replace(result, rule.replacementText)
            } catch (_: Exception) {
                result
            }
        }
        return result
    }

    /**
     * Helper for testing or direct entry lists (compiles rules on-the-fly).
     */
    fun processWithEntries(rawText: String, entries: List<CustomDictionaryEntry>): String {
        if (rawText.isBlank() || entries.isEmpty()) return rawText

        val compiledRules = entries
            .filter { it.isEnabled && it.spokenText.isNotBlank() }
            .sortedByDescending { it.spokenText.trim().length }
            .map { rule ->
                val escaped = Regex.escape(rule.spokenText.trim())
                CompiledDictionaryRule(
                    regex = Regex("(?i)\\b$escaped\\b"),
                    replacementText = Matcher.quoteReplacement(rule.replacementText)
                )
            }

        return processWithCompiledRules(rawText, compiledRules)
    }
}
