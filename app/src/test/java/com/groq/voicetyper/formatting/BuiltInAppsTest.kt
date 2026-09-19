package com.groq.voicetyper.formatting

import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltInAppsTest {

    @Test
    fun `email apps group to EMAIL`() {
        assertEquals(AppGroup.EMAIL, BuiltInApps.groupFor("com.google.android.gm"))
        assertEquals(AppGroup.EMAIL, BuiltInApps.groupFor("com.microsoft.office.outlook"))
    }

    @Test
    fun `chat apps group to PERSONAL`() {
        assertEquals(AppGroup.PERSONAL, BuiltInApps.groupFor("com.whatsapp"))
        assertEquals(AppGroup.PERSONAL, BuiltInApps.groupFor("org.telegram.messenger"))
        assertEquals(AppGroup.PERSONAL, BuiltInApps.groupFor("com.discord"))
    }

    @Test
    fun `work apps group to WORK`() {
        assertEquals(AppGroup.WORK, BuiltInApps.groupFor("com.Slack"))
        assertEquals(AppGroup.WORK, BuiltInApps.groupFor("com.microsoft.teams"))
        assertEquals(AppGroup.WORK, BuiltInApps.groupFor("com.linkedin.android"))
    }

    @Test
    fun `unknown null and blank group to OTHER`() {
        assertEquals(AppGroup.OTHER, BuiltInApps.groupFor("com.example.notes"))
        assertEquals(AppGroup.OTHER, BuiltInApps.groupFor(null))
        assertEquals(AppGroup.OTHER, BuiltInApps.groupFor("  "))
    }

    @Test
    fun `group default styles match Wispr conventions`() {
        assertEquals(FormattingCategory.FORMAL, AppGroup.defaultStyle(AppGroup.EMAIL))
        assertEquals(FormattingCategory.CASUAL, AppGroup.defaultStyle(AppGroup.PERSONAL))
        assertEquals(FormattingCategory.CASUAL, AppGroup.defaultStyle(AppGroup.WORK))
        assertEquals(FormattingCategory.NEUTRAL, AppGroup.defaultStyle(AppGroup.OTHER))
    }
}
