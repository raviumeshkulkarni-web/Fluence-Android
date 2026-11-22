package com.groq.voicetyper.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSpacingTest {

    @Test
    fun `empty text before adds no leading space and capitalizes text`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = "", textToInsert = "hello world")
        assertEquals(0, plan.deleteBefore)
        assertEquals("Hello world ", plan.formattedText)
    }

    @Test
    fun `mid sentence text before does not capitalize continuation phrase`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = "I want to eat ", textToInsert = "an apple")
        assertEquals(0, plan.deleteBefore)
        assertEquals("an apple ", plan.formattedText)
    }

    @Test
    fun `word text before without trailing space adds leading space`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = "Hello", textToInsert = "world")
        assertEquals(0, plan.deleteBefore)
        assertEquals(" world ", plan.formattedText)
    }

    @Test
    fun `text before with trailing space does not duplicate leading space`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = "Hello ", textToInsert = "world")
        assertEquals(0, plan.deleteBefore)
        assertEquals("world ", plan.formattedText)
    }

    @Test
    fun `text before with period and space capitalizes first letter`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = "Hello. ", textToInsert = "how are you")
        assertEquals(0, plan.deleteBefore)
        assertEquals("How are you ", plan.formattedText)
    }

    @Test
    fun `text before with period without space adds space and capitalizes`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = "Hello.", textToInsert = "how are you")
        assertEquals(0, plan.deleteBefore)
        assertEquals(" How are you ", plan.formattedText)
    }

    @Test
    fun `text before with trailing space collapses space when inserting punctuation`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = "Hello ", textToInsert = ", and welcome")
        assertEquals(1, plan.deleteBefore)
        assertEquals(", and welcome ", plan.formattedText)
    }

    @Test
    fun `text before newline does not add leading space and capitalizes`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = "Line 1\n", textToInsert = "line 2")
        assertEquals(0, plan.deleteBefore)
        assertEquals("Line 2 ", plan.formattedText)
    }

    @Test
    fun `text before opening bracket does not add leading space`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = "(", textToInsert = "inside")
        assertEquals(0, plan.deleteBefore)
        assertEquals("inside ", plan.formattedText)
    }

    @Test
    fun `capitalizes first letter inside opening quotes at start of sentence`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = "", textToInsert = "\"hello world\"")
        assertEquals(0, plan.deleteBefore)
        assertEquals("\"Hello world\" ", plan.formattedText)
    }

    @Test
    fun `empty or blank insert text returns empty plan`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = "Hello ", textToInsert = "   ")
        assertEquals(0, plan.deleteBefore)
        assertEquals("", plan.formattedText)
    }

    @Test
    fun `null text before treats as append-with-space and does not capitalize`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = null, textToInsert = "hello world")
        assertEquals(0, plan.deleteBefore)
        assertEquals(" hello world ", plan.formattedText)
    }

    @Test
    fun `null text before with attaching punctuation does not add leading space`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = null, textToInsert = ", and more")
        assertEquals(0, plan.deleteBefore)
        assertEquals(", and more ", plan.formattedText)
    }

    @Test
    fun `lowers recognizer capital when inserting mid sentence`() {
        val plan = VoiceSpacing.calculateInsertion(textBefore = "I said ", textToInsert = "Hello there")
        assertEquals(0, plan.deleteBefore)
        assertEquals("hello there ", plan.formattedText)
    }

    @Test
    fun `keeps pronoun I and acronyms mid sentence`() {
        assertEquals(
            "I agree ",
            VoiceSpacing.calculateInsertion(textBefore = "I said ", textToInsert = "I agree").formattedText
        )
        assertEquals(
            "NASA is here ",
            VoiceSpacing.calculateInsertion(textBefore = "I said ", textToInsert = "NASA is here").formattedText
        )
    }

    @Test
    fun `does not add trailing space before existing whitespace`() {
        val plan = VoiceSpacing.calculateInsertion(
            textBefore = "Hello ",
            textToInsert = "world",
            textAfter = " again"
        )
        assertEquals(0, plan.deleteBefore)
        assertEquals("world", plan.formattedText)
    }

    @Test
    fun `adds trailing space before following word`() {
        val plan = VoiceSpacing.calculateInsertion(
            textBefore = "Hello ",
            textToInsert = "world",
            textAfter = "again"
        )
        assertEquals(0, plan.deleteBefore)
        assertEquals("world ", plan.formattedText)
    }

    @Test
    fun `does not add trailing space before closing punctuation`() {
        val plan = VoiceSpacing.calculateInsertion(
            textBefore = "Hello ",
            textToInsert = "world",
            textAfter = "."
        )
        assertEquals(0, plan.deleteBefore)
        assertEquals("world", plan.formattedText)
    }

    @Test
    fun `recognizes sentence start after closing quote`() {
        val plan = VoiceSpacing.calculateInsertion(
            textBefore = "He said \"Hello.\" ",
            textToInsert = "how are you"
        )
        assertEquals("How are you ", plan.formattedText)
    }

    @Test
    fun `null text before preserves opening capital`() {
        val plan = VoiceSpacing.calculateInsertion(
            textBefore = null,
            textToInsert = "Hello world"
        )
        assertEquals(0, plan.deleteBefore)
        assertEquals(" Hello world ", plan.formattedText)
    }

    @Test
    fun `preserves abbreviation casing for inner sentences`() {
        val plan = VoiceSpacing.calculateInsertion(
            textBefore = "",
            textToInsert = "Call Dr. smith"
        )
        assertEquals("Call Dr. smith ", plan.formattedText)
    }
}
