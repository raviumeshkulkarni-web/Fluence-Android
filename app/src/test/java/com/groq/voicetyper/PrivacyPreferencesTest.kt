package com.groq.voicetyper

import android.content.Context
import android.content.SharedPreferences
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PrivacyPreferencesTest {
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
            preferences.getStringSet("privacy_excluded_packages", any())
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
    fun excludedPackages_persistAndCanBeRemoved() {
        PrivacyPreferences.setPackageExcluded(context, "com.example.bank", true)

        assertTrue(PrivacyPreferences.isPackageExcluded(context, "com.example.bank"))
        assertTrue(storedPackages.contains("com.example.bank"))

        PrivacyPreferences.resetForTests()
        assertTrue(PrivacyPreferences.isPackageExcluded(context, "com.example.bank"))

        PrivacyPreferences.setPackageExcluded(context, "com.example.bank", false)
        assertFalse(PrivacyPreferences.isPackageExcluded(context, "com.example.bank"))
        assertFalse(storedPackages.contains("com.example.bank"))
    }

    @Test
    fun unknownPackage_isNotExcluded() {
        assertFalse(PrivacyPreferences.isPackageExcluded(context, null))
        assertFalse(PrivacyPreferences.isPackageExcluded(context, ""))
    }
}
