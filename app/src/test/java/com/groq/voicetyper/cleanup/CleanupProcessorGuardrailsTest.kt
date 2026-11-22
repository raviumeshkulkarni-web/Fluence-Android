package com.groq.voicetyper.cleanup

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupProcessorGuardrailsTest {

    @Test
    fun sanitizeAndGuardOutput_stripsMarkdownCodeFences() {
        val input = "Hello world"
        val fenced = "```\nHello world.\n```"
        val result = CleanupProcessor.sanitizeAndGuardOutput(input, fenced)
        assertEquals("Hello world.", result)
    }

    @Test
    fun sanitizeAndGuardOutput_stripsWrappingQuotesWhenInputUnquoted() {
        val input = "meeting tomorrow at 2pm"
        val quoted = "\"Meeting tomorrow at 2:00 PM.\""
        val result = CleanupProcessor.sanitizeAndGuardOutput(input, quoted)
        assertEquals("Meeting tomorrow at 2:00 PM.", result)
    }

    @Test
    fun sanitizeAndGuardOutput_stripsWrappingSingleQuotesWhenInputUnquoted() {
        val input = "meeting tomorrow at 2pm"
        val quoted = "'Meeting tomorrow at 2:00 PM.'"
        val result = CleanupProcessor.sanitizeAndGuardOutput(input, quoted)
        assertEquals("Meeting tomorrow at 2:00 PM.", result)
    }

    @Test
    fun sanitizeAndGuardOutput_preservesWrappingQuotesWhenInputWasQuoted() {
        val input = "\"hello world\""
        val quoted = "\"Hello world.\""
        val result = CleanupProcessor.sanitizeAndGuardOutput(input, quoted)
        assertEquals("\"Hello world.\"", result)
    }

    @Test
    fun sanitizeAndGuardOutput_normalizesSpacingViaVoiceTextNormalizer() {
        val input = "we should go , right ?"
        val tidied = "We should go , right ?"
        val result = CleanupProcessor.sanitizeAndGuardOutput(input, tidied)
        assertEquals("We should go, right?", result)
    }

    @Test
    fun sanitizeAndGuardOutput_rejectsRunawayHallucinationLength() {
        val input = "short message" // ~13 chars
        val runaway = "Here is a very long paragraph that goes on and on and invents new facts and details ".repeat(10)
        val result = CleanupProcessor.sanitizeAndGuardOutput(input, runaway)
        assertEquals(input, result)
    }

    @Test
    fun sanitizeAndGuardOutput_rejectsRunawayHallucinationLength_longInputExceeding500Chars() {
        val input = "A valid transcript sentence with sufficient detail. ".repeat(15) // ~780 chars
        val runaway = "Extremely long hallucinated text that exceeds four times input length. ".repeat(60) // ~4,200 chars
        val result = CleanupProcessor.sanitizeAndGuardOutput(input, runaway)
        assertEquals(input, result)
    }

    @Test
    fun sanitizeAndGuardOutput_rejectsChattyConversationalReplies() {
        val input = "Please write this down for me"
        val chatty = "I am ready."
        val result = CleanupProcessor.sanitizeAndGuardOutput(input, chatty)
        assertEquals(input, result)
    }

    @Test
    fun sanitizeAndGuardOutput_blankOutput_returnsInput() {
        val input = "keep me"
        val result = CleanupProcessor.sanitizeAndGuardOutput(input, "   ")
        assertEquals(input, result)
    }

    @Test
    fun sanitizeAndGuardOutput_doesNotCorruptMultiQuoteProse() {
        val inputDouble = "he said hello and goodbye"
        val outputDouble = "\"Hello,\" he said. \"Goodbye.\""
        assertEquals(outputDouble, CleanupProcessor.sanitizeAndGuardOutput(inputDouble, outputDouble))

        val inputSingle = "she said hello and bye"
        val outputSingle = "'Hello,' she said. 'Bye.'"
        assertEquals(outputSingle, CleanupProcessor.sanitizeAndGuardOutput(inputSingle, outputSingle))

        val inputApostrophe = "it is 2pm"
        val outputApostrophe = "'It's 2:00 PM.'"
        assertEquals("It's 2:00 PM.", CleanupProcessor.sanitizeAndGuardOutput(inputApostrophe, outputApostrophe))
    }
}
