package com.groq.voicetyper.offline

import org.junit.Assert.*
import org.junit.Test

class OfflineEngineTypeTest {

    @Test
    fun testIsStreamingProperty() {
        assertFalse(OfflineEngineType.SENSEVOICE.isStreaming)
        assertTrue(OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING.isStreaming)
        assertTrue(OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING.isStreaming)
    }

    @Test
    fun testModelArchMapping() {
        assertEquals(0, OfflineEngineType.SENSEVOICE.modelArch)
        assertEquals(
            ai.moonshine.voice.JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING,
            OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING.modelArch
        )
        assertEquals(
            ai.moonshine.voice.JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING,
            OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING.modelArch
        )
    }
}
