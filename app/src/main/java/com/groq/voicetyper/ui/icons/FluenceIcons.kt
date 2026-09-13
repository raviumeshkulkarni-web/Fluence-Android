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
}
