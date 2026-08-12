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
    private val normalPackage = "com.example.normal"

    @Before
    fun setUp() {
        mockkObject(TranscriptionSessionManager)
        every { TranscriptionSessionManager.cancelPreWarm() } returns Unit
        every { TranscriptionSessionManager.preWarmOfflinePipeline(any()) } returns Unit

        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.packageName } returns normalPackage
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

    private fun normalNode(): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.packageName } returns normalPackage
        return node
    }

    @Test
    fun showBubble_withoutRecordAudioPermission_doesNotStartServiceAndStaysHidden() {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_DENIED
        resetBubbleState()

        BubbleController.showBubble(context, normalNode())

        assertFalse(BubbleController.isBubbleVisible.value)
        assertEquals(0, serviceCalls.size)
    }

    @Test
    fun showBubble_withRecordAudioPermission_startsServiceAndShowsBubble() {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        resetBubbleState()

        BubbleController.showBubble(context, normalNode())

        assertTrue(BubbleController.isBubbleVisible.value)
        assertEquals(1, serviceCalls.size)
    }

    @Test
    fun hideBubble_resetsStateAndDefersStopService() {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        resetBubbleState()
        BubbleController.showBubble(context, normalNode())
        serviceCalls.clear()

        BubbleController.hideBubble()

        assertFalse(BubbleController.isBubbleVisible.value)
        assertFalse(BubbleController.isBubbleExpanded.value)
        // stopService is deferred (not synchronous) so a rapid accessibility show/hide
        // flap cannot stop the FGS before startForeground() runs.
        assertFalse("stopService" in serviceCalls)
    }

    @Test
    fun showBubble_secondCall_doesNotRestartService() {
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        resetBubbleState()

        BubbleController.showBubble(context, normalNode())
        BubbleController.showBubble(context, normalNode())

        assertTrue(BubbleController.isBubbleVisible.value)
        assertEquals(1, serviceCalls.size)
    }

    @Test
    fun showBubble_excludedPackage_doesNotCacheOrStartService() {
        mockkObject(PrivacyPreferences)
        every { PrivacyPreferences.isPackageExcluded(any(), any()) } returns true
        resetBubbleState()

        BubbleController.showBubble(context, normalNode())

        assertFalse(BubbleController.isBubbleVisible.value)
        assertEquals(0, serviceCalls.size)
    }
}
