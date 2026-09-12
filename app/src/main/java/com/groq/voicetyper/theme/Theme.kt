package com.groq.voicetyper.theme

import android.content.SharedPreferences
import android.provider.Settings
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.ripple.LocalRippleTheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material.ripple.RippleTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// ── Precision Colors — full surface ladder + text hierarchy + accent + semantic ──
// DESIGN_SYSTEM.md. Maps directly to semantic token names. `isLight` selects
// the Windows [data-theme="light"] twin values; dark (default) is frozen.
// textLink: dark renders BrandAmethyst per the closed-list link-text rule
// (no dedicated dark link token exists); light renders #6D28D9 (Windows parity).
// charcoal: light-mode primary-button + toggle-ON fill (#3F3F46); in dark it
// mirrors textPrimary because dark reserves white for primary buttons.
// chartDuo*: activity-chart ramp — muted trio in dark, deepened stops in light.
@Immutable
data class PrecisionColors(
    val isLight: Boolean = false,
    // Surface hierarchy
    val appBackground: Color,
    val canvas: Color,
    val sidebar: Color,
    val panel: Color,
    val panelElevated: Color,
    val dialog: Color,
    val dialogElevated: Color,
    val outlineSubtle: Color,
    val cardSurface: Color,
    val cardBorder: Color,
    val inputBg: Color,
    val buttonSecondary: Color,
    val buttonSubtle: Color,
    // Text hierarchy
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    val textLink: Color,
    // Accent
    val brandAmethyst: Color,
    val brandCyan: Color,
    val charcoal: Color,
    val chartDuoStart: Color,
    val chartDuoMid: Color,
    val chartDuoEnd: Color,
    // Semantic
    val success: Color,
    val warning: Color,
    val error: Color,
    val errorText: Color,
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
        cardSurface = CardSurface,
        cardBorder = CardBorder,
        inputBg = InputBg,
        buttonSecondary = ButtonSecondary,
        buttonSubtle = ButtonSubtle,
        textPrimary = TextPrimary,
        textSecondary = TextSecondary,
        textTertiary = TextTertiary,
        textDisabled = TextDisabled,
        textLink = BrandAmethyst,
        brandAmethyst = BrandAmethyst,
        brandCyan = BrandCyan,
        charcoal = TextPrimary,
        chartDuoStart = ChartDuoStart,
        chartDuoMid = ChartDuoMid,
        chartDuoEnd = ChartDuoEnd,
        success = Success,
        warning = Warning,
        error = Error,
        errorText = ErrorText,
    )
}

// ── Convenience accessor ────────────────────────────────────────────────────
object PrecisionTheme {
    val colors: PrecisionColors
        @Composable get() = LocalPrecisionColors.current
}

// ── Material3 Dark Color Scheme — DESIGN_SYSTEM.md mapping ─────────────────
// Monochrome enforcement: M3 derives default colors for cursors, selection
// handles, checkboxes, switches, progress, buttons, tonal surfaces, and the
// text toolbar from `primary` and the surface-container ramp. Both are pinned
// to neutral tokens so no M3 default can ever render brand color — including
// components added later without explicit colors. BrandAmethyst survives only
// via explicit opt-in usages (recording control, charts), never via defaults.
private val FluenceDarkColorScheme = darkColorScheme(
    primary            = TextPrimary,
    onPrimary          = AppBackground,
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
    // Neutral tonal ramp: M3 defaults these from `primary`, which would tint
    // menus, toolbars, sheets, and snackbars lavender. Pinned to the surface
    // ladder so every default stays monochrome.
    surfaceDim              = AppBackground,
    surfaceBright           = DialogElevated,
    surfaceContainerLowest  = AppBackground,
    surfaceContainerLow     = Canvas,
    surfaceContainer        = Panel,
    surfaceContainerHigh    = PanelElevated,
    surfaceContainerHighest = DialogSurface,

    error              = Error,
    onError            = TextPrimary,
    errorContainer     = Error.copy(alpha = 0.15f),
    onErrorContainer   = TextPrimary,

    outline            = OutlineSubtle,
    outlineVariant     = OutlineSubtle,
    inverseSurface     = TextPrimary,
    inverseOnSurface   = AppBackground,
    inversePrimary     = TextPrimary,
    scrim              = Color.Black,
)

// ── Material3 Light Color Scheme — Windows [data-theme="light"] mapping ─────
// Same monochrome enforcement as dark: `primary` and the surface-container
// ramp are pinned to neutral light tokens so no M3 default can render brand
// color. Container alphas follow the Windows light tokens (error 8%).
private val FluenceLightColorScheme = lightColorScheme(
    primary            = LightTextPrimary,
    onPrimary          = Color.White,
    primaryContainer   = LightDialogSurface,
    onPrimaryContainer = LightTextPrimary,

    secondary          = LightPanelElevated,
    onSecondary        = LightTextPrimary,
    secondaryContainer = LightDialogSurface,
    onSecondaryContainer = LightTextPrimary,

    tertiary           = LightSuccess,
    onTertiary         = Color.White,
    tertiaryContainer  = LightDialogSurface,
    onTertiaryContainer = LightTextPrimary,

    background         = LightAppBackground,
    onBackground       = LightTextPrimary,

    surface            = LightPanel,
    onSurface          = LightTextPrimary,
    surfaceVariant     = LightPanelElevated,
    onSurfaceVariant   = LightTextSecondary,
    surfaceTint        = LightPanel,
    surfaceDim              = LightAppBackground,
    surfaceBright           = Color.White,
    surfaceContainerLowest  = LightAppBackground,
    surfaceContainerLow     = LightCanvas,
    surfaceContainer        = LightPanel,
    surfaceContainerHigh    = LightPanelElevated,
    surfaceContainerHighest = LightDialogSurface,

    error              = LightError,
    onError            = Color.White,
    errorContainer     = LightError.copy(alpha = 0.08f),
    onErrorContainer   = LightTextPrimary,

    outline            = LightOutlineSubtle,
    outlineVariant     = LightOutlineSubtle,
    inverseSurface     = LightTextPrimary,
    inverseOnSurface   = Color.White,
    inversePrimary     = LightTextPrimary,
    scrim              = Color.Black,
)

// ── Light PrecisionColors — one-to-one twin of the dark instance above ──────
private val FluenceLightPrecisionColors = PrecisionColors(
    isLight = true,
    appBackground = LightAppBackground,
    canvas = LightCanvas,
    sidebar = LightSidebar,
    panel = LightPanel,
    panelElevated = LightPanelElevated,
    dialog = LightDialogSurface,
    dialogElevated = LightDialogElevated,
    outlineSubtle = LightOutlineSubtle,
    cardSurface = LightCardSurface,
    cardBorder = LightCardBorder,
    inputBg = LightInputBg,
    buttonSecondary = LightButtonSecondary,
    buttonSubtle = LightButtonSubtle,
    textPrimary = LightTextPrimary,
    textSecondary = LightTextSecondary,
    textTertiary = LightTextTertiary,
    textDisabled = LightTextDisabled,
    textLink = LightTextLink,
    brandAmethyst = BrandAmethyst,
    brandCyan = LightBrandCyan,
    charcoal = Charcoal,
    chartDuoStart = LightChartDuoStart,
    chartDuoMid = LightChartDuoMid,
    chartDuoEnd = LightChartDuoEnd,
    success = LightSuccess,
    warning = LightWarning,
    error = LightError,
    errorText = LightErrorText,
)

// ── Strict monochrome interactions ─────────────────────────────────────────
// M3 ripples and text-selection handles default to the scheme primary
// (brand amethyst). This theme pins both to primary text, so no tap,
// press, or selection ever flashes color. (M3 1.2 ripples delegate to the
// material RippleTheme — hence LocalRippleTheme, not RippleConfiguration.)
private class FluenceRippleTheme(private val rippleColor: Color, private val light: Boolean) : RippleTheme {
    @Composable
    override fun defaultColor() = rippleColor

    @Composable
    override fun rippleAlpha(): RippleAlpha =
        RippleTheme.defaultRippleAlpha(Color.Black, lightTheme = light)
}

// ── White-mode preference (Windows localStorage `fluence_theme` parity) ────
// Default dark preserves existing behavior for current installs. Stored in
// the shared `fluence_prefs` file alongside `chart_metric`.
const val FluencePrefsName = "fluence_prefs"
const val ThemePrefKey = "theme_mode"
const val ThemeModeDark = "dark"
const val ThemeModeLight = "light"

fun isWhiteMode(prefs: SharedPreferences): Boolean =
    prefs.getString(ThemePrefKey, ThemeModeDark) == ThemeModeLight

fun setWhiteMode(prefs: SharedPreferences, white: Boolean) {
    prefs.edit().putString(ThemePrefKey, if (white) ThemeModeLight else ThemeModeDark).apply()
}

// ── Theme Composable ────────────────────────────────────────────────────────
// darkTheme defaults true: the app stays exactly as today until a later slice
// wires the persisted white-mode preference (Windows parity: dark default).
// The floating bubble and IME never read this — they stay dark in both modes.
@Composable
fun FluenceTranscribeTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val precisionColors = if (darkTheme) {
        PrecisionColors(
            appBackground = AppBackground,
            canvas = Canvas,
            sidebar = Sidebar,
            panel = Panel,
            panelElevated = PanelElevated,
            dialog = DialogSurface,
            dialogElevated = DialogElevated,
            outlineSubtle = OutlineSubtle,
        cardSurface = CardSurface,
        cardBorder = CardBorder,
        inputBg = InputBg,
        buttonSecondary = ButtonSecondary,
        buttonSubtle = ButtonSubtle,
            textPrimary = TextPrimary,
            textSecondary = TextSecondary,
            textTertiary = TextTertiary,
            textDisabled = TextDisabled,
            textLink = BrandAmethyst,
            brandAmethyst = BrandAmethyst,
            brandCyan = BrandCyan,
            charcoal = TextPrimary,
            chartDuoStart = ChartDuoStart,
            chartDuoMid = ChartDuoMid,
            chartDuoEnd = ChartDuoEnd,
            success = Success,
            warning = Warning,
            error = Error,
            errorText = ErrorText,
        )
    } else {
        FluenceLightPrecisionColors
    }
    val rippleTheme = remember(darkTheme) {
        if (darkTheme) FluenceRippleTheme(TextPrimary, light = false)
        else FluenceRippleTheme(LightTextPrimary, light = true)
    }
    val selectionColors = remember(darkTheme) {
        if (darkTheme) TextSelectionColors(
            handleColor = TextPrimary,
            backgroundColor = TextPrimary.copy(alpha = 0.4f)
        ) else TextSelectionColors(
            handleColor = LightTextPrimary,
            backgroundColor = LightTextPrimary.copy(alpha = 0.4f)
        )
    }

    val context = LocalContext.current
    val motionPrefs = remember {
        // Reduced-motion signal (see isSystemReducedMotion): the system
        // "remove animations" accessibility toggle zeroes
        // ANIMATOR_DURATION_SCALE (TalkBack must NOT flip this flag — touch
        // exploration is a different need). DESIGN_SYSTEM.md: reduced-motion
        // preferences must be respected everywhere.
        MotionPreferences(reducedMotion = isSystemReducedMotion(context))
    }

    CompositionLocalProvider(
        LocalPrecisionColors provides precisionColors,
        LocalFluenceFonts provides FluenceFonts(),
        LocalMotionPreferences provides motionPrefs,
        LocalRippleTheme provides rippleTheme,
        LocalTextSelectionColors provides selectionColors
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) FluenceDarkColorScheme else FluenceLightColorScheme,
            typography  = FluenceTypography,
            content     = content
        )
    }
}
