package com.groq.voicetyper

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.Runs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioFocusPreferencesTest {
    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val store = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        store.clear()
        every { context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getString(AudioFocusPreferences.KEY_AUDIO_FOCUS_MODE, null) } answers {
            store[AudioFocusPreferences.KEY_AUDIO_FOCUS_MODE] as? String
        }
        every { preferences.getBoolean(AudioFocusPreferences.KEY_DUCKING_ENABLED, false) } answers {
            store[AudioFocusPreferences.KEY_DUCKING_ENABLED] as? Boolean ?: false
        }
        every { preferences.edit() } returns editor
        val stringSlot = slot<String>()
        val stringValue = slot<String>()
        every { editor.putString(capture(stringSlot), capture(stringValue)) } answers {
            store[stringSlot.captured] = stringValue.captured
            editor
        }
        val boolSlot = slot<String>()
        val boolValue = slot<Boolean>()
        every { editor.putBoolean(capture(boolSlot), capture(boolValue)) } answers {
            store[boolSlot.captured] = boolValue.captured
            editor
        }
        every { editor.apply() } just Runs
    }

    @Test
    fun default_emptyPrefs_isOff() {
        assertEquals(AudioFocusMode.OFF, AudioFocusPreferences.getMode(context))
        assertFalse(AudioFocusPreferences.isDuckingEnabled(context))
        assertFalse(AudioFocusPreferences.isPauseEnabled(context))
    }

    @Test
    fun legacy_true_migratesToDuck() {
        store[AudioFocusPreferences.KEY_DUCKING_ENABLED] = true
        assertEquals(AudioFocusMode.DUCK, AudioFocusPreferences.getMode(context))
        assertTrue(AudioFocusPreferences.isDuckingEnabled(context))
    }

    @Test
    fun setPause_persists_andKeepsLegacyFalse() {
        AudioFocusPreferences.setMode(context, AudioFocusMode.PAUSE)
        assertEquals(AudioFocusMode.PAUSE, AudioFocusPreferences.getMode(context))
        assertTrue(AudioFocusPreferences.isPauseEnabled(context))
        assertFalse(AudioFocusPreferences.isDuckingEnabled(context))
        assertEquals("PAUSE", store[AudioFocusPreferences.KEY_AUDIO_FOCUS_MODE])
        assertEquals(false, store[AudioFocusPreferences.KEY_DUCKING_ENABLED])
    }

    @Test
    fun setDuck_keepsLegacyTrue() {
        AudioFocusPreferences.setMode(context, AudioFocusMode.DUCK)
        assertEquals(AudioFocusMode.DUCK, AudioFocusPreferences.getMode(context))
        assertEquals(true, store[AudioFocusPreferences.KEY_DUCKING_ENABLED])
    }

    @Test
    fun unknownString_fallsBackToLegacy() {
        store[AudioFocusPreferences.KEY_AUDIO_FOCUS_MODE] = "BOGUS"
        store[AudioFocusPreferences.KEY_DUCKING_ENABLED] = true
        assertEquals(AudioFocusMode.DUCK, AudioFocusPreferences.getMode(context))
    }

    @Test
    fun legacyWrapper_roundTrips() {
        AudioFocusPreferences.setDuckingEnabled(context, true)
        assertEquals(AudioFocusMode.DUCK, AudioFocusPreferences.getMode(context))
        AudioFocusPreferences.setDuckingEnabled(context, false)
        assertEquals(AudioFocusMode.OFF, AudioFocusPreferences.getMode(context))
    }
}
