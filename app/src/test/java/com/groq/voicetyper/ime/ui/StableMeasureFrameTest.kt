package com.groq.voicetyper.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class StableMeasureFrameTest {

    private lateinit var helper: StableMeasureHelper

    @Before
    fun setUp() {
        helper = StableMeasureHelper()
    }

    private fun atMost(size: Int): Int =
        StableMeasureHelper.MeasureSpecCompat.makeMeasureSpec(size, StableMeasureHelper.MeasureSpecCompat.AT_MOST)

    private fun sizeOf(spec: Int): Int =
        StableMeasureHelper.MeasureSpecCompat.getSize(spec)

    @Test
    fun dualPass_secondPassOfferedSmaller_roomSizeRemainsFirstOffer() {
        // Pass 1: Platform offers whole screen (e.g. 2400)
        val pass1 = helper.stableHeightSpec(atMost(2400), currentMeasuredHeight = 600)
        assertEquals(2400, sizeOf(pass1))

        // Pass 2: Platform offers remaining height (e.g. 1512)
        // StableMeasureFrame hands back the larger roomSize (2400) so Compose sees consistent constraints!
        val pass2 = helper.stableHeightSpec(atMost(1512), currentMeasuredHeight = 600)
        assertEquals(2400, sizeOf(pass2))
    }

    @Test
    fun secondPass_heldBelowContentHeight_updatesCeiling() {
        // First pass offers 1220. Content wanted 1150 (currentMeasuredHeight = 1150)
        val pass1 = helper.stableHeightSpec(atMost(1220), currentMeasuredHeight = 1150)
        assertEquals(1220, sizeOf(pass1))

        // Second pass offers 1090 (less than 1150 content height -> real system ceiling)
        val pass2 = helper.stableHeightSpec(atMost(1090), currentMeasuredHeight = 1150)
        assertEquals(1090, sizeOf(pass2))

        // End of traversal
        helper.onLayoutFinished()

        // Next traversal under same first offer (1220): ceiling 1090 is remembered so both passes agree!
        val nextPass1 = helper.stableHeightSpec(atMost(1220), currentMeasuredHeight = 1090)
        assertEquals(1090, sizeOf(nextPass1))
    }

    @Test
    fun newFirstOffer_forgetsHeldCeiling() {
        // Establish ceiling under 1220
        helper.stableHeightSpec(atMost(1220), currentMeasuredHeight = 1150)
        helper.stableHeightSpec(atMost(1090), currentMeasuredHeight = 1150)
        helper.onLayoutFinished()

        // Screen rotates upright: first offer changes to 2400. Ceiling is forgotten!
        val rotatedPass1 = helper.stableHeightSpec(atMost(2400), currentMeasuredHeight = 1090)
        assertEquals(2400, sizeOf(rotatedPass1))
    }

    @Test
    fun onShownAgain_resetsCeiling() {
        helper.stableHeightSpec(atMost(1220), currentMeasuredHeight = 1150)
        helper.stableHeightSpec(atMost(1090), currentMeasuredHeight = 1150)
        helper.onLayoutFinished()

        // Window re-shown
        helper.onShownAgain()

        val pass1 = helper.stableHeightSpec(atMost(1220), currentMeasuredHeight = 1090)
        assertEquals(1220, sizeOf(pass1))
    }
}
