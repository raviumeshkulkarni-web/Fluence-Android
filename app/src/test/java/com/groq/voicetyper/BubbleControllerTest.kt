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
import kotlin.math.roundToInt

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

    // ── Card geometry: snap targets are edge-flush, never center ────────────
    // These encode the structural guarantee that replaced the alpha-gate
    // workaround: the bubble can only ever rest flush at a screen edge, so
    // no intermediate frame can show it at the center of the screen.
    private val geomPadding = (16 * 3f).toInt()
    private val geomCollapsed = (56 * 3f).toInt()
    private val geomCardW = (272 * 3f).roundToInt()
    private val geomScreenW = 1080
    private val geomScreenH = 2400

    private fun bubbleLeftAt(targetX: Int, anchoredRight: Boolean): Int =
        targetX + BubbleController.cardInnerOffsetX(anchoredRight, geomCardW, geomPadding, geomCollapsed)

    @Test
    fun snapTarget_rightAnchor_placesBubbleFlushRight() {
        val targetX = BubbleController.snapTargetCardX(true, geomScreenW, geomCardW, geomPadding)
        assertEquals(geomScreenW - geomCollapsed, bubbleLeftAt(targetX, true))
    }

    @Test
    fun snapTarget_leftAnchor_placesBubbleFlushLeft() {
        val targetX = BubbleController.snapTargetCardX(false, geomScreenW, geomCardW, geomPadding)
        assertEquals(0, bubbleLeftAt(targetX, false))
    }

    @Test
    fun snapTargets_neverCentered_acrossScreenSizes() {
        for (screenW in listOf(720, 1080, 1440)) {
            for (anchoredRight in listOf(true, false)) {
                val targetX = BubbleController.snapTargetCardX(anchoredRight, screenW, geomCardW, geomPadding)
                val bubbleCenter = bubbleLeftAt(targetX, anchoredRight) + geomCollapsed / 2
                val distanceFromCenter = kotlin.math.abs(bubbleCenter - screenW / 2)
                assertTrue(
                    "bubble center $bubbleCenter too close to screen center ${screenW / 2}",
                    distanceFromCenter > screenW / 4
                )
            }
        }
    }

    @Test
    fun sideClassification_matchesSnapTargets() {
        val rightBubble = bubbleLeftAt(
            BubbleController.snapTargetCardX(true, geomScreenW, geomCardW, geomPadding), true
        )
        assertFalse(BubbleController.isLeftSide(rightBubble, geomCollapsed, geomScreenW))
        val leftBubble = bubbleLeftAt(
            BubbleController.snapTargetCardX(false, geomScreenW, geomCardW, geomPadding), false
        )
        assertTrue(BubbleController.isLeftSide(leftBubble, geomCollapsed, geomScreenW))
    }

    @Test
    fun clampCardX_keepsBubbleOnScreen_andAllowsFullTraverse() {
        // Extreme drags clamp so the bubble stays within [0, screenW - collapsed].
        val leftClamped = BubbleController.clampCardX(-100000, false, geomCardW, geomPadding, geomCollapsed, geomScreenW)
        assertEquals(0, bubbleLeftAt(leftClamped, false))
        val rightClamped = BubbleController.clampCardX(100000, true, geomCardW, geomPadding, geomCollapsed, geomScreenW)
        assertEquals(geomScreenW - geomCollapsed, bubbleLeftAt(rightClamped, true))
        // A right-anchored card dragged fully left reaches the left edge
        // without any side flip (flip happens only on release).
        val traversed = BubbleController.clampCardX(
            BubbleController.snapTargetCardX(true, geomScreenW, geomCardW, geomPadding) - 100000,
            true, geomCardW, geomPadding, geomCollapsed, geomScreenW
        )
        assertEquals(0, bubbleLeftAt(traversed, true))
    }

    @Test
    fun clampCardY_keepsBubbleVerticallyOnScreen() {
        val top = BubbleController.clampCardY(-100000, geomPadding, geomCollapsed, geomScreenH)
        assertEquals(0, top + geomPadding)
        val bottom = BubbleController.clampCardY(100000, geomPadding, geomCollapsed, geomScreenH)
        assertEquals(geomScreenH - geomCollapsed, bottom + geomPadding)
    }

    // ── Side-flip continuity: the flip moves only the card, never the bubble ──
    // Regression guard for the center-dwell defect: the snap must settle the
    // bubble release-point -> edge under the OLD alignment, and the flip must
    // leave the bubble exactly where the snap put it (jump == 0). Any nonzero
    // jump here renders as a center dwell + terminal teleport on device.
    private fun bubbleAt(cardX: Int, anchoredRight: Boolean, cardW: Int, pad: Int, coll: Int): Int =
        cardX + BubbleController.cardInnerOffsetX(anchoredRight, cardW, pad, coll)

    @Test
    fun sideFlip_isBubbleStationary_acrossScreenSizes() {
        for (screenW in listOf(720, 1080, 1220, 1440)) {
            for (finalRight in listOf(true, false)) {
                val oldSide = !finalRight
                val edge = BubbleController.edgeBubbleLeftPx(finalRight, screenW, geomCollapsed)
                // Bubble position at end of snap (old alignment still active).
                val settledBubble = bubbleAt(
                    BubbleController.settleCardX(edge, oldSide, geomCardW, geomPadding, geomCollapsed),
                    oldSide, geomCardW, geomPadding, geomCollapsed
                )
                assertEquals("snap must end exactly on the edge", edge, settledBubble)
                // Bubble position after the flip (new alignment, post-flip rest).
                val flippedBubble = bubbleAt(
                    BubbleController.snapTargetCardX(finalRight, screenW, geomCardW, geomPadding),
                    finalRight, geomCardW, geomPadding, geomCollapsed
                )
                assertEquals("flip must not move the bubble", edge, flippedBubble)
            }
        }
    }

    @Test
    fun nearEdgeCrossSideRelease_settlesWithShortTravel_noJump() {
        // Exact repro from the on-device trace (1220px wide display,
        // 765px card, 45px padding, 157px collapsed bubble):
        // released 22px short of the right edge while left-anchored.
        val screenW = 1220
        val cardWDevice = 765
        val pad = 45
        val coll = 157
        val releaseCardX = 996
        val releaseBubble = bubbleAt(releaseCardX, false, cardWDevice, pad, coll)
        assertEquals(1041, releaseBubble)
        val edge = BubbleController.edgeBubbleLeftPx(true, screenW, coll)
        assertEquals(1063, edge)
        // The visible snap travels only the 22px remainder, not back through center.
        val settleX = BubbleController.settleCardX(edge, false, cardWDevice, pad, coll)
        assertEquals(1018, settleX)
        assertEquals(22, edge - releaseBubble)
        // The flip lands the bubble exactly on the edge: zero teleport.
        val restX = BubbleController.snapTargetCardX(true, screenW, cardWDevice, pad)
        assertEquals(edge, bubbleAt(restX, true, cardWDevice, pad, coll))
    }

    @Test
    fun sameSideRelease_settleEqualsRest_zeroJump() {
        for (anchoredRight in listOf(true, false)) {
            val edge = BubbleController.edgeBubbleLeftPx(anchoredRight, geomScreenW, geomCollapsed)
            assertEquals(
                BubbleController.snapTargetCardX(anchoredRight, geomScreenW, geomCardW, geomPadding),
                BubbleController.settleCardX(edge, anchoredRight, geomCardW, geomPadding, geomCollapsed)
            )
        }
    }
}
