package com.groq.voicetyper.theme

import androidx.compose.ui.unit.dp

// ── Fluence Spacing Scale ───────────────────────────────────────────────────
// DESIGN_SYSTEM.md — minimum touch target (44×44dp).
// ────────────────────────────────────────────────────────────────────────────

object FluenceSpacing {
    val Xxs    = 2.dp
    val Xs     = 4.dp
    val Sm     = 8.dp
    val Md     = 12.dp
    val Base   = 16.dp
    val Lg     = 24.dp
    val Xl     = 32.dp
    val Xxl    = 48.dp
    val Section = 96.dp

    // Declared micro-rhythm (audit): values shared with section chrome that
    // sit between the t-shirt steps. Numeric names mirror the Windows
    // --spacing-N tokens holding the same values. Additive only.
    val N6     = 6.dp
    val N7     = 7.dp
    val N10    = 10.dp
    val N18    = 18.dp
}
