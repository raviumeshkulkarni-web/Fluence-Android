package com.groq.voicetyper.formatting

import android.content.Context
import android.content.SharedPreferences
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AppAwareFormatterTest {

    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()
    private var masterBacking = false
    private val stylesBacking = mutableSetOf<String>()
    private val overridesBacking = mutableSetOf<String>()

    @Before
    fun setUp() {
        FormattingPreferences.resetForTests()
        masterBacking = false
        stylesBacking.clear()
        overridesBacking.clear()
        every { context.applicationContext } returns context
        every {
            context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
        } returns preferences
        every {
            preferences.getBoolean("formatting_master_enabled", false)
        } answers { masterBacking }
        every {
            preferences.getStringSet("formatting_category_styles", any())
        } answers { stylesBacking.toSet() }
        every {
            preferences.getStringSet("formatting_package_overrides", any())
        } answers { overridesBacking.toSet() }
        every { preferences.registerOnSharedPreferenceChangeListener(any()) } just Runs
        every { preferences.unregisterOnSharedPreferenceChangeListener(any()) } just Runs
        every { preferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers {
            masterBacking = secondArg()
            editor
        }
        every { editor.putStringSet(any(), any()) } answers {
            val values = secondArg<Set<String>>().toMutableSet()
            if (firstArg<String>() == "formatting_category_styles") {
                stylesBacking.clear()
                stylesBacking.addAll(values)
            } else {
                overridesBacking.clear()
                overridesBacking.addAll(values)
            }
            editor
        }
        every { editor.apply() } just Runs
    }

    @After
    fun tearDown() {
        FormattingPreferences.resetForTests()
    }

    @Test
    fun `neutral returns input unchanged`() {
        assertEquals("hello World.", AppAwareFormatter.format("hello World.", FormattingCategory.NEUTRAL))
        assertEquals("", AppAwareFormatter.format("", FormattingCategory.NEUTRAL))
    }

    @Test
    fun `formal capitalizes first letter and ensures trailing period`() {
        assertEquals("Hello world.", AppAwareFormatter.format("hello world", FormattingCategory.FORMAL))
    }

    @Test
    fun `formal capitalizes after sentence punctuation`() {
        assertEquals(
            "Hello. How are you?",
            AppAwareFormatter.format("hello. how are you?", FormattingCategory.FORMAL)
        )
    }

    @Test
    fun `formal keeps existing terminators`() {
        assertEquals("Hello!", AppAwareFormatter.format("hello!", FormattingCategory.FORMAL))
        assertEquals("Really?", AppAwareFormatter.format("really?", FormattingCategory.FORMAL))
        assertEquals("Done. ", AppAwareFormatter.format("done. ", FormattingCategory.FORMAL))
    }

    @Test
    fun `formal collapses single trailing space to period space`() {
        assertEquals("Hello. ", AppAwareFormatter.format("hello ", FormattingCategory.FORMAL))
    }

    @Test
    fun `formal never deletes words`() {
        val input = "quarterly report needs review  "
        val output = AppAwareFormatter.format(input, FormattingCategory.FORMAL)
        assertEquals("Quarterly report needs review.", output)
    }

    @Test
    fun `casual keeps caps but strips single trailing period`() {
        assertEquals("Hello World", AppAwareFormatter.format("Hello World.", FormattingCategory.CASUAL))
    }

    @Test
    fun `casual leaves exclamation ellipsis and questions alone`() {
        assertEquals("Wow!", AppAwareFormatter.format("Wow!", FormattingCategory.CASUAL))
        assertEquals("wait..", AppAwareFormatter.format("wait..", FormattingCategory.CASUAL))
        assertEquals("Really?", AppAwareFormatter.format("Really?", FormattingCategory.CASUAL))
    }

    @Test
    fun `very casual lowercases first char and strips trailing period`() {
        assertEquals("hello World", AppAwareFormatter.format("Hello World.", FormattingCategory.VERY_CASUAL))
        assertEquals("already quiet", AppAwareFormatter.format("Already quiet", FormattingCategory.VERY_CASUAL))
    }

    @Test
    fun `empty input passes through every category`() {
        for (category in FormattingCategory.values()) {
            assertEquals("", AppAwareFormatter.format("", category))
        }
    }

    @Test
    fun `overlong input returns unchanged`() {
        val long = "a".repeat(AppAwareFormatter.MAX_LENGTH + 1)
        assertEquals(long, AppAwareFormatter.format(long, FormattingCategory.FORMAL))
        assertEquals(long, AppAwareFormatter.format(long, FormattingCategory.VERY_CASUAL))
    }

    @Test
    fun `maybeFormat with master off returns input unchanged`() {
        assertEquals(
            "hello world",
            AppAwareFormatter.maybeFormat(
                context,
                "com.example.mail",
                "hello world",
                CategoryResolver.VARIATION_EMAIL_ADDRESS
            )
        )
    }

    @Test
    fun `maybeFormat with master on applies field type style`() {
        FormattingPreferences.setMasterEnabled(context, true)
        assertEquals(
            "Hello.",
            AppAwareFormatter.maybeFormat(
                context,
                "com.example.mail",
                "hello",
                CategoryResolver.VARIATION_EMAIL_ADDRESS
            )
        )
    }

    @Test
    fun `maybeFormat with master on leaves neutral fields alone`() {
        FormattingPreferences.setMasterEnabled(context, true)
        assertEquals(
            "hello world",
            AppAwareFormatter.maybeFormat(context, "com.example.app", "hello world", null)
        )
    }

    @Test
    fun `maybeFormat package override wins`() {
        FormattingPreferences.setMasterEnabled(context, true)
        FormattingPreferences.setOverride(context, "com.example.mail", FormattingCategory.VERY_CASUAL)
        assertEquals(
            "hello",
            AppAwareFormatter.maybeFormat(
                context,
                "com.example.mail",
                "Hello.",
                CategoryResolver.VARIATION_EMAIL_ADDRESS
            )
        )
    }

    @Test
    fun `maybeFormat detected category is the output style`() {
        FormattingPreferences.setMasterEnabled(context, true)
        // com.google.android.gm is a built-in Formal app: no remap step, the
        // detected category formats directly.
        assertEquals(
            "Hello.",
            AppAwareFormatter.maybeFormat(context, "com.google.android.gm", "hello", null)
        )
    }
}
