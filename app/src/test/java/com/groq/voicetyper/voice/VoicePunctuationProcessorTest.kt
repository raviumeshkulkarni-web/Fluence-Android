package com.groq.voicetyper.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoicePunctuationProcessorTest {

    @Test
    fun `converts basic spoken punctuation marks`() {
        val input = "Hello comma this is a test period How are you question mark"
        val expected = "Hello, this is a test. How are you?"
        assertEquals(expected, VoicePunctuationProcessor.process(input))
    }

    @Test
    fun `converts exclamation point and colon and semicolon`() {
        val input = "Warning exclamation mark Note colon item one semicolon item two"
        val expected = "Warning! Note: item one; item two"
        assertEquals(expected, VoicePunctuationProcessor.process(input))
    }

    @Test
    fun `converts newline and new paragraph`() {
        val input = "First line new line second line new paragraph next section"
        val expected = "First line\nsecond line\n\nnext section"
        assertEquals(expected, VoicePunctuationProcessor.process(input))
    }

    @Test
    fun `converts spoken symbols like at sign and hashtag and percent`() {
        val input = "Follow me at sign user hashtag trending with 50 percent sign off"
        val expected = "Follow me@user #trending with 50% off"
        assertEquals(expected, VoicePunctuationProcessor.process(input))
    }

    @Test
    fun `converts web domain phrases`() {
        val input = "Visit example dot com and info dot org"
        val expected = "Visit example.com and info.org"
        assertEquals(expected, VoicePunctuationProcessor.process(input))
    }

    @Test
    fun `preserves punctuation words when used as nouns preceded by determiners`() {
        val input = "I need a comma here because the period of time matters"
        val expected = "I need a comma here because the period of time matters"
        assertEquals(expected, VoicePunctuationProcessor.process(input))
    }

    @Test
    fun `preserves question mark when preceded by a determiner`() {
        val input = "Is there a question mark on that line"
        val expected = "Is there a question mark on that line"
        assertEquals(expected, VoicePunctuationProcessor.process(input))
    }

    @Test
    fun `preserves symbols when preceded by determiners`() {
        val input = "Draw a hyphen and an ampersand and a dollar sign"
        val expected = "Draw a hyphen and an ampersand and a dollar sign"
        assertEquals(expected, VoicePunctuationProcessor.process(input))

        val multiWord = "Use a back slash for paths and a forward slash for routes"
        assertEquals(multiWord, VoicePunctuationProcessor.process(multiWord))
    }

    @Test
    fun `converts spoken brackets and quotes cleanly without internal spaces`() {
        val input = "He said open quote hello close quote and open parenthesis note close parenthesis"
        val expected = "He said \"hello\" and (note)"
        assertEquals(expected, VoicePunctuationProcessor.process(input))
    }

    @Test
    fun `collapses duplicate ASR guessed punctuation before spoken command`() {
        val input = "Hello, comma how are you? Question mark"
        val expected = "Hello, how are you?"
        assertEquals(expected, VoicePunctuationProcessor.process(input))
    }

    @Test
    fun `empty or blank input returns as is`() {
        assertEquals("", VoicePunctuationProcessor.process(""))
        assertEquals("   ", VoicePunctuationProcessor.process("   "))
    }

    @Test
    fun `preserves newline in natural phrases like new line of products or our new line`() {
        val input1 = "The company launched a new line of products"
        assertEquals(input1, VoicePunctuationProcessor.process(input1))

        val input2 = "We are developing our new line of shoes"
        assertEquals(input2, VoicePunctuationProcessor.process(input2))

        val input3 = "Exploring that new line of inquiry"
        assertEquals(input3, VoicePunctuationProcessor.process(input3))
    }

    @Test
    fun `preserves period when used in time phrases or following adjectives`() {
        val input1 = "We had a trial period of two weeks"
        assertEquals(input1, VoicePunctuationProcessor.process(input1))

        val input2 = "During this period of economic growth"
        assertEquals(input2, VoicePunctuationProcessor.process(input2))

        val input3 = "A waiting period is required by law"
        assertEquals(input3, VoicePunctuationProcessor.process(input3))

        val input4 = "Over an extended period of time"
        assertEquals(input4, VoicePunctuationProcessor.process(input4))

        val input5 = "The grace period ended yesterday"
        assertEquals(input5, VoicePunctuationProcessor.process(input5))
    }

    @Test
    fun `preserves underscore when used as a verb`() {
        val input1 = "I want to underscore the importance of testing"
        assertEquals(input1, VoicePunctuationProcessor.process(input1))

        val input2 = "The findings will underscore our hypothesis"
        assertEquals(input2, VoicePunctuationProcessor.process(input2))

        val input3 = "We must underscore this critical issue"
        assertEquals(input3, VoicePunctuationProcessor.process(input3))
    }

    @Test
    fun `returns verbatim text when spoken punctuation is disabled`() {
        val input = "Hello comma this is a test period new line next section"
        assertEquals(input, VoicePunctuationProcessor.process(input, enabled = false))

        val naturalParagraph = "The company launched a new line for the holiday period and we underscore our commitment"
        assertEquals(naturalParagraph, VoicePunctuationProcessor.process(naturalParagraph, enabled = false))

        val naturalPunctuationWords = "Place a period at the end and a comma between items, or an underscore in identifiers"
        assertEquals(naturalPunctuationWords, VoicePunctuationProcessor.process(naturalPunctuationWords, enabled = false))
    }

    @Test
    fun `adversarial natural phrases with spoken punctuation enabled`() {
        // Redundant ASR-guessed punctuation with space before spoken command
        assertEquals(
            "Hello, how are you?",
            VoicePunctuationProcessor.process("Hello , comma how are you? Question mark", enabled = true)
        )

        // "dash of" preceded by determiner
        val dash = "Add a dash of salt to the recipe"
        assertEquals(dash, VoicePunctuationProcessor.process(dash, enabled = true))

        // "underscore" preceded by modal verb
        val modalUnderscore = "We must underscore how crucial this metric is"
        assertEquals(modalUnderscore, VoicePunctuationProcessor.process(modalUnderscore, enabled = true))

        // Spoken symbols conversion
        assertEquals(
            "The price is $50 with 15% discount; contact me@fluence.io",
            VoicePunctuationProcessor.process(
                "The price is dollar sign 50 with 15 percent sign discount semicolon contact me at sign fluence dot io",
                enabled = true
            )
        )
    }

    @Test
    fun `spoken command replaces recognizer punctuation and consumes trailing punctuation`() {
        assertEquals("done. And then", VoicePunctuationProcessor.process("done Period. And then"))
        assertEquals("one\ntwo", VoicePunctuationProcessor.process("one new line, two"))
        assertEquals("see (this) now", VoicePunctuationProcessor.process("see open parenthesis, this close parenthesis. now"))
        assertEquals("really?", VoicePunctuationProcessor.process("really. question mark"))
        assertEquals("done.\nnext", VoicePunctuationProcessor.process("done. new line next"))
    }

    @Test
    fun `supports full wm english symbol set and paired names`() {
        assertEquals("well-known", VoicePunctuationProcessor.process("well hyphen known"))
        assertEquals("and/or", VoicePunctuationProcessor.process("and slash or"))
        assertEquals("snake_case", VoicePunctuationProcessor.process("snake underscore case"))
        assertEquals("salt & pepper", VoicePunctuationProcessor.process("salt ampersand pepper"))
        assertEquals("two + two = four", VoicePunctuationProcessor.process("two plus sign two equals sign four"))
        assertEquals("she said \"hi\" twice", VoicePunctuationProcessor.process("she said begin quote hi end quote twice"))
        assertEquals("[x]", VoicePunctuationProcessor.process("open square bracket x close square bracket"))
        assertEquals("{x}", VoicePunctuationProcessor.process("left curly brace x end curly brace"))
        assertEquals("about $5", VoicePunctuationProcessor.process("about dollar sign 5"))
    }

    @Test
    fun `uses the dictation language table and leaves unknown languages verbatim`() {
        assertEquals(
            "আমি ভালো।",
            VoicePunctuationProcessor.process("আমি ভালো দাঁড়ি", languageTag = "bn-BD")
        )
        assertEquals(
            "Oi, tudo bem?",
            VoicePunctuationProcessor.process("Oi vírgula tudo bem ponto de interrogação", languageTag = "pt-BR")
        )
        assertEquals(
            "Hola, amigo!",
            VoicePunctuationProcessor.process("Hola coma amigo signo de exclamación", languageTag = "es-ES")
        )
        assertEquals(
            "hallo comma  welt",
            VoicePunctuationProcessor.process("hallo comma  welt", languageTag = "de-DE")
        )
    }

    @Test
    fun `auto and empty language tags fallback to English while unsupported stays verbatim`() {
        val input = "Hello comma world period"
        val expected = "Hello, world."
        assertEquals(expected, VoicePunctuationProcessor.process(input, languageTag = "auto"))
        assertEquals(expected, VoicePunctuationProcessor.process(input, languageTag = ""))
        assertEquals(input, VoicePunctuationProcessor.process(input, languageTag = "de-DE"))
    }
}
