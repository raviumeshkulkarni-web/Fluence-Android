package com.groq.voicetyper.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.R

// ── Bundled Variable Fonts ──────────────────────────────────────────────────
// The brand fonts ship inside the APK (app/src/main/res/font) so text renders
// correctly on first run, offline, and on de-Googled devices — no Google Fonts
// provider round-trip. Licenses live in docs/fonts/OFL-*.txt.
// Each family pins the single variable TTF to named instances via
// FontVariation.weight. Keep the registered weights aligned with what the
// type scale below actually requests.
@OptIn(ExperimentalTextApi::class)
private fun bundledVariableFont(resId: Int, weight: FontWeight): Font = Font(
    resId = resId,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

// ── Font Families ───────────────────────────────────────────────────────────
// UI: buttons, labels, sidebar, chips, settings, editor body
val HankenGroteskFont = FontFamily(
    bundledVariableFont(R.font.hanken_grotesk_variable, FontWeight.Normal),
    bundledVariableFont(R.font.hanken_grotesk_variable, FontWeight.Medium),
    bundledVariableFont(R.font.hanken_grotesk_variable, FontWeight.SemiBold),
)

// Headlines / titles / display
val SoraFont = FontFamily(
    bundledVariableFont(R.font.sora_variable, FontWeight.Normal),
    bundledVariableFont(R.font.sora_variable, FontWeight.Medium),
    bundledVariableFont(R.font.sora_variable, FontWeight.SemiBold),
    bundledVariableFont(R.font.sora_variable, FontWeight.Bold),
)

// Mono: code blocks, inline code, timestamps, technical labels
val GeistMonoFont = FontFamily(
    bundledVariableFont(R.font.geist_mono_variable, FontWeight.Normal),
    bundledVariableFont(R.font.geist_mono_variable, FontWeight.Medium),
)

// Product name script (brand artwork only — Fluence Capture / Fluence Transcribe)
// Bundled locally so the script face is always present (no network/fallback dependency).
val AlluraFont = FontFamily(
    Font(resId = R.font.allura, weight = FontWeight.Normal),
)

// ── CompositionLocal for mono font ──────────────────────────────────────────
@Immutable
data class FluenceFonts(
    val ui: FontFamily = HankenGroteskFont,
    val headline: FontFamily = SoraFont,
    val mono: FontFamily = GeistMonoFont,
)

val LocalFluenceFonts = staticCompositionLocalOf { FluenceFonts() }

// ── Typography Scale ────────────────────────────────────────────────────────
val FluenceTypography = Typography(
    // ── Display (Sora) ──────────────────────────────────────────────────────
    displayLarge = TextStyle(
        fontFamily = SoraFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 56.sp,
        lineHeight = 61.6.sp,
        letterSpacing = (-1.8).sp
    ),
    displayMedium = TextStyle(
        fontFamily = SoraFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-1.0).sp
    ),
    displaySmall = TextStyle(
        fontFamily = SoraFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.4.sp,
        letterSpacing = (-0.6).sp
    ),

    // ── Headline (Sora) ─────────────────────────────────────────────────────
    headlineLarge = TextStyle(
        fontFamily = SoraFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 28.8.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = SoraFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = SoraFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 25.2.sp,
        letterSpacing = (-0.1).sp
    ),

    // ── Title (Hanken Grotesk) ──────────────────────────────────────────────
    titleLarge = TextStyle(
        fontFamily = HankenGroteskFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 23.4.sp,
        letterSpacing = (-0.1).sp
    ),
    titleMedium = TextStyle(
        fontFamily = HankenGroteskFont,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.4.sp,
        letterSpacing = (-0.05).sp
    ),
    titleSmall = TextStyle(
        fontFamily = HankenGroteskFont,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp
    ),

    // ── Body (Hanken Grotesk) ───────────────────────────────────────────────
    bodyLarge = TextStyle(
        fontFamily = HankenGroteskFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.05).sp
    ),
    bodyMedium = TextStyle(
        fontFamily = HankenGroteskFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = HankenGroteskFont,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.2.sp,
        letterSpacing = 0.sp
    ),

    // ── Label (Hanken Grotesk) ──────────────────────────────────────────────
    labelLarge = TextStyle(
        fontFamily = HankenGroteskFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 16.8.sp,
        letterSpacing = 0.sp
    ),
    labelMedium = TextStyle(
        fontFamily = HankenGroteskFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.8.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = HankenGroteskFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.3.sp,
        letterSpacing = 0.4.sp
    ),
)
