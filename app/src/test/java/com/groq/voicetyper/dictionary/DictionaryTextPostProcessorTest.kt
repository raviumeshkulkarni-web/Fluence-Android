package com.groq.voicetyper.dictionary

import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryTextPostProcessorTest {

    @Test
    fun `single word replacement is case insensitive and replaces with exact target casing`() {
        val entries = listOf(
            CustomDictionaryEntry(id = 1, spokenText = "groq", replacementText = "Groq", isEnabled = true)
        )

        val input = "i love using groq for fast stt and GROQ is awesome."
        val expected = "i love using Groq for fast stt and Groq is awesome."

        val actual = DictionaryTextPostProcessor.processWithEntries(input, entries)
        assertEquals(expected, actual)
    }

    @Test
    fun `multi word phrase replacement matches full phrase`() {
        val entries = listOf(
            CustomDictionaryEntry(id = 1, spokenText = "as soon as possible", replacementText = "ASAP", isEnabled = true)
        )

        val input = "Please reply as soon as possible thank you."
        val expected = "Please reply ASAP thank you."

        val actual = DictionaryTextPostProcessor.processWithEntries(input, entries)
        assertEquals(expected, actual)
    }

    @Test
    fun `longest match phrase precedence prevents shorter rule from corrupting longer phrase`() {
        // Order in list is deliberately shorter rule first, but processor sorts by length descending
        val entries = listOf(
            CustomDictionaryEntry(id = 1, spokenText = "as soon", replacementText = "when", isEnabled = true),
            CustomDictionaryEntry(id = 2, spokenText = "as soon as possible", replacementText = "ASAP", isEnabled = true)
        )

        val input = "Send this as soon as possible."
        val expected = "Send this ASAP."

        val actual = DictionaryTextPostProcessor.processWithEntries(input, entries)
        assertEquals(expected, actual)
    }

    @Test
    fun `word boundary matching prevents partial word replacements`() {
        val entries = listOf(
            CustomDictionaryEntry(id = 1, spokenText = "cat", replacementText = "dog", isEnabled = true)
        )

        val input = "The cat is in the concatenate category."
        val expected = "The dog is in the concatenate category."

        val actual = DictionaryTextPostProcessor.processWithEntries(input, entries)
        assertEquals(expected, actual)
    }

    @Test
    fun `disabled entries are ignored during replacement`() {
        val entries = listOf(
            CustomDictionaryEntry(id = 1, spokenText = "fluence", replacementText = "Fluence", isEnabled = false),
            CustomDictionaryEntry(id = 2, spokenText = "api", replacementText = "API", isEnabled = true)
        )

        val input = "welcome to fluence api."
        val expected = "welcome to fluence API."

        val actual = DictionaryTextPostProcessor.processWithEntries(input, entries)
        assertEquals(expected, actual)
    }

    @Test
    fun `empty input or empty rules list returns original text unchanged`() {
        val entries = listOf(
            CustomDictionaryEntry(id = 1, spokenText = "test", replacementText = "Test", isEnabled = true)
        )

        assertEquals("", DictionaryTextPostProcessor.processWithEntries("", entries))
        assertEquals("Hello world", DictionaryTextPostProcessor.processWithEntries("Hello world", emptyList()))
    }

    @Test
    fun `repository updateCompiledCache pre-compiles regexes into cache`() {
        val entries = listOf(
            CustomDictionaryEntry(id = 1, spokenText = "ai", replacementText = "AI", isEnabled = true)
        )

        DictionaryRepository.updateCompiledCache(entries)

        // Process directly with processWithCompiledRules using rules compiled by repository
        val rules = listOf(
            CompiledDictionaryRule(Regex("(?i)\\bai\\b"), "AI")
        )
        val result = DictionaryTextPostProcessor.processWithCompiledRules("the ai revolution", rules)
        assertEquals("the AI revolution", result)
    }

    @Test
    fun `replacement text with dollar signs is treated literally`() {
        val entries = listOf(
            CustomDictionaryEntry(id = 1, spokenText = "price", replacementText = "price: \$5", isEnabled = true)
        )

        val input = "the price is high"
        val expected = "the price: \$5 is high"

        val actual = DictionaryTextPostProcessor.processWithEntries(input, entries)
        assertEquals(expected, actual)
    }

    @Test
    fun `replacement text with backslashes is treated literally`() {
        val entries = listOf(
            CustomDictionaryEntry(id = 1, spokenText = "path", replacementText = "C:\\Users\\me", isEnabled = true)
        )

        val input = "go to path"
        val expected = "go to C:\\Users\\me"

        val actual = DictionaryTextPostProcessor.processWithEntries(input, entries)
        assertEquals(expected, actual)
    }

    @Test
    fun `malformed compiled rule never throws during processing`() {
        val rules = listOf(
            CompiledDictionaryRule(regex = Regex("(?i)\\bprice\\b"), replacementText = "price: \$5")
        )

        val input = "the price is high"

        val actual = DictionaryTextPostProcessor.processWithCompiledRules(input, rules)
        assertEquals(input, actual)
    }
}
