package com.groq.voicetyper.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

// ── Fluence Shape Scale ─────────────────────────────────────────────────────
// DESIGN_SYSTEM.md defers corner radii to platform conventions.
// ────────────────────────────────────────────────────────────────────────────

object FluenceShapes {
    val ExtraSmall = RoundedCornerShape(4.dp)   // Chips, badges, small tags
    val Small      = RoundedCornerShape(8.dp)   // Buttons, text fields, cards
    val Medium     = RoundedCornerShape(12.dp)  // Sheets, list items
    val Large      = RoundedCornerShape(16.dp)  // Dialogs, bottom sheets
    val ExtraLarge = RoundedCornerShape(24.dp)  // Full-screen overlays
    val Full       = RoundedCornerShape(9999.dp) // Circular elements, FABs
}
