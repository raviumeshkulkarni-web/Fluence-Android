package com.groq.voicetyper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformBarMathTest {

    @Test
    fun barCount_isOddAndSymmetricAcrossWidths() {
        val pillCount = WaveformBarMath.barCountForWidthDp(120f)
        assertEquals(13, pillCount)
        assertTrue(pillCount % 2 == 1)

        val compactCount = WaveformBarMath.barCountForWidthDp(56f)
        assertTrue(compactCount in WaveformBarMath.MIN_BARS..WaveformBarMath.MAX_BARS)
        assertTrue(compactCount % 2 == 1)

        // Safe fallback on zero/negative width during expand morph
        assertEquals(WaveformBarMath.DEFAULT_BARS, WaveformBarMath.barCountForWidthDp(0f))
        assertEquals(WaveformBarMath.DEFAULT_BARS, WaveformBarMath.barCountForWidthDp(-10f))
    }

    @Test
    fun outerBars_reactVisiblyUnderConversationalSpeech() {
        val count = WaveformBarMath.barCountForWidthDp(120f)
        val outerU = WaveformBarMath.barPositionFraction(0, count)

        val idleSamples = (0..20).map { step ->
            WaveformBarMath.barTargetFraction(outerU, step * 0.4f, rawLevel = 0f)
        }
        val speechSamples = (0..20).map { step ->
            WaveformBarMath.barTargetFraction(outerU, step * 0.4f, rawLevel = 0.50f)
        }

        val maxIdle = idleSamples.max()
        val maxSpeech = speechSamples.max()
        val minSpeech = speechSamples.min()

        // Outer bars must lift meaningfully on speech and visibly oscillate over phase
        assertTrue("Expected outer bar peak >= 0.40 on speech, got $maxSpeech", maxSpeech >= 0.40f)
        assertTrue("Expected outer bar lift over idle, idle=$maxIdle speech=$maxSpeech", maxSpeech - maxIdle >= 0.22f)
        assertTrue("Expected outer bar phase swing >= 0.15, got ${maxSpeech - minSpeech}", maxSpeech - minSpeech >= 0.15f)
    }

    @Test
    fun allBars_stayWithinUnitBoundsForExtremeInputs() {
        val count = 13
        for (i in 0 until count) {
            val u = WaveformBarMath.barPositionFraction(i, count)
            for (level in listOf(-1f, 0f, 0.25f, 0.7f, 1f, 3f)) {
                val frac = WaveformBarMath.barTargetFraction(u, phase = 2.5f, rawLevel = level)
                assertTrue("Fraction out of bounds: $frac", frac in 0.08f..1.0f)
            }
        }
    }

    @Test
    fun smoothApproach_isMonotonicAndBounded() {
        val rising = WaveformBarMath.smoothApproach(current = 0.2f, target = 0.8f, ratePerSec = 20f, dtSec = 0.016f)
        assertTrue(rising in 0.2f..0.8f)

        val falling = WaveformBarMath.smoothApproach(current = 0.8f, target = 0.2f, ratePerSec = 9f, dtSec = 0.016f)
        assertTrue(falling in 0.2f..0.8f)

        assertEquals(0.5f, WaveformBarMath.smoothApproach(0.5f, 1.0f, 20f, 0f), 1e-6f)
    }

    @Test
    fun horizontalInset_neverExceedsAvailableWidth() {
        assertEquals(0f, WaveformBarMath.horizontalInsetDp(0f), 1e-6f)
        assertEquals(10f, WaveformBarMath.horizontalInsetDp(120f), 1e-6f)
        assertEquals(2f, WaveformBarMath.horizontalInsetDp(20f), 1e-6f)
    }
}
