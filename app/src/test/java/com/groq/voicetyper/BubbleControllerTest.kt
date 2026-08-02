package com.groq.voicetyper

import android.content.Context
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BubbleControllerTest {

    private lateinit var context: Context
    private val serviceCalls = mutableListOf<String>()

    @Before
    fun setUp() {
        mockkObject(TranscriptionSessionManager)
        every { TranscriptionSessionManager.cancelPreWarm() } returns Unit
        every { TranscriptionSessionManager.preWarmOfflinePipeline(any()) } returns Unit

        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.startForegroundService(any()) } answers {
            serviceCalls += "startForegroundService"
            null
        }
        every { context.startService(any()) } answers {
            serviceCalls += "startService"
            null
        }
        every { context.stopService(any()) } answers {
            serviceCalls += "stopService"
            true
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun resetBubbleState() {
        BubbleController.hideBubble()
        serviceCalls.clear()
    }

    @Test
    fun showBubble_withoutRecordAudioPermission_doesNotStartServiceAndStaysHidden() {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        resetBubbleState()

        BubbleController.showBubble(context, mockk(relaxed = true))

        assertFalse(BubbleController.isBubbleVisible.value)
        assertEquals(0, serviceCalls.size)
    }

    @Test
    fun showBubble_withRecordAudioPermission_startsServiceAndShowsBubble() {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        resetBubbleState()

        BubbleController.showBubble(context, mockk(relaxed = true))

        assertTrue(BubbleController.isBubbleVisible.value)
        assertEquals(1, serviceCalls.size)
    }

    @Test
    fun hideBubble_resetsStateAndStopsService() {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        resetBubbleState()
        BubbleController.showBubble(context, mockk(relaxed = true))
        serviceCalls.clear()

        BubbleController.hideBubble()

        assertFalse(BubbleController.isBubbleVisible.value)
        assertFalse(BubbleController.isBubbleExpanded.value)
        assertTrue("stopService" in serviceCalls)
    }

    @Test
    fun showBubble_secondCall_doesNotRestartService() {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        resetBubbleState()

        BubbleController.showBubble(context, mockk(relaxed = true))
        BubbleController.showBubble(context, mockk(relaxed = true))

        assertTrue(BubbleController.isBubbleVisible.value)
        assertEquals(1, serviceCalls.size)
    }
}
