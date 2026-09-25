package com.groq.voicetyper.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

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

// ── System reduced-motion signal ────────────────────────────────────────────
// Single source of truth for the "remove animations" accessibility toggle
// (ANIMATOR_DURATION_SCALE == 0). FluenceTranscribeTheme provisions it
// app-wide via LocalMotionPreferences; overlay/IME surfaces composed without
// the theme wrapper read it here instead, so every surface honors the signal.
fun isSystemReducedMotion(context: Context): Boolean =
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f
    ) == 0f

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember { androidx.compose.runtime.mutableStateOf(isSystemReducedMotion(context)) }
    androidx.compose.runtime.DisposableEffect(context) {
        val uri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val observer = object : android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper())
        ) {
            override fun onChange(selfChange: Boolean) {
                reduced = isSystemReducedMotion(context)
            }
        }
        context.contentResolver.registerContentObserver(uri, false, observer)
        reduced = isSystemReducedMotion(context)
        onDispose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    return reduced
}

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
