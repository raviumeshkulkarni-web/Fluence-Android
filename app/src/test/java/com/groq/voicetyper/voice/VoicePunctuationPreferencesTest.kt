package com.groq.voicetyper.voice

import android.content.Context
import android.content.SharedPreferences
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VoicePunctuationPreferencesTest {

    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private var backingEnabled = false

    @Before
    fun setUp() {
        backingEnabled = false
        VoicePunctuationPreferences.resetForTests()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getBoolean("spoken_punctuation_enabled", false) } answers { backingEnabled }
        every { preferences.registerOnSharedPreferenceChangeListener(any()) } just Runs
        every { preferences.unregisterOnSharedPreferenceChangeListener(any()) } just Runs
        every { preferences.edit() } returns editor

        val keySlot = slot<String>()
        val valSlot = slot<Boolean>()
        every { editor.putBoolean(capture(keySlot), capture(valSlot)) } answers {
            if (keySlot.captured == "spoken_punctuation_enabled") {
                backingEnabled = valSlot.captured
            }
            editor
        }
        every { editor.apply() } just Runs
    }

    @After
    fun tearDown() {
        VoicePunctuationPreferences.resetForTests()
    }

    @Test
    fun `default setting is disabled (opt-in)`() {
        assertFalse(VoicePunctuationPreferences.isSpokenPunctuationEnabled(context))
    }

    @Test
    fun `setSpokenPunctuationEnabled updates preference and snapshot`() {
        VoicePunctuationPreferences.setSpokenPunctuationEnabled(context, true)
        assertTrue(VoicePunctuationPreferences.isSpokenPunctuationEnabled(context))
        assertTrue(backingEnabled)

        VoicePunctuationPreferences.setSpokenPunctuationEnabled(context, false)
        assertFalse(VoicePunctuationPreferences.isSpokenPunctuationEnabled(context))
        assertFalse(backingEnabled)
    }

    @Test
    fun `resetForTests clears in-memory snapshot back to false`() {
        VoicePunctuationPreferences.setSpokenPunctuationEnabled(context, true)
        assertTrue(VoicePunctuationPreferences.isSpokenPunctuationEnabled(context))

        backingEnabled = false
        VoicePunctuationPreferences.resetForTests()
        assertFalse(VoicePunctuationPreferences.isSpokenPunctuationEnabled(context))
    }
}
