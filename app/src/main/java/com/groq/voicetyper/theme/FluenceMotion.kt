package com.groq.voicetyper.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

// ── Fluence Motion Constants ────────────────────────────────────────────────
// DESIGN_SYSTEM.md §107–114 defines three motion tiers.
// ────────────────────────────────────────────────────────────────────────────

object FluenceMotion {
    // Immediate feedback: press, hover, toggle
    const val durationImmediate: Int = 125

    // Structural change: panel open, navigation, expand/collapse
    const val durationStructural: Int = 225

    // Large surface transition: dialog, sheet, modal entrance/exit
    const val durationLargeSurface: Int = 300
}

object FluenceEasing {
    val easingImmediate = FastOutSlowInEasing
    val easingStructural = FastOutSlowInEasing
    val easingLargeSurface = FastOutSlowInEasing
}

// ── Spring tokens ──────────────────────────────────────────────────────────
object FluenceSpring {
    val immediate = Spring.DampingRatioNoBouncy to Spring.StiffnessMedium
    val structural = Spring.DampingRatioMediumBouncy to Spring.StiffnessLow
}

// ── Reduced-motion accessibility ───────────────────────────────────────────

@Immutable
data class MotionPreferences(val reducedMotion: Boolean)

val LocalMotionPreferences = staticCompositionLocalOf { MotionPreferences(reducedMotion = false) }

// ── Pre-built animation specs ───────────────────────────────────────────────

val immediateTween = tween<Float>(
    durationMillis = 125,
    easing = FastOutSlowInEasing
)

val structuralTween = tween<Float>(
    durationMillis = 225,
    easing = FastOutSlowInEasing
)

val largeSurfaceTween = tween<Float>(
    durationMillis = 300,
    easing = FastOutSlowInEasing
)
