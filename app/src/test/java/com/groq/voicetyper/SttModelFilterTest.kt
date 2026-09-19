package com.groq.voicetyper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SttModelFilterTest {

    @Test
    fun `batch whisper family passes`() {
        assertTrue(SttModelFilter.isAsrModelId("whisper-large-v3"))
        assertTrue(SttModelFilter.isAsrModelId("whisper-large-v3-turbo"))
        assertTrue(SttModelFilter.isAsrModelId("whisper-1"))
    }

    @Test
    fun `streaming families pass`() {
        assertTrue(SttModelFilter.isAsrModelId("voxtral-mini-latest"))
        assertTrue(SttModelFilter.isAsrModelId("mistral-stt"))
    }

    @Test
    fun `matching is case-insensitive`() {
        assertTrue(SttModelFilter.isAsrModelId("Whisper-Large-V3"))
        assertTrue(SttModelFilter.isAsrModelId("VOXTRAL-MINI-LATEST"))
    }

    @Test
    fun `chat models fail closed`() {
        assertFalse(SttModelFilter.isAsrModelId("llama-3.3-70b-versatile"))
        assertFalse(SttModelFilter.isAsrModelId("gpt-4o"))
        assertFalse(SttModelFilter.isAsrModelId("mistral-large-latest"))
        assertFalse(SttModelFilter.isAsrModelId("gpt-4o-mini"))
    }

    @Test
    fun `filter drops chat models and keeps speech models`() {
        val out = SttModelFilter.applyFilter(
            listOf("whisper-large-v3", "llama-3.3-70b-versatile", "voxtral-mini-latest", "gpt-4o"),
            keep = null
        )
        assertEquals(listOf("whisper-large-v3", "voxtral-mini-latest"), out)
    }

    @Test
    fun `keep is always included even when filtered out`() {
        val out = SttModelFilter.applyFilter(
            listOf("llama-3.3-70b-versatile"),
            keep = "whisper-large-v3"
        )
        assertEquals(listOf("whisper-large-v3"), out)
    }

    @Test
    fun `blank keep adds nothing`() {
        assertEquals(emptyList<String>(), SttModelFilter.applyFilter(emptyList(), keep = "  "))
        assertEquals(
            listOf("whisper-1"),
            SttModelFilter.applyFilter(listOf("whisper-1"), keep = null)
        )
    }
}
