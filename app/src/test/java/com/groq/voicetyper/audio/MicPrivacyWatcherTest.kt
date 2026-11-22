package com.groq.voicetyper.audio

import android.media.AudioRecordingConfiguration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicPrivacyWatcherTest {

    private class FakeAudioRecordingConfiguration(
        private val silenced: Boolean,
        private val sessionId: Int = 1
    ) {
        fun toMock(): AudioRecordingConfiguration {
            val config = io.mockk.mockk<AudioRecordingConfiguration>(relaxed = true)
            io.mockk.every { config.isClientSilenced } returns silenced
            io.mockk.every { config.clientAudioSessionId } returns sessionId
            return config
        }
    }

    @Test
    fun isBlocked_nullOrEmptyConfigs_returnsFalse() {
        assertFalse(MicPrivacyWatcher.isBlocked(null))
        assertFalse(MicPrivacyWatcher.isBlocked(emptyList()))
    }

    @Test
    fun isBlocked_oneSilencedAndOneActive_returnsFalse() {
        // Critical concurrent-capture test:
        // A background app may be silenced by Android's concurrent capture policy,
        // while our dictation session is hearing fine.
        val silencedConfig = FakeAudioRecordingConfiguration(silenced = true, sessionId = 101).toMock()
        val activeConfig = FakeAudioRecordingConfiguration(silenced = false, sessionId = 102).toMock()

        val configs = listOf(silencedConfig, activeConfig)
        assertFalse(MicPrivacyWatcher.isBlocked(configs))
    }

    @Test
    fun isBlocked_allSilenced_returnsTrue() {
        val config1 = FakeAudioRecordingConfiguration(silenced = true, sessionId = 101).toMock()
        val config2 = FakeAudioRecordingConfiguration(silenced = true, sessionId = 102).toMock()

        val configs = listOf(config1, config2)
        assertTrue(MicPrivacyWatcher.isBlocked(configs))
    }

    @Test
    fun isBlocked_withSpecificSessionId_evaluatesTargetOnly() {
        val backgroundSilenced = FakeAudioRecordingConfiguration(silenced = true, sessionId = 999).toMock()
        val ourSessionActive = FakeAudioRecordingConfiguration(silenced = false, sessionId = 42).toMock()

        val configs = listOf(backgroundSilenced, ourSessionActive)
        assertFalse(MicPrivacyWatcher.isBlocked(configs, sessionId = 42))

        val ourSessionSilenced = FakeAudioRecordingConfiguration(silenced = true, sessionId = 42).toMock()
        val configsSilenced = listOf(backgroundSilenced, ourSessionSilenced)
        assertTrue(MicPrivacyWatcher.isBlocked(configsSilenced, sessionId = 42))
    }

}
