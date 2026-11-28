package com.groq.voicetyper.quickcontrols

import android.content.Context
import android.content.SharedPreferences
import com.groq.voicetyper.offline.OfflineEngineType
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FluenceQuickControlsTest {
    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val stringStore = mutableMapOf<String, String?>()

    @Before
    fun setUp() {
        stringStore.clear()
        every { context.applicationContext } returns context
        every {
            context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
        } returns preferences
        every { preferences.getString(any(), any()) } answers {
            val key = firstArg<String>()
            val defaultValue = secondArg<String?>()
            stringStore[key] ?: defaultValue
        }
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } answers {
            stringStore[firstArg()] = secondArg()
            editor
        }
        every { editor.apply() } just Runs
    }

    @Test
    fun resolveReadyOfflineEngine_returnsPreferredWhenReady() {
        val ready = setOf(
            OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING,
            OfflineEngineType.SENSEVOICE
        )

        val resolved = FluenceQuickControls.resolveReadyOfflineEngine(
            preferred = OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING,
            isReady = { it in ready }
        )

        assertEquals(OfflineEngineType.MOONSHINE_V2_MEDIUM_STREAMING, resolved)
    }

    @Test
    fun resolveReadyOfflineEngine_fallsBackToInstalledSecondaryEngine() {
        val ready = setOf(OfflineEngineType.SENSEVOICE)

        val resolved = FluenceQuickControls.resolveReadyOfflineEngine(
            preferred = OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING,
            isReady = { it in ready }
        )

        assertEquals(OfflineEngineType.SENSEVOICE, resolved)
    }

    @Test
    fun resolveReadyOfflineEngine_returnsNullWhenNoEngineInstalled() {
        val resolved = FluenceQuickControls.resolveReadyOfflineEngine(
            preferred = OfflineEngineType.MOONSHINE_V2_SMALL_STREAMING,
            isReady = { false }
        )

        assertNull(resolved)
    }

    @Test
    fun isValidShortcutToken_validatesGeneratedTokenAndRejectsInvalidOnes() {
        // Before any token exists, every candidate must fail.
        assertFalse(FluenceQuickControls.isValidShortcutToken(context, "any-token"))

        val generated = FluenceQuickControls.getOrCreateShortcutToken(context)
        assertTrue(generated.isNotBlank())
        assertEquals(generated, FluenceQuickControls.getOrCreateShortcutToken(context))

        assertTrue(FluenceQuickControls.isValidShortcutToken(context, generated))
        assertFalse(FluenceQuickControls.isValidShortcutToken(context, null))
        assertFalse(FluenceQuickControls.isValidShortcutToken(context, ""))
        assertFalse(FluenceQuickControls.isValidShortcutToken(context, "   "))
        assertFalse(FluenceQuickControls.isValidShortcutToken(context, "${generated}x"))
        assertFalse(FluenceQuickControls.isValidShortcutToken(context, "wrong-token"))
    }
}
