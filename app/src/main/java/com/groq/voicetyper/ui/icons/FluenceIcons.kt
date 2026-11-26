package com.groq.voicetyper.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// ── Fluence Lucide icon layer ───────────────────────────────────────────────
// Hand-ported Lucide icons (lucide-static v1.43.0, ISC license) for the
// shared product surfaces where Windows uses Lucide and Android must match
// (drawer, History, trend badges). Only the icons below are included — do
// not grow this into a general icon library.
//
// Authoring rules (mirror Lucide exactly):
// - 24×24 viewport, fill none, 2-unit round stroke.
// - Rects/circles converted to equivalent path data (rounded corners use
//   exact arc segments, not approximations).
// - Base color is Black; call sites MUST pass an explicit tint (Icon tint
//   replaces the base color, so geometry/stroke carry over).
// - Paths whose Lucide source runs flags straight into coordinates with no
//   separator (e.g. "A2 2 0 0022 17") are written with every token explicitly
//   space-delimited and each repeated arc pair as its own command. Geometry is
//   unchanged, but Compose's PathParser tokenizes cleanly (this is why the
//   book-open outline below has explicit separators).
// ────────────────────────────────────────────────────────────────────────────

private fun lucideIcon(name: String, paths: List<String>): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
    for (d in paths) {
        builder.addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            pathFillType = PathFillType.NonZero,
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return builder.build()
}

object FluenceIcons {
    val LayoutDashboard: ImageVector = lucideIcon(
        "LayoutDashboard",
        listOf(
            "M4 3H9A1 1 0 0 1 10 4V11A1 1 0 0 1 9 12H4A1 1 0 0 1 3 11V4A1 1 0 0 1 4 3Z",
            "M15 3H20A1 1 0 0 1 21 4V7A1 1 0 0 1 20 8H15A1 1 0 0 1 14 7V4A1 1 0 0 1 15 3Z",
            "M15 12H20A1 1 0 0 1 21 13V20A1 1 0 0 1 20 21H15A1 1 0 0 1 14 20V13A1 1 0 0 1 15 12Z",
            "M4 16H9A1 1 0 0 1 10 17V20A1 1 0 0 1 9 21H4A1 1 0 0 1 3 20V17A1 1 0 0 1 4 16Z",
        ),
    )
    val History: ImageVector = lucideIcon(
        "History",
        listOf(
            "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
            "M3 3v5h5",
            "M12 7v5l4 2",
        ),
    )
    val BookOpen: ImageVector = lucideIcon(
        "BookOpen",
        listOf(
            "M12 5v16",
            // Lucide book-open outline, token-normalized (see authoring rules):
            // every arc flag is space-delimited so PathParser reads it verbatim.
            "M20.001 19 A2 2 0 0 0 22 17 V5 a2 2 0 0 0 -1.999 -2 L16 3.002 " +
                "A5 5 0 0 0 12 5 a5 5 0 0 0 -4 -2 H4 a2 2 0 0 0 -2 2 v12 " +
                "a2 2 0 0 0 1.999 2 H8 a5 5 0 0 1 4 2 a5 5 0 0 1 4 -2 Z",
        ),
    )
    val Braces: ImageVector = lucideIcon(
        "Braces",
        listOf(
            "M8 3H7a2 2 0 0 0-2 2v5a2 2 0 0 1-2 2 2 2 0 0 1 2 2v5c0 1.1.9 2 2 2h1",
            "M16 21h1a2 2 0 0 0 2-2v-5c0-1.1.9-2 2-2a2 2 0 0 1-2-2V5a2 2 0 0 0-2-2h-1",
        ),
    )
    val RefreshCw: ImageVector = lucideIcon(
        "RefreshCw",
        listOf(
            "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
            "M21 3v5h-5",
            "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
            "M8 16H3v5",
        ),
    )
    // Lucide "cog" (v1.41.0): the bolder gear — 12 straight teeth, r8 ring,
    // r2 hub. Chosen over "settings" because the latter's fine arc-teeth
    // blur into uneven bumps at 20dp drawer size; the cog's rectangular
    // teeth stay crisp. Every command is M-anchored and absolute, implicit
    // linetos made explicit; draw order (teeth, then ring, then hub)
    // matches lucide so the ring covers the spoke middles.
    val Settings: ImageVector = lucideIcon(
        "Settings",
        listOf(
            "M11 10.27 L7 3.34",
            "M11 13.73 L7 20.66",
            "M12 22 L12 20",
            "M12 2 L12 4",
            "M14 12 L22 12",
            "M17 20.66 L16 18.93",
            "M17 3.34 L16 5.07",
            "M2 12 L4 12",
            "M20.66 17 L18.93 16",
            "M20.66 7 L18.93 8",
            "M3.34 17 L5.07 16",
            "M3.34 7 L5.07 8",
            "M12 4 A8 8 0 1 0 12 20 M12 20 A8 8 0 1 0 12 4",
            "M12 10 A2 2 0 1 0 12 14 M12 14 A2 2 0 1 0 12 10",
        ),
    )
    val Search: ImageVector = lucideIcon(
        "Search",
        listOf(
            "m21 21-4.34-4.34",
            "M3 11a8 8 0 1 0 16 0a8 8 0 1 0-16 0",
        ),
    )
    val Mic: ImageVector = lucideIcon(
        "Mic",
        listOf(
            "M12 19v3",
            "M19 10v2a7 7 0 0 1-14 0v-2",
            "M12 2H12A3 3 0 0 1 15 5V12A3 3 0 0 1 12 15H12A3 3 0 0 1 9 12V5A3 3 0 0 1 12 2Z",
        ),
    )
    // Lucide "audio-waveform" (v0.294.0): single continuous sine trace for the
    // minimal collapsed bubble. Arc flags space-delimited per the rules above;
    // geometry otherwise verbatim.
    val AudioWaveform: ImageVector = lucideIcon(
        "AudioWaveform",
        listOf(
            "M2 13 a2 2 0 0 0 2 -2 V7 a2 2 0 0 1 4 0 v13 a2 2 0 0 0 4 0 V4 a2 2 0 0 1 4 0 v13 a2 2 0 0 0 4 0 v-4 a2 2 0 0 1 2 -2",
        ),
    )
    val X: ImageVector = lucideIcon(
        "X",
        listOf(
            "M18 6 6 18",
            "m6 6 12 12",
        ),
    )
    val MoreHorizontal: ImageVector = lucideIcon(
        "MoreHorizontal",
        listOf(
            "M11 12a1 1 0 1 0 2 0a1 1 0 1 0-2 0",
            "M18 12a1 1 0 1 0 2 0a1 1 0 1 0-2 0",
            "M4 12a1 1 0 1 0 2 0a1 1 0 1 0-2 0",
        ),
    )
    val Copy: ImageVector = lucideIcon(
        "Copy",
        listOf(
            "M10 8H20A2 2 0 0 1 22 10V20A2 2 0 0 1 20 22H10A2 2 0 0 1 8 20V10A2 2 0 0 1 10 8Z",
            "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2",
        ),
    )
    // Lucide share-2 + download (same v1.41.0 source as the Windows icons):
    // single-transcript share and bulk history export.
    val Share2: ImageVector = lucideIcon(
        "Share2",
        listOf(
            "M18 2 A3 3 0 1 0 18 8 M18 8 A3 3 0 1 0 18 2",
            "M6 9 A3 3 0 1 0 6 15 M6 15 A3 3 0 1 0 6 9",
            "M18 16 A3 3 0 1 0 18 22 M18 22 A3 3 0 1 0 18 16",
            "M8.59 13.51 L15.42 17.49",
            "M15.41 6.51 L8.59 10.49",
        ),
    )
    val Download: ImageVector = lucideIcon(
        "Download",
        listOf(
            "M12 15 V3",
            "M21 15 v4 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 v-4",
            "M7 10 L12 15 L17 10",
        ),
    )
    val TrendingUp: ImageVector = lucideIcon(
        "TrendingUp",
        listOf(
            "M16 7h6v6",
            "m22 7-8.5 8.5-5-5L2 17",
        ),
    )
    val TrendingDown: ImageVector = lucideIcon(
        "TrendingDown",
        listOf(
            "M16 17h6v-6",
            "m22 17-8.5-8.5-5 5L2 7",
        ),
    )
    val Minus: ImageVector = lucideIcon(
        "Minus",
        listOf("M5 12h14"),
    )
    val ChevronDown: ImageVector = lucideIcon(
        "ChevronDown",
        listOf("m6 9 6 6 6-6"),
    )
    // ── One-family ports (cross-platform parity audit) ──────────────────────
    // Check / Trash2 / Menu / Eye / ArrowUpDown ported from lucide-react
    // v1.41.0 — the exact version the Windows client renders — so shared
    // surfaces (History, Home, drawer chrome) draw identical geometry on
    // both platforms. Arc flags are space-delimited per the rules above;
    // the eye circle is converted to two half-arcs. Material AutoMirrored
    // ArrowBack is deliberately NOT ported: directional back chevrons must
    // mirror in RTL locales, which a raw path cannot do.
    val Check: ImageVector = lucideIcon(
        "Check",
        listOf("M20 6 9 17l-5-5"),
    )
    val Trash2: ImageVector = lucideIcon(
        "Trash2",
        listOf(
            "M10 11v6",
            "M14 11v6",
            "M19 6 v14 a2 2 0 0 1 -2 2 H7 a2 2 0 0 1 -2 -2 V6",
            "M3 6h18",
            "M8 6 V4 a2 2 0 0 1 2 -2 h4 a2 2 0 0 1 2 2 v2",
        ),
    )
    val Menu: ImageVector = lucideIcon(
        "Menu",
        listOf(
            "M4 5h16",
            "M4 12h16",
            "M4 19h16",
        ),
    )
    val Eye: ImageVector = lucideIcon(
        "Eye",
        listOf(
            "M2.062 12.348 a1 1 0 0 1 0 -0.696 10.75 10.75 0 0 1 19.876 0 a1 1 0 0 1 0 0.696 10.75 10.75 0 0 1 -19.876 0",
            "M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",
        ),
    )
    val ArrowUpDown: ImageVector = lucideIcon(
        "ArrowUpDown",
        listOf(
            "m21 16-4 4-4-4",
            "M17 20V4",
            "m3 8 4-4 4 4",
            "M7 4v16",
        ),
    )
    // Zap + Lightbulb (same lucide-react v1.41.0 source): the empty states
    // Windows pairs with these glyphs (Snippets, Suggested Corrections).
    val Zap: ImageVector = lucideIcon(
        "Zap",
        listOf(
            "M15.914 4 a1.5 1.5 0 0 0 -2.474 -1.561 l -9 9 A1.5 1.5 0 0 0 5.5 14 h4.002 a0.5 0.5 0 0 1 0.471 0.666 L8.086 20 a1.5 1.5 0 0 0 2.475 1.56 l9 -9 A1.5 1.5 0 0 0 18.5 10 h-3.997 a0.5 0.5 0 0 1 -0.472 -0.667 z",
        ),
    )
    val Lightbulb: ImageVector = lucideIcon(
        "Lightbulb",
        listOf(
            "M15 14 c0.2 -1 0.7 -1.7 1.5 -2.5 1 -0.9 1.5 -2.2 1.5 -3.5 A6 6 0 0 0 6 8 c0 1 0.2 2.2 1.5 3.5 0.7 0.7 1.3 1.5 1.5 2.5",
            "M9 18h6",
            "M10 22h4",
        ),
    )
    // Lucide "case-sensitive": drawer glyph for Text Formatting. Arc flags
    // space-delimited and the circle converted to two half-arcs per the
    // rules above; geometry otherwise verbatim.
    val CaseSensitive: ImageVector = lucideIcon(
        "CaseSensitive",
        listOf(
            "m2 16 4.039 -9.69 a0.5 0.5 0 0 1 0.923 0 L11 16",
            "M22 9v7",
            "M3.304 13h6.392",
            "M15 12.5 a3.5 3.5 0 1 0 7 0 a3.5 3.5 0 1 0 -7 0",
        ),
    )
    val EyeOff: ImageVector = lucideIcon(
        "EyeOff",
        listOf(
            "M10.733 5.076 a10.744 10.744 0 0 1 11.205 6.575 a1 1 0 0 1 0 0.696 a10.747 10.747 0 0 1 -1.444 2.49",
            "M14.084 14.158 a3 3 0 0 1 -4.242 -4.242",
            "M17.479 17.499 a10.75 10.75 0 0 1 -15.417 -5.151 a1 1 0 0 1 0 -0.696 a10.75 10.75 0 0 1 4.446 -5.143",
            "M2 2 L22 22",
        ),
    )
    val Bot: ImageVector = lucideIcon(
        "Bot",
        listOf(
            "M12 8 V4 H8",
            "M6 8 H18 A2 2 0 0 1 20 10 V18 A2 2 0 0 1 18 20 H6 A2 2 0 0 1 4 18 V10 A2 2 0 0 1 6 8 Z",
            "M2 14 H4",
            "M20 14 H22",
            "M15 13 V15",
            "M9 13 V15",
        ),
    )
    val Smartphone: ImageVector = lucideIcon(
        "Smartphone",
        listOf(
            "M7 2 H17 A2 2 0 0 1 19 4 V20 A2 2 0 0 1 17 22 H7 A2 2 0 0 1 5 20 V4 A2 2 0 0 1 7 2 Z",
            "M12 18 H12.01",
        ),
    )
    val Shield: ImageVector = lucideIcon(
        "Shield",
        listOf(
            "M20 13 c0 5 -3.5 7.5 -7.66 8.95 a1 1 0 0 1 -0.67 -0.01 C7.5 20.5 4 18 4 13 V6 a1 1 0 0 1 1 -1 c2 0 4.5 -1.2 6.24 -2.72 a1.17 1.17 0 0 1 1.52 0 C14.51 3.81 17 5 19 5 a1 1 0 0 1 1 1 Z",
        ),
    )
    val Lock: ImageVector = lucideIcon(
        "Lock",
        listOf(
            "M5 11 H19 A2 2 0 0 1 21 13 V20 A2 2 0 0 1 19 22 H5 A2 2 0 0 1 3 20 V13 A2 2 0 0 1 5 11 Z",
            "M7 11 V7 a5 5 0 0 1 10 0 v4",
        ),
    )
    val CircleDot: ImageVector = lucideIcon(
        "CircleDot",
        listOf(
            "M12 2 A10 10 0 1 0 12 22 M12 22 A10 10 0 1 0 12 2",
            "M12 11 A1 1 0 1 0 12 13 M12 13 A1 1 0 1 0 12 11",
        ),
    )
    val Volume2: ImageVector = lucideIcon(
        "Volume2",
        listOf(
            "M11 4.702 a0.705 0.705 0 0 0 -1.203 -0.498 L6.413 7.587 A1.4 1.4 0 0 1 5.416 8 H3 a1 1 0 0 0 -1 1 v6 a1 1 0 0 0 1 1 h2.416 a1.4 1.4 0 0 1 0.997 0.413 l3.383 3.384 A0.705 0.705 0 0 0 11 19.298 Z",
            "M16 9 a5 5 0 0 1 0 6",
            "M19.364 18.364 a9 9 0 0 0 0 -12.728",
        ),
    )
    val Sun: ImageVector = lucideIcon(
        "Sun",
        listOf(
            "M12 8 A4 4 0 1 0 12 16 M12 16 A4 4 0 1 0 12 8",
            "M12 2 V4",
            "M12 20 V22",
            "M4.93 4.93 L6.34 6.34",
            "M17.66 17.66 L19.07 19.07",
            "M2 12 H4",
            "M20 12 H22",
            "M6.34 17.66 L4.93 19.07",
            "M19.07 4.93 L17.66 6.34",
        ),
    )
    val Moon: ImageVector = lucideIcon(
        "Moon",
        listOf(
            "M12 3 a6 6 0 0 0 9 9 a9 9 0 1 1 -9 -9 Z",
        ),
    )
    val Info: ImageVector = lucideIcon(
        "Info",
        listOf(
            "M12 2 A10 10 0 1 0 12 22 M12 22 A10 10 0 1 0 12 2",
            "M12 16 V12",
            "M12 8 H12.01",
        ),
    )
    val Keyboard: ImageVector = lucideIcon(
        "Keyboard",
        listOf(
            "M4 4 H20 A2 2 0 0 1 22 6 V18 A2 2 0 0 1 20 20 H4 A2 2 0 0 1 2 18 V6 A2 2 0 0 1 4 4 Z",
            "M10 8 H10.01",
            "M12 12 H12.01",
            "M14 8 H14.01",
            "M16 12 H16.01",
            "M18 8 H18.01",
            "M6 8 H6.01",
            "M7 16 H17",
            "M8 12 H8.01",
        ),
    )
    val Layers: ImageVector = lucideIcon(
        "Layers",
        listOf(
            "M12.83 2.18 a2 2 0 0 0 -1.66 0 L2.6 6.08 a1 1 0 0 0 0 1.83 l8.58 3.91 a2 2 0 0 0 1.66 0 l8.58 -3.9 a1 1 0 0 0 0 -1.83 Z",
            "M2 12 a1 1 0 0 0 0.58 0.91 l8.6 3.91 a2 2 0 0 0 1.65 0 l8.58 -3.9 A1 1 0 0 0 22 12",
            "M2 17 a1 1 0 0 0 0.58 0.91 l8.6 3.91 a2 2 0 0 0 1.65 0 l8.58 -3.9 A1 1 0 0 0 22 17",
        ),
    )
    val PersonStanding: ImageVector = lucideIcon(
        "PersonStanding",
        listOf(
            "M12 4 A1 1 0 1 0 12 6 M12 6 A1 1 0 1 0 12 4",
            "M9 20 L12 14 L15 20",
            "M6 8 L12 10 L18 8",
            "M12 10 V14",
        ),
    )
    val BatteryWarning: ImageVector = lucideIcon(
        "BatteryWarning",
        listOf(
            "M10 17 H10.01",
            "M10 7 V13",
            "M14 6 H16 A2 2 0 0 1 18 8 V16 A2 2 0 0 1 16 18 H14",
            "M22 11 V13",
            "M6 18 H4 A2 2 0 0 1 2 16 V8 A2 2 0 0 1 4 6 H6",
        ),
    )
}
