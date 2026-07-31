package com.groq.voicetyper.theme

import androidx.compose.ui.graphics.Color

// ── Fluence Design System v2.1 — Source of Truth ────────────────────────────
// All values from DESIGN_SYSTEM.md.
// No legacy aliases. No raw hex literals in UI code.
// ────────────────────────────────────────────────────────────────────────────

// ── App Background ──────────────────────────────────────────────────────────
val AppBackground = Color(0xFF121212)

// ── Surface Hierarchy ───────────────────────────────────────────────────────
val Canvas         = Color(0xFF121212)
val Sidebar        = Color(0xFF141414)
val Panel          = Color(0xFF1E1E1E)
val PanelElevated  = Color(0xFF262626)
val DialogSurface  = Color(0xFF2E2E2E)
val DialogElevated = Color(0xFF363636)

// ── Divider ─────────────────────────────────────────────────────────────────
val OutlineSubtle  = Color(0xFF2A2A2A)

// ── Text Hierarchy ──────────────────────────────────────────────────────────
val TextPrimary    = Color(0xFFE2E2E2)
val TextSecondary  = Color(0xFFA0A0A0)
val TextTertiary   = Color(0xFF8E8E8E) // ≥4.5:1 on Canvas/Panel/PanelElevated (WCAG AA)
val TextDisabled   = Color(0xFF4A4A4A)

// ── Accent ──────────────────────────────────────────────────────────────────
val BrandAmethyst  = Color(0xFF8B45D8)
val BrandCyan      = Color(0xFF0BD6E3)

// ── IME Accent (Agent Mode pill) ────────────────────────────────────────────
// Promoted verbatim from IMEScreen hex literals — values must stay identical
// to the frozen IME recording visuals.
val AgentTeal      = Color(0xFF00F5D4)
val AgentTealSoft  = Color(0xFF80FFE8)
val AgentBlue      = Color(0xFF00BBF9)
val IndigoAccent   = Color(0xFF6366F1)

// ── IME Surfaces ────────────────────────────────────────────────────────────
val ImeInkDark       = Color(0xFF0D0E12)
val ImeStatusBg      = Color(0xCC0D0E12)
val ImePillBg        = Color(0x80131319)
val ImePillBgActive  = Color(0xB2131319)

// ── Semantic ────────────────────────────────────────────────────────────────
val Success        = Color(0xFF22C55E)
val Warning        = Color(0xFFF59E0B)
val Error          = Color(0xFFEF4444)

// ── Code Block Surface ──────────────────────────────────────────────────────
val CodeBlock       = Color(0xFF181818)

// ── Input Background ────────────────────────────────────────────────────────
val InputBg         = Color(0xFF262626)

// ── Interaction States ─────────────────────────────────────────────────────
val InputBackground = Color(0x05FFFFFF)
val Pressed         = Color(0x0DFFFFFF)
val ButtonSubtle    = Color(0x13FFFFFF)
val ButtonSecondary = Color(0x26FFFFFF)

// ── Editor Body Text ────────────────────────────────────────────────────────
val EditorBody      = Color(0xFFD4D4D4)
