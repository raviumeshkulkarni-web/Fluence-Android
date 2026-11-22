package com.groq.voicetyper.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCasingTest {

    @Test
    fun `detects sentence starts from field context`() {
        assertTrue(VoiceCasing.startsSentence(""))
        assertTrue(VoiceCasing.startsSentence("Hello. "))
        assertTrue(VoiceCasing.startsSentence("Hello?\n"))
        assertTrue(VoiceCasing.startsSentence("He said \"Hello.\" "))
        assertFalse(VoiceCasing.startsSentence("Hello, "))
        assertFalse(VoiceCasing.startsSentence("Hello "))
        assertFalse(VoiceCasing.startsSentence("("))
    }

    @Test
    fun `lowers automatic capital mid sentence but keeps names and acronyms`() {
        assertEquals("hello there", VoiceCasing.apply("Hello there", sentenceStart = false))
        assertEquals("I agree", VoiceCasing.apply("I agree", sentenceStart = false))
        assertEquals("I'm ready", VoiceCasing.apply("I'm ready", sentenceStart = false))
        assertEquals("I, however, agree", VoiceCasing.apply("I, however, agree", sentenceStart = false))
        assertEquals("NASA is here", VoiceCasing.apply("NASA is here", sentenceStart = false))
        assertEquals("iPhone works", VoiceCasing.apply("iPhone works", sentenceStart = false))
    }

    @Test
    fun `raises sentence start and preserves intentional capitalization`() {
        assertEquals("Hello there", VoiceCasing.apply("hello there", sentenceStart = true))
        assertEquals("NASA is here", VoiceCasing.apply("NASA is here", sentenceStart = true))
        assertEquals("iPhone works", VoiceCasing.apply("iPhone works", sentenceStart = true))
        assertEquals("\"Hello world\"", VoiceCasing.apply("\"hello world\"", sentenceStart = true))
    }

    @Test
    fun `raises later sentences without treating initials as sentence ends`() {
        assertEquals("Done. And then", VoiceCasing.apply("Done. and then", sentenceStart = true))
        assertEquals("e.g. this stays", VoiceCasing.apply("e.g. this stays", sentenceStart = false))
    }
}
