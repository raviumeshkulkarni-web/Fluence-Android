package com.groq.voicetyper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TranscriptRescueTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun rescue_writesTrimmedTextToClipboard() {
        mockkStatic(ClipData::class)
        val clip = mockk<ClipData>(relaxed = true)
        every { ClipData.newPlainText(any(), any()) } returns clip
        val clipboard = mockk<ClipboardManager>(relaxed = true)
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard

        assertTrue(rescueTranscriptToClipboard(context, "  hello  "))

        verify { ClipData.newPlainText("Fluence transcript", "hello") }
        verify { clipboard.setPrimaryClip(clip) }
    }

    @Test
    fun rescue_blankTextIsNotCopied() {
        assertFalse(rescueTranscriptToClipboard(context, "   "))
    }
}
