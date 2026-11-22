package com.groq.voicetyper.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SpacebarScrubTrackerTest {

    private lateinit var tracker: SpacebarScrubTracker

    @Before
    fun setUp() {
        tracker = SpacebarScrubTracker(stepPx = 14f, reversalThresholdPx = 12f)
        tracker.start(100f)
    }

    @Test
    fun initialState_hasNotDragged_onUpReturnsFalse() {
        assertFalse(tracker.hasDragged)
        assertFalse(tracker.onUp())
    }

    @Test
    fun smallMovement_underStepPx_returnsZeroSteps() {
        val steps = tracker.onMove(105f) // +5px
        assertEquals(0, steps)
        assertFalse(tracker.hasDragged)
        assertFalse(tracker.onUp())
    }

    @Test
    fun dragRight_exceedingStepPx_emitsPositiveSteps() {
        val step1 = tracker.onMove(114f) // +14px exactly
        assertEquals(1, step1)
        assertTrue(tracker.hasDragged)

        val step2 = tracker.onMove(128f) // +14px more
        assertEquals(1, step2)
        assertTrue(tracker.onUp())
    }

    @Test
    fun dragLeft_exceedingStepPx_emitsNegativeSteps() {
        val step1 = tracker.onMove(86f) // -14px exactly
        assertEquals(-1, step1)
        assertTrue(tracker.hasDragged)

        val step2 = tracker.onMove(72f) // -14px more
        assertEquals(-1, step2)
        assertTrue(tracker.onUp())
    }

    @Test
    fun minorJitter_belowReversalThreshold_doesNotResetAccumulation() {
        // Move right +10px (under 14px step)
        tracker.onMove(110f)

        // Slight hand wobble left of 4px (below 12px reversal threshold)
        val jitterStep = tracker.onMove(106f)
        assertEquals(0, jitterStep)

        // Resume moving right by 8px (total +14px from start) -> step should fire
        val resumeStep = tracker.onMove(114f)
        assertEquals(1, resumeStep)
    }

    @Test
    fun reversal_exceedingThreshold_countsReversalTravelImmediately() {
        // Drag right: move +20px -> 1 step (+14px), leaving +6px accumulated
        val stepRight = tracker.onMove(120f)
        assertEquals(1, stepRight)

        // Now user reverses left by 16px (reversalThreshold is 12px, step is 14px)
        // Position changes from 120f to 104f (-16px)
        // Reversal reset triggers AND counts the 16px travel in the new direction -> fires -1 step!
        val reversalMove = tracker.onMove(104f)
        assertEquals(-1, reversalMove)

        // Moving left another 12px from 104f to 92f (remaining -2px + -12px = -14px)
        val stepLeft = tracker.onMove(92f)
        assertEquals(-1, stepLeft)
    }
}
