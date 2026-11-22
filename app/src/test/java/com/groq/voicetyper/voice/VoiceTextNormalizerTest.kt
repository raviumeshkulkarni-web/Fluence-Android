package com.groq.voicetyper.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTextNormalizerTest {

    @Test
    fun `removes space before punctuation marks`() {
        val input = "Hello , world . How are you ? Great !"
        val expected = "Hello, world. How are you? Great!"
        assertEquals(expected, VoiceTextNormalizer.normalize(input))
    }

    @Test
    fun `ensures space after comma and colon when missing`() {
        val input = "apples,oranges,bananas:fruits"
        val expected = "apples, oranges, bananas: fruits"
        assertEquals(expected, VoiceTextNormalizer.normalize(input))
    }

    @Test
    fun `removes spaces inside brackets and quotes`() {
        val input = "( hello world ) and [ test ] and \" quoted text \""
        val expected = "(hello world) and [test] and \"quoted text\""
        assertEquals(expected, VoiceTextNormalizer.normalize(input))
    }

    @Test
    fun `removes spaces after currency and symbols`() {
        val input = "It costs $ 50 or € 100 on @ user # tag"
        val expected = "It costs $50 or €100 on @user #tag"
        assertEquals(expected, VoiceTextNormalizer.normalize(input))
    }

    @Test
    fun `collapses multiple horizontal spaces`() {
        val input = "Hello    world.   How   are you?"
        val expected = "Hello world. How are you?"
        assertEquals(expected, VoiceTextNormalizer.normalize(input))
    }

    @Test
    fun `capitalizes after internal sentence boundaries and newlines without forcing initial letter`() {
        val input = "hello world. this is second. and third?\nyes! fourth."
        val expected = "hello world. This is second. And third?\nYes! Fourth."
        assertEquals(expected, VoiceTextNormalizer.normalize(input))
    }

    @Test
    fun `handles empty and blank strings gracefully`() {
        assertEquals("", VoiceTextNormalizer.normalize(""))
        assertEquals("   ", VoiceTextNormalizer.normalize("   "))
    }

    @Test
    fun `preserves numeric grouping in numbers with commas and decimals`() {
        assertEquals("1,000", VoiceTextNormalizer.normalize("1,000"))
        assertEquals("The total is $1,500,000 for 10,000 items", VoiceTextNormalizer.normalize("The total is $ 1,500,000 for 10,000 items"))
        assertEquals("pi is roughly 3,14", VoiceTextNormalizer.normalize("pi is roughly 3,14"))
        assertEquals("page 1, chapter 2", VoiceTextNormalizer.normalize("page 1,chapter 2"))
        assertEquals("item 1, item 2", VoiceTextNormalizer.normalize("item 1,item 2"))
    }

    @Test
    fun `does not capitalize words following abbreviations like eg and ie`() {
        val inputEg = "For example, e.g. the first item on the list"
        assertEquals(inputEg, VoiceTextNormalizer.normalize(inputEg))

        val inputIe = "This is a concept, i.e. an important principle"
        assertEquals(inputIe, VoiceTextNormalizer.normalize(inputIe))

        val inputEtc = "Bring apples, oranges, etc. and some water"
        assertEquals(inputEtc, VoiceTextNormalizer.normalize(inputEtc))

        val inputEtAl = "Smith et al. presented the study"
        assertEquals(inputEtAl, VoiceTextNormalizer.normalize(inputEtAl))

        val inputPm = "The call is at 4 p.m. tomorrow"
        assertEquals(inputPm, VoiceTextNormalizer.normalize(inputPm))

        val inputVs = "Team A vs. team B will play tonight"
        assertEquals(inputVs, VoiceTextNormalizer.normalize(inputVs))

        val inputJr = "Smith Jr. was elected to the board"
        assertEquals(inputJr, VoiceTextNormalizer.normalize(inputJr))

        val inputCorp = "Visit Acme Corp. for further details"
        assertEquals(inputCorp, VoiceTextNormalizer.normalize(inputCorp))

        val inputDr = "Call Dr. watson at 5 a.m. sharp"
        assertEquals(inputDr, VoiceTextNormalizer.normalize(inputDr))
    }

    @Test
    fun `adversarial natural language numbers and symbols are correctly preserved`() {
        val complexFinancial = "We generated 10,000 leads, sold 2,500 units, and earned $ 1,250,500.75 in revenue"
        assertEquals(
            "We generated 10,000 leads, sold 2,500 units, and earned $1,250,500.75 in revenue",
            VoiceTextNormalizer.normalize(complexFinancial)
        )

        // Time preserves colon while subsequent clause gets spacing
        assertEquals(
            "Meeting at 14:30: please be on time",
            VoiceTextNormalizer.normalize("Meeting at 14:30:please be on time")
        )

        // Comma between words and numbers
        assertEquals(
            "flight 123, gate 4, section 5",
            VoiceTextNormalizer.normalize("flight 123,gate 4,section 5")
        )

        // Unicode and accented characters
        assertEquals(
            "bonjour, monde et año, nuevo",
            VoiceTextNormalizer.normalize("bonjour,monde et año,nuevo")
        )
    }

    @Test
    fun `technical tokens and urls are preserved verbatim`() {
        assertEquals("http://localhost:8080", VoiceTextNormalizer.normalize("http://localhost:8080"))
        assertEquals("https://example.com:443/api", VoiceTextNormalizer.normalize("https://example.com:443/api"))
        assertEquals("mailto:user@example.com", VoiceTextNormalizer.normalize("mailto:user@example.com"))
        assertEquals("std::vector", VoiceTextNormalizer.normalize("std::vector"))
        assertEquals("open .env", VoiceTextNormalizer.normalize("open .env"))
    }
}
