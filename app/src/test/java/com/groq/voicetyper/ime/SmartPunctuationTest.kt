package com.groq.voicetyper.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class SmartPunctuationTest {

    @Test
    fun `trailing single space after word with cursor at end collapses space and appends space`() {
        val action = SmartPunctuation.resolve(
            symbol = ".",
            textBefore = "hello world ",
            textAfter = ""
        )
        assertEquals(1, action.deleteCount)
        assertEquals(". ", action.insertText)
    }

    @Test
    fun `comma collapses single trailing space when cursor at end`() {
        val action = SmartPunctuation.resolve(
            symbol = ",",
            textBefore = "yes ",
            textAfter = ""
        )
        assertEquals(1, action.deleteCount)
        assertEquals(", ", action.insertText)
    }

    @Test
    fun `cursor not at end performs ordinary insertion and never deletes text`() {
        val action = SmartPunctuation.resolve(
            symbol = ".",
            textBefore = "hello ",
            textAfter = "world"
        )
        assertEquals(0, action.deleteCount)
        assertEquals(".", action.insertText)
    }

    @Test
    fun `multiple trailing spaces are not collapsed`() {
        val action = SmartPunctuation.resolve(
            symbol = ".",
            textBefore = "hello  ",
            textAfter = ""
        )
        assertEquals(0, action.deleteCount)
        assertEquals(".", action.insertText)
    }

    @Test
    fun `decimal number insertion does not delete text or add space`() {
        val action = SmartPunctuation.resolve(
            symbol = ".",
            textBefore = "42",
            textAfter = ""
        )
        assertEquals(0, action.deleteCount)
        assertEquals(".", action.insertText)
    }

    @Test
    fun `email address insertion does not delete text or add space`() {
        val action = SmartPunctuation.resolve(
            symbol = "@",
            textBefore = "alex",
            textAfter = "fluence.io"
        )
        assertEquals(0, action.deleteCount)
        assertEquals("@", action.insertText)
    }

    @Test
    fun `url punctuation insertion does not delete text or add space`() {
        val action = SmartPunctuation.resolve(
            symbol = ":",
            textBefore = "https",
            textAfter = "//fluence.io"
        )
        assertEquals(0, action.deleteCount)
        assertEquals(":", action.insertText)
    }

    @Test
    fun `abbreviation insertion does not delete text or add space`() {
        val action = SmartPunctuation.resolve(
            symbol = ".",
            textBefore = "U.S.",
            textAfter = ""
        )
        assertEquals(0, action.deleteCount)
        assertEquals(".", action.insertText)
    }

    @Test
    fun `cursor in middle of word does not delete text or add space`() {
        val action = SmartPunctuation.resolve(
            symbol = ",",
            textBefore = "he",
            textAfter = "llo"
        )
        assertEquals(0, action.deleteCount)
        assertEquals(",", action.insertText)
    }

    @Test
    fun `active selection never deletes text`() {
        val action = SmartPunctuation.resolve(
            symbol = ".",
            textBefore = "some text ",
            textAfter = "",
            hasSelection = true
        )
        assertEquals(0, action.deleteCount)
        assertEquals(".", action.insertText)
    }

    @Test
    fun `empty text before cursor performs plain insertion`() {
        val action = SmartPunctuation.resolve(
            symbol = "?",
            textBefore = "",
            textAfter = ""
        )
        assertEquals(0, action.deleteCount)
        assertEquals("?", action.insertText)
    }
}
