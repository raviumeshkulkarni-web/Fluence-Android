package com.groq.voicetyper

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// Contract for the waveform animation pick (Windows Floating Bubble parity):
// unset and unknown values MUST fall back to "waves" (today's look), and the
// setter must normalize before writing so a bad value can never be stored.
class FloatingBubblePreferencesWaveformTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var context: Context

    @Before
    fun setUp() {
        prefs = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
    }

    @Test
    fun waveformStyle_defaultsToWaves() {
        every { prefs.getString(FloatingBubblePreferences.KEY_WAVEFORM_STYLE, any()) } returns null
        assertEquals(
            FloatingBubblePreferences.WAVEFORM_WAVES,
            FloatingBubblePreferences.getWaveformStyle(context)
        )
    }

    @Test
    fun waveformStyle_reflectsStoredBars() {
        every { prefs.getString(FloatingBubblePreferences.KEY_WAVEFORM_STYLE, any()) } returns
            FloatingBubblePreferences.WAVEFORM_BARS
        assertEquals(
            FloatingBubblePreferences.WAVEFORM_BARS,
            FloatingBubblePreferences.getWaveformStyle(context)
        )
    }

    @Test
    fun waveformStyle_unknownValuesFallBackToWaves() {
        for (junk in listOf("dot", "siri", "orb", "", "BARS", "spikes")) {
            every { prefs.getString(FloatingBubblePreferences.KEY_WAVEFORM_STYLE, any()) } returns junk
            assertEquals(
                FloatingBubblePreferences.WAVEFORM_WAVES,
                FloatingBubblePreferences.getWaveformStyle(context)
            )
        }
    }

    @Test
    fun waveformStyleSetter_writesBarsAndNormalizesJunk() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { prefs.edit() } returns editor

        FloatingBubblePreferences.setWaveformStyle(context, FloatingBubblePreferences.WAVEFORM_BARS)
        FloatingBubblePreferences.setWaveformStyle(context, "orb")

        verify(exactly = 1) { editor.putString(FloatingBubblePreferences.KEY_WAVEFORM_STYLE, FloatingBubblePreferences.WAVEFORM_BARS) }
        verify(exactly = 1) { editor.putString(FloatingBubblePreferences.KEY_WAVEFORM_STYLE, FloatingBubblePreferences.WAVEFORM_WAVES) }
    }
}
