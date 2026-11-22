package com.groq.voicetyper

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
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
        sdkIntOverride = 33
        context = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        sdkIntOverride = null
        unmockkAll()
    }

    @Test
    fun rescue_legacyVersion_doesNotAttachExtras() {
        sdkIntOverride = 21
        mockkStatic(ClipData::class)
        val clip = mockk<ClipData>(relaxed = true)
        val desc = mockk<android.content.ClipDescription>(relaxed = true)
        every { clip.description } returns desc
        every { ClipData.newPlainText(any(), any()) } returns clip
        val clipboard = mockk<ClipboardManager>(relaxed = true)
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard

        assertTrue(rescueTranscriptToClipboard(context, "legacy test"))

        verify { clipboard.setPrimaryClip(clip) }
        verify(exactly = 0) { desc.extras = any() }
    }

    @Test
    fun rescue_writesTrimmedTextToClipboard() {
        mockkStatic(ClipData::class)
        val clip = mockk<ClipData>(relaxed = true)
        val desc = mockk<android.content.ClipDescription>(relaxed = true)
        every { clip.description } returns desc
        every { ClipData.newPlainText(any(), any()) } returns clip
        val clipboard = mockk<ClipboardManager>(relaxed = true)
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard

        assertTrue(rescueTranscriptToClipboard(context, "  hello  "))

        verify { ClipData.newPlainText("Fluence transcript", "hello") }
        verify { clipboard.setPrimaryClip(clip) }
    }

    @Test
    fun rescue_attachesSensitiveExtraOnSupportedVersions() {
        mockkStatic(ClipData::class)
        val clip = mockk<ClipData>(relaxed = true)
        val desc = mockk<android.content.ClipDescription>(relaxed = true)
        val bundle = mockk<PersistableBundle>(relaxed = true)
        every { desc.extras } returns bundle
        every { clip.description } returns desc
        every { ClipData.newPlainText(any(), any()) } returns clip
        val clipboard = mockk<ClipboardManager>(relaxed = true)
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard

        assertTrue(rescueTranscriptToClipboard(context, "confidential meeting notes"))

        verify { bundle.putBoolean("android.content.extra.IS_SENSITIVE", true) }
        verify { desc.extras = bundle }
        verify { clipboard.setPrimaryClip(clip) }
    }

    @Test
    fun rescue_blankTextIsNotCopied() {
        assertFalse(rescueTranscriptToClipboard(context, "   "))
    }
}
