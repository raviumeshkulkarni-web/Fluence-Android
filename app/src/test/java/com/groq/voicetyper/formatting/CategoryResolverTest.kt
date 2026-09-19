package com.groq.voicetyper.formatting

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryResolverTest {

    @Test
    fun `package override wins over field type`() {
        val resolved = CategoryResolver.resolve(
            variation = CategoryResolver.VARIATION_EMAIL_ADDRESS,
            packageName = "com.example.chat",
            overrides = mapOf("com.example.chat" to FormattingCategory.VERY_CASUAL)
        )
        assertEquals(FormattingCategory.VERY_CASUAL, resolved)
    }

    @Test
    fun `email style variations resolve to formal`() {
        assertEquals(
            FormattingCategory.FORMAL,
            CategoryResolver.resolve(CategoryResolver.VARIATION_EMAIL_ADDRESS, "com.example.mail")
        )
        assertEquals(
            FormattingCategory.FORMAL,
            CategoryResolver.resolve(CategoryResolver.VARIATION_EMAIL_SUBJECT, "com.example.mail")
        )
        assertEquals(
            FormattingCategory.FORMAL,
            CategoryResolver.resolve(CategoryResolver.VARIATION_LONG_MESSAGE, "com.example.mail")
        )
    }

    @Test
    fun `short message resolves to casual`() {
        assertEquals(
            FormattingCategory.CASUAL,
            CategoryResolver.resolve(CategoryResolver.VARIATION_SHORT_MESSAGE, "com.example.chat")
        )
    }

    @Test
    fun `unknown variation resolves to neutral`() {
        assertEquals(
            FormattingCategory.NEUTRAL,
            CategoryResolver.resolve(0, "com.example.app")
        )
        assertEquals(
            FormattingCategory.NEUTRAL,
            CategoryResolver.resolve(999, "com.example.app")
        )
    }

    @Test
    fun `null variation and null package resolve to neutral`() {
        assertEquals(FormattingCategory.NEUTRAL, CategoryResolver.resolve(null, null))
        assertEquals(
            FormattingCategory.NEUTRAL,
            CategoryResolver.resolve(null, null, mapOf("com.example.chat" to FormattingCategory.FORMAL))
        )
    }

    @Test
    fun `blank package never matches overrides`() {
        val resolved = CategoryResolver.resolve(
            variation = CategoryResolver.VARIATION_SHORT_MESSAGE,
            packageName = "  ",
            overrides = mapOf("  " to FormattingCategory.FORMAL)
        )
        assertEquals(FormattingCategory.CASUAL, resolved)
    }

    @Test
    fun `built-in email packages resolve to formal without overrides`() {
        assertEquals(
            FormattingCategory.FORMAL,
            CategoryResolver.resolve(null, "com.google.android.gm")
        )
        assertEquals(
            FormattingCategory.FORMAL,
            CategoryResolver.resolve(null, "com.microsoft.office.outlook")
        )
    }

    @Test
    fun `built-in chat and work packages resolve to casual without overrides`() {
        assertEquals(
            FormattingCategory.CASUAL,
            CategoryResolver.resolve(null, "com.whatsapp")
        )
        assertEquals(
            FormattingCategory.CASUAL,
            CategoryResolver.resolve(null, "org.telegram.messenger")
        )
        assertEquals(
            FormattingCategory.CASUAL,
            CategoryResolver.resolve(null, "com.Slack")
        )
    }

    @Test
    fun `user override wins over built-in defaults`() {
        assertEquals(
            FormattingCategory.VERY_CASUAL,
            CategoryResolver.resolve(
                variation = null,
                packageName = "com.whatsapp",
                overrides = mapOf("com.whatsapp" to FormattingCategory.VERY_CASUAL)
            )
        )
        assertEquals(
            FormattingCategory.NEUTRAL,
            CategoryResolver.resolve(
                variation = CategoryResolver.VARIATION_EMAIL_ADDRESS,
                packageName = "com.google.android.gm",
                overrides = mapOf("com.google.android.gm" to FormattingCategory.NEUTRAL)
            )
        )
    }

    @Test
    fun `unlisted package with null variation resolves to neutral`() {
        assertEquals(
            FormattingCategory.NEUTRAL,
            CategoryResolver.resolve(null, "com.example.notes")
        )
    }
}
