package com.groq.voicetyper.theme

import androidx.compose.ui.graphics.Color

// ── Fluence Design System v2.1 — Source of Truth ────────────────────────────
// All values from DESIGN_SYSTEM.md.
// No legacy aliases. No raw hex literals in UI code.
// ────────────────────────────────────────────────────────────────────────────

// ── Surface Hierarchy ───────────────────────────────────────────────────────
val Canvas         = Color(0xFF121212)
val AppBackground  = Color(0xFF0D0D0D) // spec/Windows app background; Canvas untouched
val Sidebar        = Color(0xFF141414)
val Panel          = Color(0xFF1E1E1E)
val PanelElevated  = Color(0xFF262626)
val DialogSurface  = Color(0xFF2E2E2E)
val DialogElevated = Color(0xFF363636)

// ── Divider ─────────────────────────────────────────────────────────────────
val OutlineSubtle  = Color(0xFF2A2A2A)

// ── Card ────────────────────────────────────────────────────────────────────
// Windows Dashboard card treatment (parity): cards render on --color-surface
// #141414 with a 5%-white hairline — one tier below Panel, which remains the
// surface for controls, sheets, and the range-selector container.
val CardSurface    = Color(0xFF141414)
val CardBorder     = Color(0x0DFFFFFF)

// ── Text Hierarchy ──────────────────────────────────────────────────────────
val TextPrimary    = Color(0xFFE2E2E2)
val TextSecondary  = Color(0xFFA0A0A0)
val TextTertiary   = Color(0xFF8E8E8E) // ≥4.5:1 on Canvas/Panel/PanelElevated (WCAG AA)
val TextDisabled   = Color(0xFF4A4A4A)

// ── Accent ──────────────────────────────────────────────────────────────────
val BrandAmethyst  = Color(0xFF8B45D8)
val BrandCyan      = Color(0xFF0BD6E3)

// ── Chart Duo (muted amethyst-to-cyan, Windows parity 2026-09-12) ─────────────
// Desaturated activity-chart ramp shared with the Windows dashboard chart.
// Charts are an explicit accent opt-in (see DESIGN_SYSTEM.md changelog).
// Never use outside charts without a new changelog entry.
val ChartDuoStart  = Color(0xFF8E7CC3)
val ChartDuoMid    = Color(0xFF7498C6)
val ChartDuoEnd    = Color(0xFF5FB4C2)

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

// Error-colored TEXT on elevated surfaces (Panel/Dialog/DialogElevated) —
// holds WCAG AA ≥4.5:1 there (4.91:1 on Dialog #2E2E2E). Error remains the
// tone for icons, containers, indicators. See DESIGN_SYSTEM.md 2026-08-25.
val ErrorText      = Color(0xFFF87171)

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

// ── Light (white-mode) palette — Windows [data-theme="light"] parity ────────
// Values verbatim from Fluence-Windows `design-tokens.css` light block, the
// DashboardPage light chart duo, and the light overrides in ui.css / app.css /
// settings.css / global.css. Dark vals above stay frozen; nothing above moves.
// `Light` prefix keeps the dark defaults unambiguous at call sites. Reached
// only through PrecisionColors — never referenced raw in UI code.
// Frozen in light mode (Windows parity): BrandAmethyst (logo only) and the
// logo-mark gradient stops keep their dark values; only functional cyan
// deepens to teal. The floating bubble and IME stay dark in both modes.
val LightAppBackground  = Color(0xFFEFF0F3)
val LightCanvas         = Color(0xFFEFF0F3)
val LightSidebar        = Color(0xFFFFFFFF)
val LightPanel          = Color(0xFFF5F5F7)
val LightPanelElevated  = Color(0xFFFFFFFF)
val LightDialogSurface  = Color(0xFFFFFFFF)
val LightDialogElevated = Color(0xFFF5F5F7)
val LightCardSurface    = Color(0xFFFFFFFF)
val LightCardBorder     = Color(0x14000000) // hairline: between --color-border-muted (4%) and --color-border (8%)
val LightOutlineSubtle  = Color(0xFFDFE0E4) // --color-border-structural
val LightTextPrimary    = Color(0xFF18181B)
val LightTextSecondary  = Color(0xFF52525B)
val LightTextTertiary   = Color(0xFF71717A)
val LightTextDisabled   = Color(0xFFA1A1AA)
val LightTextLink       = Color(0xFF6D28D9)
val LightBrandCyan      = Color(0xFF0E7490) // functional teal, AA on white; the logo lockup is FROZEN and keeps #0BD6E3 in both modes (Brand.kt untouched)
val LightChartDuoStart  = Color(0xFF6E5AA8) // deepened light stops (Windows DashboardPage parity)
val LightChartDuoMid    = Color(0xFF4E7FA8)
val LightChartDuoEnd    = Color(0xFF2E8B99)
val LightSuccess        = Color(0xFF15803D)
val LightWarning        = Color(0xFFB45309)
val LightError          = Color(0xFFDC2626)
val LightErrorText      = Color(0xFFB91C1C)
val LightInputBg        = Color(0xFFFFFFFF)
val LightInputBorder    = Color(0xFFD4D4D8) // untreated checkbox / kbd border
// Light button fills: secondary at active-level 12% black, subtle at
// interactive-level 5% black (Windows light --color-active / --color-interactive).
val LightButtonSecondary = Color(0x1F000000)
val LightButtonSubtle    = Color(0x0D000000)
val LightSunken         = Color(0xFFE7E8EC) // skeleton, sunken wells
// Matte charcoal: light-mode primary-button + toggle-ON fill
// (Windows --color-charcoal). No dark equivalent — dark reserves white.
val Charcoal            = Color(0xFF3F3F46)
