package com.groq.voicetyper.ime

import kotlin.math.abs

/**
 * State machine for spacebar cursor scrubbing with reversal reset inspired by wmkeyboard.
 *
 * When dragging along the spacebar to scrub the cursor:
 * - Emits cursor steps whenever accumulated movement in the current direction reaches [stepPx].
 * - When the user reverses drag direction by more than [reversalThresholdPx] (12px),
 *   the accumulated offset in the previous direction is reset, and the new run begins from
 *   the reversal point with the travel accumulated during the reversal, eliminating sluggish
 *   turnaround lag and allowing opposite-direction scrubbing to respond immediately.
 * - Wobbles and jitter smaller than [reversalThresholdPx] are tolerated without resetting.
 */
class SpacebarScrubTracker(
    val stepPx: Float,
    val reversalThresholdPx: Float = 12f
) {
    var hasDragged: Boolean = false
        private set

    private var lastX: Float = 0f
    private var accumulatedDx: Float = 0f
    private var reversalDistance: Float = 0f
    private var currentDirection: Int = 0 // 1 for right, -1 for left, 0 for initial

    fun start(initialX: Float) {
        lastX = initialX
        accumulatedDx = 0f
        reversalDistance = 0f
        currentDirection = 0
        hasDragged = false
    }

    fun onMove(currentX: Float): Int {
        val dx = currentX - lastX
        lastX = currentX
        if (dx == 0f) return 0

        val moveDir = if (dx > 0f) 1 else -1

        if (currentDirection == 0) {
            currentDirection = moveDir
            accumulatedDx += dx
            reversalDistance = 0f
        } else if (moveDir == currentDirection) {
            // Continuing in same direction: clear any pending reversal jitter
            accumulatedDx += dx
            reversalDistance = 0f
        } else {
            // Moving opposite to current direction
            reversalDistance += abs(dx)
            if (reversalDistance >= reversalThresholdPx) {
                // Reversal confirmed! Flip direction and initialize accumulatedDx with the travel in the new direction
                currentDirection = moveDir
                accumulatedDx = moveDir * reversalDistance
                reversalDistance = 0f
            }
        }

        if (abs(accumulatedDx) >= stepPx) {
            val steps = (accumulatedDx / stepPx).toInt()
            accumulatedDx -= steps * stepPx
            if (steps != 0) {
                hasDragged = true
                currentDirection = if (steps > 0) 1 else -1
            }
            return steps
        }

        return 0
    }

    fun onUp(): Boolean {
        return hasDragged
    }
}
