package com.groq.voicetyper

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import com.groq.voicetyper.ui.icons.FluenceIcons
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.theme.rememberReducedMotion
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.TextPrimary
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.composed
import kotlin.math.PI
import kotlin.math.sin

// ── Pill themes (paint only) ────────────────────────────────────────────────
// Curated presets for the pill's static paints, collapsed shell included.
// Hardcoded literals: the overlay service has no theme wrapper, so
// PrecisionTheme is BANNED in this file (it would silently resolve to dark
// defaults). The waveform is NOT themed — amethyst wave = transcription, teal
// wave = agent, in every preset except Light, which deepens both hues for
// contrast on white (Windows light-mode parity). That fixed signal is how the
// modes stay distinguishable while everything else follows the preset.
// Sizes, animation targets, Crossfade, gestures, and sticky behavior are
// untouched by themes.
enum class PillTheme(
    val prefValue: String,
    val label: String,
    val description: String,
    // Shell + wells.
    val shellBase: Color,
    val cancelWell: Color,
    val cancelIcon: Color,
    val waveWellBg: Color,
    val waveWellBorder: Color,
    // Waveform mode pairs (T = transcription, A = agent).
    val waveT: Color,
    val waveTFore: Color,
    val waveA: Color,
    val waveAFore: Color,
    // Confirm button.
    val confirmBg: Color,
    val confirmIcon: Color,
    // Shell dressing.
    val glowBase: Color,
    val glowAlphaScale: Float,
    val borderStart: Color,
    val borderEnd: Color,
    // Dimmed idle branch + functional states.
    val dimmedBase: Color,
    val dimmedHairline: Color,
    val spinner: Color,
    val error: Color,
) {
    OBSIDIAN(
        prefValue = FloatingBubblePreferences.PILL_THEME_OBSIDIAN,
        label = "Obsidian",
        description = "Signature amethyst glow",
        shellBase = Color(0xEA0D0E12),
        cancelWell = Color(0x1AFFFFFF),
        cancelIcon = Color.White,
        waveWellBg = Color(0x0CFFFFFF),
        waveWellBorder = Color(0x0DFFFFFF),
        waveT = Color(0xFFA855F7),
        waveTFore = Color(0xFFF3E8FF),
        waveA = Color(0xFF00F5D4),
        waveAFore = Color(0xFFE6FFFA),
        confirmBg = Color(0xFFA855F7),
        confirmIcon = Color.White,
        glowBase = Color(0xFFA855F7),
        glowAlphaScale = 1f,
        borderStart = Color(0xFFA855F7),
        borderEnd = Color(0xFF6366F1),
        dimmedBase = Color(0x1F0D0E12),
        dimmedHairline = Color(0x4DFFFFFF),
        spinner = TextPrimary,
        error = Color(0xFFFF5252),
    ),
    MONO(
        prefValue = FloatingBubblePreferences.PILL_THEME_MONO,
        label = "Mono",
        description = "No glow color, all neutral",
        shellBase = Color(0xEA0D0E12),
        cancelWell = Color(0x1AFFFFFF),
        cancelIcon = Color.White,
        waveWellBg = Color(0x0CFFFFFF),
        waveWellBorder = Color(0x0DFFFFFF),
        waveT = Color(0xFFA855F7),
        waveTFore = Color(0xFFF3E8FF),
        waveA = Color(0xFF00F5D4),
        waveAFore = Color(0xFFE6FFFA),
        confirmBg = Color(0x29FFFFFF),
        confirmIcon = Color.White,
        glowBase = Color(0xFFFFFFFF),
        glowAlphaScale = 0.5f,
        // Seamless: obsidian shell color, so no visible border ring.
        borderStart = Color(0xEA0D0E12),
        borderEnd = Color(0xEA0D0E12),
        dimmedBase = Color(0x1F0D0E12),
        dimmedHairline = Color(0x4DFFFFFF),
        spinner = TextPrimary,
        error = Color(0xFFFF5252),
    ),
    HIGH_CONTRAST(
        prefValue = FloatingBubblePreferences.PILL_THEME_HIGH_CONTRAST,
        label = "High contrast",
        description = "Maximum legibility",
        shellBase = Color(0xEA0D0E12),
        cancelWell = Color(0x1AFFFFFF),
        cancelIcon = Color.White,
        waveWellBg = Color(0x0CFFFFFF),
        waveWellBorder = Color(0x33FFFFFF),
        waveT = Color(0xFFA855F7),
        waveTFore = Color(0xFFF3E8FF),
        waveA = Color(0xFF00F5D4),
        waveAFore = Color(0xFFE6FFFA),
        confirmBg = Color(0xFFFFFFFF),
        confirmIcon = Color(0xFF0D0E12),
        glowBase = Color(0xFFFFFFFF),
        glowAlphaScale = 0.7f,
        borderStart = Color(0xFFFFFFFF),
        borderEnd = Color(0xFFFFFFFF),
        dimmedBase = Color(0x1F0D0E12),
        dimmedHairline = Color(0x4DFFFFFF),
        spinner = Color.White,
        error = Color(0xFFFF5252),
    ),
    LIGHT(
        prefValue = FloatingBubblePreferences.PILL_THEME_LIGHT,
        label = "Light",
        description = "Bright shell for light setups",
        shellBase = Color(0xF2F5F5F7),
        cancelWell = Color(0x14000000),
        cancelIcon = Color(0xFF18181B),
        waveWellBg = Color(0x0F000000),
        waveWellBorder = Color(0x1F000000),
        waveT = Color(0xFF8B45D8),
        waveTFore = Color(0xFF8B45D8),
        waveA = Color(0xFF0E7490),
        waveAFore = Color(0xFF0E7490),
        confirmBg = Color(0xFF3F3F46),
        confirmIcon = Color.White,
        // Contrast gray halo: white glow vanishes on white apps, so Light
        // uses a mid-gray that reads on bright surfaces and stays subtle
        // on dark ones.
        glowBase = Color(0xFF8E8E93),
        glowAlphaScale = 0.55f,
        borderStart = Color(0xFFD4D4D8),
        borderEnd = Color(0xFFD4D4D8),
        dimmedBase = Color(0x1FFFFFFF),
        dimmedHairline = Color(0x4D000000),
        spinner = Color(0xFF18181B),
        error = Color(0xFFDC2626),
    );

    companion object {
        fun forName(raw: String?): PillTheme =
            entries.firstOrNull { it.prefValue == raw } ?: OBSIDIAN
    }
}

@Composable
fun FloatingBubbleUI(
    isAnchoredRight: Boolean,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragReleased: () -> Unit,
    onWidthUpdated: (Float) -> Unit
) {
    val context = LocalContext.current
    // Overlay service: no theme wrapper here, so the system signal is read
    // directly (same source the app theme provisions via CompositionLocal).
    val reducedMotion = rememberReducedMotion()
    val view = androidx.compose.ui.platform.LocalView.current
    val isExpanded by BubbleController.isBubbleExpanded.collectAsState()
    val recordingState by BubbleController.recordingState.collectAsState()
    val errorMessage by BubbleController.errorMessage.collectAsState()
    val anchoredRight by BubbleController.isAnchoredRight.collectAsState()
    val contentAlignment = if (anchoredRight) Alignment.TopEnd else Alignment.TopStart
    val coroutineScope = rememberCoroutineScope()

    // Size animations for morphing transition.
    // tween(250ms) provides ~15 frames of smooth, perceivable animation.
    // FastOutSlowInEasing is Material Design's standard "elements moving into place" curve.
    val animSpec: FiniteAnimationSpec<Dp> = if (reducedMotion) snap() else
        tween(durationMillis = 250, easing = FastOutSlowInEasing)
    val width by animateDpAsState(
        targetValue = if (isExpanded) 240.dp else 56.dp,
        animationSpec = animSpec,
        label = "width"
    )

    // Only notify the Service when width actually changes.
    // SideEffect fires on EVERY recomposition (including 60fps waveform frames)
    // which hammered updateViewLayout unnecessarily, causing right-side stutter.
    LaunchedEffect(Unit) {
        snapshotFlow { width.value }
            .distinctUntilChanged()
            .collect { widthDp -> onWidthUpdated(widthDp) }
    }
    val height by animateDpAsState(
        targetValue = if (isExpanded) 64.dp else 56.dp,
        animationSpec = animSpec,
        label = "height"
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (isExpanded) 32.dp else 28.dp,
        label = "cornerRadius"
    )

    val shape = RoundedCornerShape(cornerRadius)
    var idleOpacity by remember { mutableFloatStateOf(FloatingBubblePreferences.getOpacity(context)) }
    var pillThemeName by remember { mutableStateOf(FloatingBubblePreferences.getPillTheme(context)) }
    var glowEnabled by remember { mutableStateOf(FloatingBubblePreferences.isGlowEnabled(context)) }
    var collapsedStyleName by remember { mutableStateOf(FloatingBubblePreferences.getCollapsedStyle(context)) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("fluence_prefs", android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == FloatingBubblePreferences.KEY_OPACITY) {
                idleOpacity = FloatingBubblePreferences.getOpacity(context)
            } else if (key == FloatingBubblePreferences.KEY_PILL_THEME) {
                pillThemeName = FloatingBubblePreferences.getPillTheme(context)
            } else if (key == FloatingBubblePreferences.KEY_GLOW_ENABLED) {
                glowEnabled = FloatingBubblePreferences.isGlowEnabled(context)
            } else if (key == FloatingBubblePreferences.KEY_COLLAPSED_STYLE) {
                collapsedStyleName = FloatingBubblePreferences.getCollapsedStyle(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    // Theme preset is hoisted here (never read inside draw loops) and only
    // feeds static paints — animation targets, gestures, and sticky behavior
    // never see it.
    // Collapsed vs expanded are INDEPENDENT: the expanded pill follows
    // pillTheme, the collapsed bubble follows collapsedStyle only. Minimal
    // adapts to Light for contrast (white shell needs the dark mic variant);
    // otherwise it stays neutral mono regardless of the expanded theme.
    val pillTheme = remember(pillThemeName) { PillTheme.forName(pillThemeName) }
    val collapsedTheme = remember(collapsedStyleName, pillThemeName) {
        when {
            collapsedStyleName == FloatingBubblePreferences.COLLAPSED_MINIMAL &&
                pillThemeName == FloatingBubblePreferences.PILL_THEME_LIGHT -> PillTheme.LIGHT
            collapsedStyleName == FloatingBubblePreferences.COLLAPSED_MINIMAL -> PillTheme.MONO
            else -> PillTheme.OBSIDIAN
        }
    }
    val waveAgentMode by BubbleController.isAgentMode.collectAsState()
    // Idle dimming — pure Compose render-layer opacity, no WindowManager involvement.
    // Fully opaque while active (expanded, recording/transcribing, or error feedback);
    // dims to idleOpacity when idle. Starts dimmed on mount; after a real active→idle
    // transition it holds full opacity ~2500ms as transcription-completion feedback.
    val isActive = isExpanded || recordingState != RecordingState.IDLE || errorMessage != null
    var wasActive by remember { mutableStateOf(false) }
    var dimmed by remember { mutableStateOf(true) }
    // One-shot confirmation pop — fires on idle→active (tap feedback) and on
    // expanded→collapsed (transcription finished). Mount, drag, and theme
    // changes never fire it. Animatable (not key()) so firing never disposes
    // the gesture subtree; zero cost at rest.
    var confirmKey by remember { mutableIntStateOf(0) }
    var prevExpanded by remember { mutableStateOf(false) }
    val confirmT = remember { Animatable(1f) }
    LaunchedEffect(isActive) {
        if (isActive) {
            if (!wasActive) confirmKey++
            dimmed = false
        } else if (wasActive) {
            kotlinx.coroutines.delay(2500)
            dimmed = true
        }
        wasActive = isActive
    }
    LaunchedEffect(isExpanded) {
        if (prevExpanded && !isExpanded) confirmKey++
        prevExpanded = isExpanded
    }
    LaunchedEffect(confirmKey) {
        if (confirmKey == 0) return@LaunchedEffect
        if (reducedMotion) {
            confirmT.snapTo(1f)
        } else {
            confirmT.snapTo(0f)
            // 450ms: slow enough to read the orb's single spin, quick enough
            // to stay a confirmation beat rather than ambient motion.
            confirmT.animateTo(1f, tween(durationMillis = 450, easing = FastOutSlowInEasing))
        }
    }
    val dimAlpha by animateFloatAsState(
        targetValue = if (dimmed) idleOpacity else 1f,
        animationSpec = if (reducedMotion) {
            snap()
        } else if (dimmed) {
            tween(durationMillis = 400, easing = FastOutSlowInEasing)
        } else {
            tween(durationMillis = 200, easing = FastOutSlowInEasing)
        },
        label = "bubbleAlpha"
    )
    Box(
        modifier = Modifier
            // Preserve the fixed V1 visual frame for both collapsed and expanded states.
            .widthIn(min = 272.dp)
            .heightIn(min = 96.dp)
            .padding(16.dp),
        contentAlignment = contentAlignment
    ) {
        Box(
            modifier = Modifier
                .size(width = width, height = height)
                .amethystObsidianGlow(isExpanded = isExpanded, theme = if (isExpanded) pillTheme else collapsedTheme, glowOn = glowEnabled, shape = shape, dimmed = dimmed, agentMode = waveAgentMode)
                .clip(shape)
                // Idle dimming via RenderNode layer alpha — dims glow, border, background,
                // and content together. Does not affect layout, hit testing, or the window.
                // The confirmation beat rides the same layer (settle + fade, one shot)
                // so the tap→expand morph is felt; the collapsed mark itself blooms
                // separately below, which is the clearly visible part.
                .graphicsLayer {
                    val t = confirmT.value
                    alpha = dimAlpha * (0.5f + 0.5f * t)
                    val settle = 0.9f + 0.1f * t
                    scaleX = settle
                    scaleY = settle
                }
                // Gesture handling for Collapsed state (drag, instant tap, hold for agent mode)
                .run {
                    if (!isExpanded) {
                        this.pointerInput(isExpanded, recordingState) {
                            awaitPointerEventScope {
                                while (true) {
                                    val down = awaitFirstDown()
                                    val startPos = down.position
                                    var isDragging = false
                                    var isLongPressTriggered = false

                                    val longPressJob = coroutineScope.launch {
                                        kotlinx.coroutines.delay(500)
                                        if (!isDragging && (recordingState == RecordingState.IDLE || recordingState == RecordingState.ERROR)) {
                                            isLongPressTriggered = true
                                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                            BubbleController.startRecording(context, agentMode = true)
                                        }
                                    }

                                    do {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull() ?: continue

                                        if (change.pressed) {
                                            val currentPos = change.position
                                            val dragDistance = (currentPos - startPos).getDistance()

                                            if (dragDistance > 8.dp.toPx()) {
                                                isDragging = true
                                                longPressJob.cancel()
                                            }

                                            if (isDragging) {
                                                val dx = change.position.x - change.previousPosition.x
                                                val dy = change.position.y - change.previousPosition.y
                                                onDrag(dx, dy)
                                            }
                                            change.consume()
                                        } else {
                                            break
                                        }
                                    } while (true)

                                    longPressJob.cancel()

                                    if (isDragging) {
                                        onDragReleased()
                                    } else {
                                        if (!isLongPressTriggered) {
                                            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                            if (recordingState == RecordingState.RECORDING) {
                                                BubbleController.stopRecording(context)
                                            } else if (recordingState == RecordingState.IDLE || recordingState == RecordingState.ERROR) {
                                                BubbleController.startRecording(context, agentMode = false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else this
                },
            contentAlignment = Alignment.Center
        ) {
            // Crossfade provides a smooth alpha-blended transition between the
            // collapsed logo and the expanded pill content. Without this, the
            // content swaps in a single frame while the width is still mid-
            // animation, creating a visual "jump" on both left and right sides.
            Crossfade(
                targetState = isExpanded,
                animationSpec = if (reducedMotion) snap() else tween(durationMillis = 200),
                label = "bubbleContent"
            ) { targetExpanded ->
                if (!targetExpanded) {
                    // Minimal is fully static — no beat, no bloom, no wipe.
                    // Classic and Original play the confirmation beat below.
                    val beatT = if (collapsedStyleName == FloatingBubblePreferences.COLLAPSED_MINIMAL) {
                        1f
                    } else {
                        confirmT.value
                    }
                    // Mark bloom: the collapsed mark scales 0.6→1.0 and fades in
                    // on every confirmation beat — the visible moment after
                    // collapse. Same beat drives the Classic bars via live and
                    // the orb's single spin.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = beatT
                                val bloom = 0.6f + 0.4f * beatT
                                scaleX = bloom
                                scaleY = bloom
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Classic and Original stay alive only during the
                        // post-collapse full-glow hold — never dimmed, never
                        // reduced-motion. Minimal rests above at beat 1.
                        val collapsedLive = !isExpanded && !dimmed
                        when (collapsedStyleName) {
                            FloatingBubblePreferences.COLLAPSED_MINIMAL ->
                                MinimalCollapsedIcon(theme = collapsedTheme, drawIn = beatT)
                            FloatingBubblePreferences.COLLAPSED_CLASSIC ->
                                ClassicCollapsedOrb(live = collapsedLive)
                            else -> FluenceLogoIcon(spin = beatT)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 1. Cancel Button (Left)
                        IconButton(
                            onClick = { BubbleController.cancelRecording() },
                            modifier = Modifier
                                .size(44.dp)
                                .background(pillTheme.cancelWell, CircleShape)
                        ) {
                            Canvas(modifier = Modifier.size(14.dp)) {
                                val w = size.width
                                val h = size.height
                                drawLine(
                                    color = pillTheme.cancelIcon,
                                    start = Offset(0f, 0f),
                                    end = Offset(w, h),
                                    strokeWidth = 2.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawLine(
                                    color = pillTheme.cancelIcon,
                                    start = Offset(w, 0f),
                                    end = Offset(0f, h),
                                    strokeWidth = 2.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                            }
                        }

                        // 2. Siri Waveform Pill (Center)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .padding(horizontal = 8.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(pillTheme.waveWellBg)
                                .border(1.dp, pillTheme.waveWellBorder, RoundedCornerShape(24.dp))
                                .clickable {
                                    if (recordingState == RecordingState.RECORDING) {
                                        BubbleController.stopRecording(context)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (recordingState == RecordingState.TRANSCRIBING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = pillTheme.spinner,
                                    strokeWidth = 2.dp
                                )
                            } else if (recordingState == RecordingState.ERROR) {
                                Text(
                                    text = errorMessage ?: "Error",
                                    color = pillTheme.error,
                                    style = FluenceTypography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            } else {
                                SiriWaveform(
                                    theme = pillTheme,
                                    agentMode = waveAgentMode,
                                )
                            }
                        }

                        // 3. Confirm Button (Right) — preset, except Obsidian
                        // agent mode takes the teal bg like previous versions
                        // (X stays preset). Mono / High contrast / Light keep
                        // their preset accept — only their waveform changes.
                        val obsidianAgentConfirm = waveAgentMode &&
                            pillTheme.prefValue == FloatingBubblePreferences.PILL_THEME_OBSIDIAN
                        val confirmBgColor =
                            if (obsidianAgentConfirm) pillTheme.waveA else pillTheme.confirmBg
                        val confirmIconColor =
                            if (obsidianAgentConfirm) Color(0xFF0D0E12) else pillTheme.confirmIcon
                        IconButton(
                            onClick = { BubbleController.stopRecording(context) },
                            modifier = Modifier
                                .size(44.dp)
                                .background(confirmBgColor, CircleShape)
                        ) {
                            Canvas(modifier = Modifier.size(16.dp)) {
                                val w = size.width
                                val h = size.height
                                val path = Path().apply {
                                    moveTo(w * 0.2f, h * 0.5f)
                                    lineTo(w * 0.45f, h * 0.75f)
                                    lineTo(w * 0.85f, h * 0.25f)
                                }
                                drawPath(
                                    path = path,
                                    color = confirmIconColor,
                                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Custom Modifier that draws a high-end glowing background with amethyst obsidian aesthetic.
 */
fun Modifier.amethystObsidianGlow(
    isExpanded: Boolean,
    theme: PillTheme,
    glowOn: Boolean,
    glowRadius: Dp = 8.dp,
    shape: RoundedCornerShape,
    dimmed: Boolean = false,
    agentMode: Boolean = false
): Modifier = this.composed {
    // Agent mode follows the preset everywhere except two call-site/override
    // points: the teal confirm button (handled at the call site, every theme)
    // and — Obsidian expanded only — a teal border + glow takeover so agent
    // mode reads instantly on the signature theme. Other themes keep their
    // own dressing (Mono stays seamless neutral). The preset also dresses the
    // collapsed orb shell; only the dimmed idle branch below stays frozen,
    // and animation targets, gestures, and sticky behavior are untouched.
    // Obsidian literals equal the pre-theme paints, so the default theme
    // renders pixel-identical to the frozen look.
    val obsidianAgent = agentMode && isExpanded &&
        theme.prefValue == FloatingBubblePreferences.PILL_THEME_OBSIDIAN
    val baseGlowColor = if (obsidianAgent) theme.waveA else theme.glowBase
    val glowAlpha = when {
        !glowOn -> 0f
        !isExpanded -> 0.45f
        else -> 0.65f * theme.glowAlphaScale
    }
    val glowColor = baseGlowColor.copy(alpha = glowAlpha)

    if (dimmed) {
        // Quiet-glass idle look: no glow/bloom layers, translucent base,
        // faint hairline. The callsite graphicsLayer alpha further subdues it.
        return@composed this.background(
            color = theme.dimmedBase,
            shape = shape
        ).border(
            width = 0.8.dp,
            color = theme.dimmedHairline,
            shape = shape
        )
    }

    this.drawBehind {
        val shapeRadiusPx = shape.topStart.toPx(size, this)
        val maxOffset = glowRadius.toPx()

        // Draw concentric rounded rectangles to build a smooth hardware-accelerated glow
        val steps = 5
        for (i in 1..steps) {
            val offset = maxOffset * (i.toFloat() / steps)
            val alpha = glowColor.alpha * (1.0f - (i.toFloat() / (steps + 1)))
            val strokeWidth = maxOffset / steps * 1.5f

            drawRoundRect(
                color = glowColor.copy(alpha = alpha),
                topLeft = Offset(-offset, -offset),
                size = Size(size.width + offset * 2, size.height + offset * 2),
                cornerRadius = CornerRadius(shapeRadiusPx + offset, shapeRadiusPx + offset),
                style = Stroke(width = strokeWidth)
            )
        }
    }
    .background(
        color = theme.shellBase,
        shape = shape
    )
    .border(
        width = 1.2.dp,
        brush = Brush.linearGradient(
            colors = if (obsidianAgent) {
                listOf(
                    theme.waveA,
                    theme.waveA.copy(alpha = 0.5f)
                )
            } else {
                listOf(
                    theme.borderStart,
                    theme.borderEnd.copy(alpha = 0.5f)
                )
            }
        ),
        shape = shape
    )
}

/**
 * Fluence brand logo icon — replaces the old animated orb for a clean, premium look.
 *
 * [spin] drives one quick circular rotation per confirmation beat (0→1 maps
 * to 0→360°, resting exactly at today's look). Reduced motion is handled
 * upstream — the beat snaps to 1, so 0° static.
 */
@Composable
fun FluenceLogoIcon(spin: Float = 1f) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_fluence_logo),
            contentDescription = "Fluence",
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = spin * 360f }
        )
    }
}

/**
 * Classic collapsed orb — the pre-logo look, restored verbatim from the
 * V1 MiniFluenceOrb paints: amethyst aura (frozen at mid-pulse), frosted
 * amethyst glass circle, white 3-line equalizer. When [live] the equalizer
 * bars gently bounce (bounded to the post-collapse full-glow hold — never
 * while dimmed, never under reduced motion, so zero idle cost). Same 56dp
 * frame, same gestures and touch envelope — paint only.
 */
@Composable
fun ClassicCollapsedOrb(live: Boolean = false) {
    val reducedMotion = rememberReducedMotion()
    val animateBars = live && !reducedMotion
    val phase by if (animateBars) {
        rememberInfiniteTransition(label = "eqHold").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "eqPhase"
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    val pulseScale = 1f
    val pulseAlpha = 0.6f
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Glowing Outer Radial Aura
        Canvas(modifier = Modifier.size(56.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFA855F7).copy(alpha = 0.4f * pulseAlpha),
                        Color(0xFFA855F7).copy(alpha = 0.02f * pulseAlpha),
                        Color.Transparent
                    )
                ),
                radius = size.width / 2 * pulseScale
            )
        }

        // Inner frosted amethyst glass circle
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF7C3AED).copy(alpha = 0.5f),
                            Color(0xFFC084FC).copy(alpha = 0.2f)
                        )
                    ),
                    shape = CircleShape
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.3f),
                            Color(0xFFA855F7).copy(alpha = 0.1f)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // Mini 3-line equalizer. At rest the bars sit at their static
            // heights; while live each bar breathes around its base with a
            // phase offset (one shared loop, no per-bar clocks).
            Canvas(modifier = Modifier.size(14.dp)) {
                val lineStroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)

                fun barHeight(base: Float, offset: Float): Float {
                    if (!animateBars) return size.height * base
                    val wave = sin((phase + offset) * 2f * Math.PI.toFloat())
                    return size.height * base * (0.8f + 0.2f * wave)
                }
                val h1 = barHeight(0.4f, 0f)
                val h2 = barHeight(0.8f, 0.33f)
                val h3 = barHeight(0.5f, 0.66f)

                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.25f, size.height * 0.5f - h1 / 2),
                    end = Offset(size.width * 0.25f, size.height * 0.5f + h1 / 2),
                    strokeWidth = lineStroke.width,
                    cap = lineStroke.cap
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.5f, size.height * 0.5f - h2 / 2),
                    end = Offset(size.width * 0.5f, size.height * 0.5f + h2 / 2),
                    strokeWidth = lineStroke.width,
                    cap = lineStroke.cap
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.75f, size.height * 0.5f - h3 / 2),
                    end = Offset(size.width * 0.75f, size.height * 0.5f + h3 / 2),
                    strokeWidth = lineStroke.width,
                    cap = lineStroke.cap
                )
            }
        }
    }
}

/**
 * Minimal collapsed icon — quiet Lucide waveform trace on the collapsed
 * shell. Tint comes from the collapsed theme (white on dark, near-black on
 * Light) so it stays legible without the colorful orb. Same 56dp frame,
 * same gestures and touch envelope — paint only.
 */
@Composable
fun MinimalCollapsedIcon(theme: PillTheme, drawIn: Float = 1f) {
    // The exact static Lucide glyph — at rest (drawIn = 1) pixel-identical to
    // today's look. On the confirmation beat a start-anchored window wipes it
    // in left→right with a fade, once, then rests until dim. Reduced motion
    // snaps the beat to 1.
    val progress = drawIn.coerceIn(0f, 1f)
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { alpha = 0.35f + 0.65f * progress },
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceAtLeast(0.001f))
                    .clipToBounds()
            ) {
                Icon(
                    imageVector = FluenceIcons.AudioWaveform,
                    contentDescription = "Fluence",
                    tint = theme.cancelIcon,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Siri-Style multi-layered animated sine wave visualizer.
 */
@Composable
fun SiriWaveform(
    theme: PillTheme,
    agentMode: Boolean,
) {
    val rawAmplitude by BubbleController.amplitude.collectAsState()
    val reducedMotion = rememberReducedMotion()

    // Mode pairs from the preset — amethyst-family wave = transcription, teal
    // wave = agent. This fixed-per-mode mapping is how the modes stay
    // distinguishable while everything else follows the preset.
    val primaryColor = if (agentMode) theme.waveA else theme.waveT
    val forefrontColor = if (agentMode) theme.waveAFore else theme.waveTFore

    // Smooth and boost the amplitude to prevent jerky jumps from 50ms polling.
    // Amplitude is live data (not decoration), so it still responds under
    // reduced motion — only the decorative phase drift freezes.
    val smoothedAmplitude by animateFloatAsState(
        targetValue = (rawAmplitude * 6f).coerceIn(0f, 1f),
        animationSpec = if (reducedMotion) snap() else spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "amplitude"
    )

    // Dynamically integrate phase for speed changes without jumps
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var lastTime = withFrameNanos { it }
        while (isActive) {
            val currentTime = withFrameNanos { it }
            val dt = (currentTime - lastTime) / 1e9f
            lastTime = currentTime

            // Speed increases when voice detects (smoothedAmplitude is higher)
            val speed = 1f + smoothedAmplitude * 4f
            if (!reducedMotion) {
                phase = (phase + speed * dt * 2f * Math.PI.toFloat()) % (1000f * Math.PI.toFloat())
            }
        }
    }

    val phase1 = phase
    val phase2 = -phase * 0.7f

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        // Determine actual height amplitude (minimum idle height of 0.1f)
        val activeAmplitude = (smoothedAmplitude * 0.8f + 0.1f) * (height * 0.45f)

        // Gradient brushes to fade out the waves near the left and right edges
        val gradientBrush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, forefrontColor.copy(alpha = 0.9f), Color.Transparent),
            startX = 0f,
            endX = width
        )
        val bgGradientBrush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, primaryColor.copy(alpha = 0.4f), Color.Transparent),
            startX = 0f,
            endX = width
        )

        // Wave 1: Background Wave
        val path1 = Path()
        path1.moveTo(0f, centerY)
        for (x in 0..width.toInt() step 6) {
            val xVal = x.toFloat()
            // Parabolic envelope to taper wave heights to 0 at edges
            val envelope = sin((xVal / width) * Math.PI.toFloat())
            val angle = (xVal / width) * 2f * Math.PI.toFloat() * 1.5f + phase1
            // Adding high frequency vibration based on amplitude
            val vibration = sin(xVal * 0.1f + phase1 * 3f) * smoothedAmplitude * 4f
            val yVal = centerY + (sin(angle) * activeAmplitude * 0.5f + vibration) * envelope
            path1.lineTo(xVal, yVal)
        }
        drawPath(
            path = path1,
            brush = bgGradientBrush,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Wave 2: Middle Wave
        val path2 = Path()
        path2.moveTo(0f, centerY)
        for (x in 0..width.toInt() step 6) {
            val xVal = x.toFloat()
            val envelope = sin((xVal / width) * Math.PI.toFloat())
            val angle = (xVal / width) * 2f * Math.PI.toFloat() * 2.5f + phase2
            val vibration = sin(xVal * 0.15f - phase2 * 4f) * smoothedAmplitude * 3f
            val yVal = centerY + (sin(angle) * activeAmplitude * 0.7f + vibration) * envelope
            path2.lineTo(xVal, yVal)
        }
        drawPath(
            path = path2,
            brush = bgGradientBrush,
            style = Stroke(width = 1.8.dp.toPx())
        )

        // Wave 3: Forefront Delicate Wave
        val path3 = Path()
        path3.moveTo(0f, centerY)
        for (x in 0..width.toInt() step 6) {
            val xVal = x.toFloat()
            val envelope = sin((xVal / width) * Math.PI.toFloat())
            val angle = (xVal / width) * 2f * Math.PI.toFloat() * 1.2f + (phase1 - phase2) * 0.5f
            val vibration = sin(xVal * 0.08f + phase1 * 5f) * smoothedAmplitude * 5f
            val yVal = centerY + (sin(angle) * activeAmplitude * 0.9f + vibration) * envelope
            path3.lineTo(xVal, yVal)
        }
        drawPath(
            path = path3,
            brush = gradientBrush,
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
