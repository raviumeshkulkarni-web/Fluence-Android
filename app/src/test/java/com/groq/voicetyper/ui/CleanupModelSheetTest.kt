package com.groq.voicetyper.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupModelSheetTest {

    @Test
    fun `speech models are excluded`() {
        assertTrue(isSpeechModelId("whisper-large-v3"))
        assertTrue(isSpeechModelId("voxtral-mini-latest"))
        assertTrue(isSpeechModelId("playai-tts"))
        assertTrue(isSpeechModelId("moonshine-base"))
    }

    @Test
    fun `chat models are kept`() {
        assertFalse(isSpeechModelId("llama-3.3-70b-versatile"))
        assertFalse(isSpeechModelId("mistral-large-latest"))
        assertFalse(isSpeechModelId("gpt-4o"))
    }
}
