# Fluence Design System

Version: 2.1 — Frozen
Status: Universal source of truth for the entire Fluence ecosystem
(Android, Web, Desktop, Website, and all future applications).

This document is platform-agnostic. It defines what must be true, never
how to implement it in any specific framework or language.

---

## Philosophy

Capture instantly. Think later. Capture speed always wins over feature
exposure. Every design decision must strengthen this philosophy.

- Monochrome-first. Color is earned, not constantly visible.
- Hierarchy must be felt before it is seen.
- Whitespace is preferred over decoration.
- Typography is preferred over borders.
- Avoid dashboard aesthetics and unnecessary visual complexity.

---

## Color

### Base
- App background: `#0D0D0D`

### Surfaces — semantic layer (application code uses these names only)

| Semantic token | Hex | Usage |
|---|---|---|
| Canvas | `#121212` | Primary writing surface — note editor, long-form reading content |
| Sidebar | `#141414` | Navigation panels, sidebar surfaces — deliberately subtle 2pt from Canvas to keep chrome recessive |
| Panel | `#1E1E1E` | Content panels, cards, feed items, secondary surfaces |
| PanelElevated | `#262626` | Elevated panels, bottom sheets, search bars, buttons |
| Dialog | `#2E2E2E` | Dialog backgrounds, menus, popovers, tooltips |
| DialogElevated | `#363636` | Elevated dialogs, nested dialog elements |
| CodeBlock | `#181818` | Code block background within editor — anchored to Canvas hierarchy |
| InputBg | `#262626` | Form control background — matches PanelElevated tier |
| Divider / OutlineSubtle | `#2A2A2A` | Structural separators, subtle outlines |

Depth is communicated through these small, deliberate tonal steps —
never through shadows or heavy borders. Application code must reference
only these semantic names, never raw hex values or internal tonal-scale
names.

### Text

| Token | Hex | Usage rule |
|---|---|---|
| text-primary | `#E2E2E2` | Primary reading content, titles |
| text-secondary | `#A0A0A0` | Supporting text, descriptions |
| text-tertiary | `#8E8E8E` | Short (≤3 word) metadata, timestamps, icon captions ONLY — never sentence-length body or preview text, never in the editor. Raised from the v2.1 `#808080` spec to `#8E8E8E` to hold WCAG AA ≥4.5:1 on Canvas/Panel surfaces; `Color.kt` is the source of truth |
| text-disabled | `#4A4A4A` | Disabled state only |
| editor-body | `#D4D4D4` | Long-form editor body text — paragraphs, prose content. Brighter than secondary for sustained readability; dimmer than primary to avoid eye strain. |

### Accent

- brand-amethyst: `#8B45D8`
- brand-cyan: `#0BD6E3`

**Gradient rule:** the amethyst→cyan gradient (135deg) is restricted to
exactly two places:
- Recording / Capture control
- Logo / Wordmark (amethyst → lavender → cyan, 3-stop, 135deg)

No other UI element may use the gradient — not buttons, navigation,
icons, indicators, cards, or backgrounds.

**Solid accent rule:** solid brand-amethyst (no gradient) may additionally
be used only for:
- Focus rings
- Caret (text cursor), including editor caret-color
- Text selection
- Progress indicators
- Processing/streaming state indicators (e.g. a subtle border on an
  active AI response container while content is streaming)
- Active controls (switches, sliders, selected radio/checkbox,
  **and toggle buttons in their active/selected state**)
- Toggle buttons in an active/selected state (extends the existing
  active controls entry to explicitly cover toggle buttons)
- Link-style text (the `link` button variant text color)
- Inline code text color (`.ProseMirror code`)
- Blockquote left-border color (`.ProseMirror blockquote`)

Rationale: inline code text color, blockquote left-border, editor caret,
processing/streaming indicators, toggle-button active states, and link
text all read as restrained functional-role accents rather than decorative
color; they were confirmed correct on visual review and codebase audit.

This is a closed list. No other element may use solid accent. Adding a
new use case requires updating this document first.

### Semantic

| Token | Hex |
|---|---|
| success | `#22C55E` |
| warning | `#F59E0B` |
| error | `#EF4444` |

Color must never be the only signal for status — pair with an icon,
label, or shape.

---

## Typography

| Role | Font |
|---|---|
| UI (buttons, labels, sidebar, chips, settings) | Hanken Grotesk |
| Headlines / titles / display | Sora |
| Editor body (note content, the writing surface) | Hanken Grotesk |
| Editor code (code blocks, inline code) | Geist Mono |
| Mono / data (timestamps, technical labels outside the editor) | Geist Mono |

Chat message body text matches editor body size (16px). Chat UI
labels, metadata, and action elements must not exceed editor body size.

---

## Motion

- **Immediate feedback** (press, hover, toggle): ~100–150ms
- **Structural change** (panel open, navigation, expand/collapse): ~200–250ms
- **Large surface transition** (dialog, sheet, modal entrance/exit): ~250–350ms
- Reduced-motion accessibility settings must be respected everywhere —
  non-essential animation must be disabled when requested.
- Motion must preserve continuity. It communicates life and state change,
  not decoration. It must never exist purely to attract attention.

---

## Iconography

- One icon family per platform. Never mix styles within the same screen.
- Consistent stroke weight throughout an application.
- Icon color follows the same text/accent token rules as surrounding
  content — icons are not exempt from the accent restrictions above.
- Decorative icons (purely ornamental, non-functional) should be avoided;
  every icon should communicate or enable an action.

---

## Elevation

- Depth comes from the surface tonal ladder defined above, not shadows.
- Shadows are reserved only for floating/overlay elements that must
  visually separate from all content beneath them: menus, dialogs,
  tooltips, popovers.
- Borders are used sparingly, as secondary reinforcement of a surface
  boundary — not as the primary method of establishing hierarchy.

### Shadow Tokens

| Token | Value | Usage |
|---|---|---|
| shadow-menu | 0 4px 24px rgba(0,0,0,0.45), 0 1px 4px rgba(0,0,0,0.25) | Menus, dropdowns, popovers, tooltips |
| shadow-dialog | 0 12px 56px rgba(0,0,0,0.65), 0 2px 8px rgba(0,0,0,0.35) | Dialogs, modals, command palettes |
| shadow-card | 0 1px 3px rgba(0,0,0,0.35) | Cards, sheets, subtle floating surfaces |

---

## Layout

- Minimum interactive touch/click target: 44×44 points/dp/px.
- Density serves navigation. Space serves thinking — err toward more
  whitespace in content/reading contexts, tighter density in
  navigation/utility contexts.

---

## Accessibility

- Any token used for continuous reading text must meet WCAG AA contrast
  (4.5:1) against its surface. Tokens that don't meet this bar (e.g.
  text-tertiary) are restricted to short, non-essential text only.
- Every interactive element must have a visible focus state.
- Color must never be the only signal for state or status.
- Reduced-motion preferences must be respected.
- Minimum touch target sizing (see Layout) applies to all interactive
  elements regardless of visual size.

---

## Feedback States

| State | Visual language |
|---|---|
| Recording | Brand gradient with pulse motion |
| Syncing | Neutral progress indicator, solid accent allowed (progress indicator rule) |
| Saving | Neutral, unobtrusive — should not interrupt capture flow |
| Success | success token, brief, non-blocking |
| Warning | warning token, requires no immediate action |
| Error | error token, no additional decoration |
| Offline | Neutral, informational — never alarming |

---

## Token Rules

- No raw colors, spacing, or typography values may appear in production UI.
- Every visual value must originate from a semantic design token defined
  in this document.
- All platforms must expose the same semantic token names.
- Hex values and platform-specific implementation are details beneath
  the semantic layer — application code references semantic names only.
- Any new token requires updating this document before implementation.

---

## Design Review Checklist

Before any UI change is accepted:

- Uses semantic tokens
- No raw visual values
- Matches motion rules
- Meets accessibility rules
- Respects accent rules
- No undocumented design patterns introduced

---

## AI Agent Rules

1. Read this document before making any visual decision.
2. Generate the platform-native implementation of these tokens — never
   invent equivalents that aren't defined here.
3. Never invent colors, spacing, typography, animations, or gradients.
4. Never introduce raw values directly into UI code.
5. Never create a new token without updating this document first.
6. Before finishing any UI task, run the Design Review Checklist above.
7. If a task requires a design decision not covered by this document,
   stop and request clarification — do not guess, do not proceed on
   an assumption, and do not silently introduce a new pattern.
8. This document governs all Fluence applications equally. Do not apply
   platform-specific exceptions without updating this document first.

---

## Status of prior tokens

All previously used tokens (including PrecisionAccent `#7C5CBF`, any
`Inter`/`JetBrains Mono` font references, and any raw literals found in
existing code) are superseded by this document and must be migrated to
match it.

The notebook color system (9-index shared palette, index-based sync) is
a separate, already-verified system and is NOT affected by this document.

## Changelog

**2026-07-16 — Solid accent closed list — audit resolution.**
Following a full codebase audit that found 7 brand-amethyst usages
outside the prior closed list, 3 were approved as consistent with the
philosophy of restrained, functional accent use: processing/streaming
state indicators, toggle-button active states, and link-style button
text. 2 dormant unused variants (Button `primary`, Badge `accent`) were
stripped of amethyst entirely rather than left as unused landmines.
2 were confirmed already-correct (AI chat panel icon reclassified as
decorative, not functional — moved to neutral). Files touched:
DESIGN_SYSTEM.md (this entry + solid accent rule text).

**2026-07-15 — VISUAL LANGUAGE FROZEN.** All audit findings resolved or explicitly deferred. No further presentation-layer changes beyond this point without a formal design review and changelog entry.

Changes in this freeze commit:
- Button default variant: background changed from brand-amethyst to --color-panelElevated (neutral). New `primary` variant available for future use. Based on a visual review, the three candidate CTA buttons (Create Notebook, Rename Notebook, Empty State New Note) were returned to default (neutral) — no primary-variant button is currently used. Files touched: button.tsx, nav-sidebar.tsx, empty-state.tsx.
- Brand-color rule expanded: closed list extended to include inline code text color and blockquote left-border. Editor caret already present. Rationale added. No code changes for inline code, blockquote, or caret — these are now explicitly permitted rather than violations. Selected-note left-border indicator was initially added and then removed after visual review. Files touched: DESIGN_SYSTEM.md (this entry + rule text), WEB_IMPLEMENTATION.md (3.6, 5.1, 0.3, 0.4, 3.15).
- Editor canvas (#121212) and all text color tokens explicitly unchanged throughout.

**2026-07-15 (v2.4)** — Replaced subtle-step approach (v2.3) with bold-contrast steps (10-14pt gaps). **SUPERSEDED/REVERTED** — dialogs and overlays became too bright (#505050), which broke the calm-interface principle. The full ladder was too aggressive; only the overly bright floating tiers were problematic. Reverted to v2.3 values except for a single targeted sidebar correction (see v2.5).

**2026-07-15 (v2.5)** — Targeted single-token correction on top of v2.3 revert. --color-sidebar lowered from #181818 (24) to #141414 (20), creating a deliberately subtle 2pt gap from Canvas (#121212, 18). This keeps the nav chrome recessive — it recedes into the background rather than competing with the note-list and editor panes. All other tokens remain at v2.3 values. Editor canvas (#121212), appBackground (#0D0D0D), and all text color tokens explicitly unchanged. Files touched: DESIGN_SYSTEM.md, WEB_IMPLEMENTATION.md, index.css (token values only — no component changes).

**2026-07-15 (v2.3)** — Corrected tonal ladder to eliminate imperceptible gaps and tier collisions from the v2.2 proposal. Canvas→Sidebar gap widened to 6pt (#181818). Panel/panelElevated/dialog/dialogElevated shifted up to create clean progressive gaps (6-6-8-8-8). input-bg raised to #262626 to match PanelElevated tier. Editor canvas (#121212) and all text color tokens explicitly unchanged. Files touched: DESIGN_SYSTEM.md, WEB_IMPLEMENTATION.md, index.css (token values only — no component changes).

**2026-07-15** — Three-pane hierarchy fix (background only). Widened dark-end tonal gaps: Sidebar #161616, Panel #1A1A1A, PanelElevated #1E1E1E, Dialog #242424, DialogElevated #2C2C2C. Left nav keeps --color-sidebar; note-list reassigned to --color-panel; editor column reassigned to --color-panelElevated. New dedicated tokens: --color-code-block (#181818, decoupled from Panel), --color-input-bg (#1E1E1E, decoupled from Panel for form controls). Editor content background (--color-canvas) and all text color tokens explicitly unchanged. Files touched: DESIGN_SYSTEM.md, WEB_IMPLEMENTATION.md, index.css, App.tsx:251, sidebar.tsx:739/779/965, input.tsx, textarea.tsx, switch.tsx, progress.tsx.

**2026-07-14** — Reverted gray/neutral tokens to true neutral (R=G=B). A
warm-tinted variant was tested and rejected — reference direction was
reassessed as cool-neutral, not warm, based on additional UI references.
All 15 base gray tokens confirmed mathematically neutral, zero channel
bias. No code changes were required; the `@theme` block in `index.css`
already contained the correct neutral values. Stale hex references in
`WEB_IMPLEMENTATION.md` (divider `#353534` → `#2A2A2A`, canvas `#0E0E0E`
→ `#121212`) were corrected to match this document. Files touched:
`DESIGN_SYSTEM.md` (this note), `WEB_IMPLEMENTATION.md` (lines 378, 664).
