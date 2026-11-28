package com.groq.voicetyper

import android.content.Context
import android.content.SharedPreferences
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrivacyExclusionHardeningTest {
    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private val storedPackages = mutableSetOf<String>()

    @Before
    fun setUp() {
        PrivacyPreferences.resetForTests()
        every { context.applicationContext } returns context
        every {
            context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
        } returns preferences
        every {
            preferences.getStringSet(PrivacyPreferences.KEY_EXCLUDED_PACKAGES, any())
        } answers { storedPackages.toSet() }
        every { preferences.registerOnSharedPreferenceChangeListener(any()) } just Runs
        every { preferences.unregisterOnSharedPreferenceChangeListener(any()) } just Runs
        every { preferences.edit() } returns editor
        every { editor.putStringSet(any(), any()) } answers {
            storedPackages.clear()
            storedPackages.addAll(secondArg<Set<String>>())
            editor
        }
        every { editor.apply() } just Runs
    }

    @After
    fun tearDown() {
        PrivacyPreferences.resetForTests()
    }

    @Test
    fun evaluateWindowExclusion_returnsTrueWhenAnyVisibleWindowIsExcluded() {
        val excluded = setOf("com.google.android.apps.nbu.paisa.user")

        // Split-screen or payment bottom-sheet over an allowed browser window.
        assertTrue(
            FluenceAccessibilityService.evaluateWindowExclusion(
                listOf("com.android.chrome", "com.google.android.apps.nbu.paisa.user")
            ) { pkg -> pkg != null && pkg in excluded }
        )
        assertTrue(
            FluenceAccessibilityService.evaluateWindowExclusion(
                listOf("com.google.android.apps.nbu.paisa.user", "com.android.chrome")
            ) { pkg -> pkg != null && pkg in excluded }
        )
    }

    @Test
    fun evaluateWindowExclusion_returnsFalseWhenAllWindowsAllowedOrBlank() {
        val excluded = setOf("com.example.bank")

        assertFalse(
            FluenceAccessibilityService.evaluateWindowExclusion(
                listOf("com.android.chrome", null, "", "   ")
            ) { pkg -> pkg != null && pkg in excluded }
        )
        assertFalse(
            FluenceAccessibilityService.evaluateWindowExclusion(emptyList()) { pkg ->
                pkg != null && pkg in excluded
            }
        )
    }

    @Test
    fun resolveEffectiveNodePackage_prefersTrimmedNodePackageAndFallsBack() {
        assertEquals(
            "com.example.notes",
            FluenceAccessibilityService.resolveEffectiveNodePackage(
                "  com.example.notes ",
                "com.example.fallback"
            )
        )
        assertEquals(
            "com.example.webviewhost",
            FluenceAccessibilityService.resolveEffectiveNodePackage(
                null,
                "  com.example.webviewhost  "
            )
        )
        assertEquals(
            "com.example.composehost",
            FluenceAccessibilityService.resolveEffectiveNodePackage(
                "   ",
                "com.example.composehost"
            )
        )
        assertNull(FluenceAccessibilityService.resolveEffectiveNodePackage(null, "  "))
        assertNull(FluenceAccessibilityService.resolveEffectiveNodePackage("", null))
    }

    @Test
    fun shouldEvaluateOnWindowsChanged_gatesIdleChurnAndCatchesActiveSurfaces() {
        assertFalse(
            FluenceAccessibilityService.shouldEvaluateOnWindowsChanged(
                imeOnlyEnabled = false,
                isBubbleVisible = false,
                hasActiveBubbleSession = false,
                recordingState = RecordingState.IDLE
            )
        )
        assertTrue(
            FluenceAccessibilityService.shouldEvaluateOnWindowsChanged(
                imeOnlyEnabled = false,
                isBubbleVisible = true,
                hasActiveBubbleSession = false,
                recordingState = RecordingState.IDLE
            )
        )
        assertTrue(
            FluenceAccessibilityService.shouldEvaluateOnWindowsChanged(
                imeOnlyEnabled = false,
                isBubbleVisible = false,
                hasActiveBubbleSession = true,
                recordingState = RecordingState.IDLE
            )
        )
        assertTrue(
            FluenceAccessibilityService.shouldEvaluateOnWindowsChanged(
                imeOnlyEnabled = false,
                isBubbleVisible = false,
                hasActiveBubbleSession = false,
                recordingState = RecordingState.RECORDING
            )
        )
        assertTrue(
            FluenceAccessibilityService.shouldEvaluateOnWindowsChanged(
                imeOnlyEnabled = true,
                isBubbleVisible = false,
                hasActiveBubbleSession = false,
                recordingState = RecordingState.IDLE
            )
        )
    }

    @Test
    fun privacyPreferences_normalizesWhitespaceOnSetAndCheck() {
        PrivacyPreferences.setPackageExcluded(context, "  com.example.bank  ", true)

        assertTrue(storedPackages.contains("com.example.bank"))
        assertEquals(setOf("com.example.bank"), PrivacyPreferences.getExcludedPackages(context))
        assertTrue(PrivacyPreferences.isPackageExcluded(context, "com.example.bank"))
        assertTrue(PrivacyPreferences.isPackageExcluded(context, " com.example.bank\n"))

        PrivacyPreferences.setPackageExcluded(context, "   ", true)
        assertEquals(setOf("com.example.bank"), PrivacyPreferences.getExcludedPackages(context))

        PrivacyPreferences.setPackageExcluded(context, "\tcom.example.bank ", false)
        assertFalse(PrivacyPreferences.isPackageExcluded(context, "com.example.bank"))
        assertFalse(storedPackages.contains("com.example.bank"))
    }
}
