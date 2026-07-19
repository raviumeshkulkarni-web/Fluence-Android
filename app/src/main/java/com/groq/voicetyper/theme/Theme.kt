package com.groq.voicetyper.theme

import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ── Precision Colors — full surface ladder + text hierarchy + accent + semantic ──
// DESIGN_SYSTEM.md. Maps directly to semantic token names.
@Immutable
data class PrecisionColors(
    // Surface hierarchy
    val appBackground: Color,
    val canvas: Color,
    val sidebar: Color,
    val panel: Color,
    val panelElevated: Color,
    val dialog: Color,
    val dialogElevated: Color,
    val outlineSubtle: Color,
    // Text hierarchy
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    // Accent
    val brandAmethyst: Color,
    val brandCyan: Color,
    // Semantic
    val success: Color,
    val warning: Color,
    val error: Color,
)

val LocalPrecisionColors = staticCompositionLocalOf {
    PrecisionColors(
        appBackground = AppBackground,
        canvas = Canvas,
        sidebar = Sidebar,
        panel = Panel,
        panelElevated = PanelElevated,
        dialog = DialogSurface,
        dialogElevated = DialogElevated,
        outlineSubtle = OutlineSubtle,
        textPrimary = TextPrimary,
        textSecondary = TextSecondary,
        textTertiary = TextTertiary,
        textDisabled = TextDisabled,
        brandAmethyst = BrandAmethyst,
        brandCyan = BrandCyan,
        success = Success,
        warning = Warning,
        error = Error,
    )
}

// ── Convenience accessor ────────────────────────────────────────────────────
object PrecisionTheme {
    val colors: PrecisionColors
        @Composable get() = LocalPrecisionColors.current
}

// ── Material3 Dark Color Scheme — DESIGN_SYSTEM.md mapping ─────────────────
private val FluenceDarkColorScheme = darkColorScheme(
    primary            = BrandAmethyst,
    onPrimary          = TextPrimary,
    primaryContainer   = DialogSurface,
    onPrimaryContainer = TextPrimary,

    secondary          = PanelElevated,
    onSecondary        = TextPrimary,
    secondaryContainer = DialogSurface,
    onSecondaryContainer = TextPrimary,

    tertiary           = Success,
    onTertiary         = TextPrimary,
    tertiaryContainer  = DialogSurface,
    onTertiaryContainer = TextPrimary,

    background         = AppBackground,
    onBackground       = TextPrimary,

    surface            = Panel,
    onSurface          = TextPrimary,
    surfaceVariant     = PanelElevated,
    onSurfaceVariant   = TextSecondary,
    surfaceTint        = Panel,

    error              = Error,
    onError            = TextPrimary,
    errorContainer     = Error.copy(alpha = 0.15f),
    onErrorContainer   = TextPrimary,

    outline            = OutlineSubtle,
    outlineVariant     = OutlineSubtle,
    inverseSurface     = TextPrimary,
    inverseOnSurface   = AppBackground,
    inversePrimary     = BrandAmethyst,
    scrim              = Color.Black,
)

// ── Theme Composable ────────────────────────────────────────────────────────
@Composable
fun FluenceTranscribeTheme(
    content: @Composable () -> Unit
) {
    val precisionColors = PrecisionColors(
        appBackground = AppBackground,
        canvas = Canvas,
        sidebar = Sidebar,
        panel = Panel,
        panelElevated = PanelElevated,
        dialog = DialogSurface,
        dialogElevated = DialogElevated,
        outlineSubtle = OutlineSubtle,
        textPrimary = TextPrimary,
        textSecondary = TextSecondary,
        textTertiary = TextTertiary,
        textDisabled = TextDisabled,
        brandAmethyst = BrandAmethyst,
        brandCyan = BrandCyan,
        success = Success,
        warning = Warning,
        error = Error,
    )

    val context = LocalContext.current
    val motionPrefs = remember {
        val am = context.getSystemService(AccessibilityManager::class.java)
        MotionPreferences(reducedMotion = am?.isTouchExplorationEnabled == true)
    }

    CompositionLocalProvider(
        LocalPrecisionColors provides precisionColors,
        LocalFluenceFonts provides FluenceFonts(),
        LocalMotionPreferences provides motionPrefs
    ) {
        MaterialTheme(
            colorScheme = FluenceDarkColorScheme,
            typography  = FluenceTypography,
            content     = content
        )
    }
}
