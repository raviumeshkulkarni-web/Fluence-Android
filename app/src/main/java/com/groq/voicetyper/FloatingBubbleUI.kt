package com.groq.voicetyper

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.unit.IntOffset
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
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
    cardX: Int,
    cardY: Int,
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
        animationSpec = animSpec,
        label = "cornerRadius"
    )

    val shape = RoundedCornerShape(cornerRadius)
    var idleOpacity by remember { mutableFloatStateOf(FloatingBubblePreferences.getOpacity(context)) }
    var pillThemeName by remember { mutableStateOf(FloatingBubblePreferences.getPillTheme(context)) }
    var glowEnabled by remember { mutableStateOf(FloatingBubblePreferences.isGlowEnabled(context)) }
    var collapsedStyleName by remember { mutableStateOf(FloatingBubblePreferences.getCollapsedStyle(context)) }
    var followSystem by remember { mutableStateOf(FloatingBubblePreferences.isFollowSystem(context)) }
    var waveformStyleName by remember { mutableStateOf(FloatingBubblePreferences.getWaveformStyle(context)) }
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
            } else if (key == FloatingBubblePreferences.KEY_FOLLOW_SYSTEM) {
                followSystem = FloatingBubblePreferences.isFollowSystem(context)
            } else if (key == FloatingBubblePreferences.KEY_WAVEFORM_STYLE) {
                waveformStyleName = FloatingBubblePreferences.getWaveformStyle(context)
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
    // Day/night auto-switch (opt-in): when followSystem is on, the manual
    // prefs above are bypassed at read time for the fixed day/night pair.
    // The stored manual selections are never overwritten.
    val systemDark = isSystemInDarkTheme()
    val effectivePillName = remember(pillThemeName, followSystem, systemDark) {
        if (followSystem) FloatingBubblePreferences.getEffectivePillTheme(context, systemDark)
        else pillThemeName
    }
    val effectiveCollapsedName = remember(collapsedStyleName, followSystem, systemDark) {
        if (followSystem) FloatingBubblePreferences.getEffectiveCollapsedStyle(context, systemDark)
        else collapsedStyleName
    }
    val pillTheme = remember(effectivePillName) { PillTheme.forName(effectivePillName) }
    val collapsedTheme = remember(effectiveCollapsedName, effectivePillName) {
        when {
            effectiveCollapsedName == FloatingBubblePreferences.COLLAPSED_MINIMAL &&
                effectivePillName == FloatingBubblePreferences.PILL_THEME_LIGHT -> PillTheme.LIGHT
            effectiveCollapsedName == FloatingBubblePreferences.COLLAPSED_MINIMAL -> PillTheme.MONO
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
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopStart
    ) {
        Box(
            modifier = Modifier
                // Absolute card position inside the fixed full-screen window.
                // cardX/cardY and contentAlignment below come from state
                // updated synchronously on the main thread, so every frame
                // renders a complete, consistent geometry — the precondition
                // for the side-flip center flash no longer exists.
                .offset { IntOffset(cardX, cardY) }
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
                    val beatT = if (effectiveCollapsedName == FloatingBubblePreferences.COLLAPSED_MINIMAL) {
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
                        when (effectiveCollapsedName) {
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
                                    waveformStyle = waveformStyleName,
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
 * Pure, unit-tested geometry and modulation math shared by live [SiriWaveform]
 * and the frozen [BubbleSettingsScreen] preview so both render identical bar
 * proportions, inset, and full-width edge presence.
 */
internal object WaveformBarMath {
    const val MAX_BARS = 19
    const val MIN_BARS = 5
    const val DEFAULT_BARS = 13
    const val IDLE_PHASE = 0.6f

    private const val EDGE_FLOOR = 0.50f
    private const val EDGE_EXPONENT = 0.45f
    private const val LEVEL_GAMMA = 0.58f

    /** Horizontal inset inside the 24dp rounded capsule well so outer bars never clip. */
    fun horizontalInsetDp(widthDp: Float): Float {
        if (widthDp <= 0f) return 0f
        return min(10f, widthDp * 0.10f)
    }

    /** Symmetric odd bar count tuned for crisp spacing on mobile pill widths. */
    fun barCountForWidthDp(widthDp: Float): Int {
        if (widthDp <= 0f) return DEFAULT_BARS
        val usableDp = (widthDp - horizontalInsetDp(widthDp) * 2f).coerceAtLeast(0f)
        val raw = (usableDp / 7.5f).toInt().coerceIn(MIN_BARS, MAX_BARS)
        return if (raw % 2 == 0) (raw - 1).coerceAtLeast(MIN_BARS) else raw
    }

    /** Normalized horizontal coordinate [0..1] for bar [index] out of [barCount]. */
    fun barPositionFraction(index: Int, barCount: Int): Float {
        if (barCount <= 0) return 0.5f
        return (index + 0.5f) / barCount.toFloat()
    }

    /**
     * Raised-floor arch envelope: keeps a subtle center peak while giving
     * outermost bars >=50% height headroom so the entire row dances left-to-right.
     */
    fun edgeEnvelope(u: Float): Float {
        val clamped = u.coerceIn(0f, 1f)
        val arch = sin(clamped * PI.toFloat()).coerceIn(0f, 1f).pow(EDGE_EXPONENT)
        return EDGE_FLOOR + (1f - EDGE_FLOOR) * arch
    }

    /**
     * Three-harmonic raised-cosine spatial pattern spanning [0.18..1.0].
     * Smooth peaks and valleys (no sharp abs(sin) cusps) travel in both
     * directions so neighboring bars move independently across the full width.
     */
    fun barPattern(u: Float, phase: Float): Float {
        val twoPi = 2f * PI.toFloat()
        val w1 = 0.5f + 0.5f * sin(u * twoPi * 1.35f + phase * 1.45f)
        val w2 = 0.5f + 0.5f * sin(u * twoPi * 2.40f - phase * 1.10f + 1.1f)
        val w3 = 0.5f + 0.5f * sin(u * twoPi * 3.70f + phase * 0.85f + 2.3f)
        val combined = (w1 * 0.48f + w2 * 0.34f + w3 * 0.18f).coerceIn(0f, 1f)
        return 0.18f + 0.82f * combined.pow(1.35f)
    }

    /**
     * Target normalized half-height fraction [0.08..1.0] for one bar.
     * Scales linearly with the gamma-curved [rawLevel] (no level^2 collapse)
     * so conversational speech drives lively full-width motion.
     */
    fun barTargetFraction(u: Float, phase: Float, rawLevel: Float): Float {
        val shapedLevel = rawLevel.coerceIn(0f, 1f).pow(LEVEL_GAMMA)
        val env = edgeEnvelope(u)
        val pat = barPattern(u, phase)
        val idleFrac = (0.12f + 0.06f * pat) * env
        val activeFrac = shapedLevel * (0.14f + 0.86f * pat) * env
        return (idleFrac * (1f - shapedLevel * 0.60f) + activeFrac).coerceIn(0.08f, 1f)
    }

    /** Smoothstep ramp [0..1] preventing hard visual threshold pops. */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Frame-rate-independent exponential smoothing step (60/90/120Hz consistent). */
    fun smoothApproach(current: Float, target: Float, ratePerSec: Float, dtSec: Float): Float {
        if (dtSec <= 0f) return current
        val factor = 1f - exp(-ratePerSec * dtSec)
        return current + (target - current) * factor
    }
}

private class WaveformRenderState {
    var targetAmplitude: Float = 0f
    var smoothWaveAmplitude: Float = 0f
    var smoothBarsLevel: Float = 0f
    var phase: Float = WaveformBarMath.IDLE_PHASE
    var barCount: Int = WaveformBarMath.DEFAULT_BARS
    val barHeights: FloatArray = FloatArray(WaveformBarMath.MAX_BARS) { i ->
        val u = WaveformBarMath.barPositionFraction(i, WaveformBarMath.DEFAULT_BARS)
        WaveformBarMath.barTargetFraction(u, WaveformBarMath.IDLE_PHASE, 0f)
    }
}

private class WaveformDrawCache {
    var cachedWidth: Float = -1f
    var cachedPrimary: Color = Color.Unspecified
    var cachedForefront: Color = Color.Unspecified

    var waveForeBrush: Brush? = null
    var waveBgBrush: Brush? = null
    var barBedBrush: Brush? = null
    var barForeBrush: Brush? = null
    var barGlowBrush: Brush? = null

    val path1: Path = Path()
    val path2: Path = Path()
    val path3: Path = Path()

    fun ensureBrushes(width: Float, primaryColor: Color, forefrontColor: Color) {
        if (cachedWidth == width &&
            cachedPrimary == primaryColor &&
            cachedForefront == forefrontColor &&
            waveForeBrush != null
        ) {
            return
        }
        cachedWidth = width
        cachedPrimary = primaryColor
        cachedForefront = forefrontColor

        val sameTone = primaryColor == forefrontColor
        val bedEdgeAlpha = if (sameTone) 0.18f else 0.30f
        val bedCenterAlpha = if (sameTone) 0.26f else 0.44f
        val foreEdgeAlpha = if (sameTone) 0.82f else 0.78f
        val glowEdgeAlpha = if (sameTone) 0.08f else 0.14f
        val glowCenterAlpha = if (sameTone) 0.14f else 0.22f

        waveForeBrush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, forefrontColor.copy(alpha = 0.9f), Color.Transparent),
            startX = 0f,
            endX = width
        )
        waveBgBrush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, primaryColor.copy(alpha = 0.4f), Color.Transparent),
            startX = 0f,
            endX = width
        )
        barBedBrush = Brush.horizontalGradient(
            colors = listOf(
                primaryColor.copy(alpha = bedEdgeAlpha),
                primaryColor.copy(alpha = bedCenterAlpha),
                primaryColor.copy(alpha = bedEdgeAlpha)
            ),
            startX = 0f,
            endX = width
        )
        barForeBrush = Brush.horizontalGradient(
            colors = listOf(
                forefrontColor.copy(alpha = foreEdgeAlpha),
                forefrontColor.copy(alpha = 0.96f),
                forefrontColor.copy(alpha = foreEdgeAlpha)
            ),
            startX = 0f,
            endX = width
        )
        barGlowBrush = Brush.horizontalGradient(
            colors = listOf(
                forefrontColor.copy(alpha = glowEdgeAlpha),
                forefrontColor.copy(alpha = glowCenterAlpha),
                forefrontColor.copy(alpha = glowEdgeAlpha)
            ),
            startX = 0f,
            endX = width
        )
    }
}

@Suppress("DEPRECATION")
private fun buildSmoothWavePath(
    path: Path,
    width: Float,
    centerY: Float,
    stepPx: Float,
    sampleY: (Float) -> Float
) {
    path.reset()
    path.moveTo(0f, centerY)
    var prevX = 0f
    var prevY = centerY
    var x = stepPx
    while (x < width) {
        val y = sampleY(x)
        val midX = (prevX + x) * 0.5f
        val midY = (prevY + y) * 0.5f
        path.quadraticBezierTo(prevX, prevY, midX, midY)
        prevX = x
        prevY = y
        x += stepPx
    }
    val endY = sampleY(width)
    path.quadraticBezierTo(prevX, prevY, width, endY)
}

/**
 * Multi-layered animated waveform visualizer (Windows Floating Bubble parity).
 * Style pick rides FloatingBubblePreferences.WAVEFORM_STYLE; colors stay
 * mode-owned (amethyst transcription / teal agent) in every style.
 *
 * Frame updates run in a single vsync loop and invalidate only the Canvas
 * draw phase (zero Compose recompositions per second while recording).
 */
@Composable
fun SiriWaveform(
    theme: PillTheme,
    agentMode: Boolean,
    waveformStyle: String = FloatingBubblePreferences.WAVEFORM_WAVES,
) {
    val reducedMotion = rememberReducedMotion()
    val density = LocalDensity.current

    // Mode pairs from the preset — amethyst-family wave = transcription, teal
    // wave = agent. This fixed-per-mode mapping is how the modes stay
    // distinguishable while everything else follows the preset.
    val primaryColor = if (agentMode) theme.waveA else theme.waveT
    val forefrontColor = if (agentMode) theme.waveAFore else theme.waveTFore

    val renderState = remember { WaveformRenderState() }
    val drawCache = remember { WaveformDrawCache() }
    val frameTick = remember { mutableLongStateOf(0L) }

    val currentReducedMotion by rememberUpdatedState(reducedMotion)
    val currentWaveformStyle by rememberUpdatedState(waveformStyle)

    // Collect live mic amplitude directly into renderState without triggering
    // Compose recomposition on every 50ms audio poll.
    LaunchedEffect(Unit) {
        BubbleController.amplitude.collect { raw ->
            renderState.targetAmplitude = (raw * 6f).coerceIn(0f, 1f)
        }
    }

    // Single frame-rate-independent animation loop (60/90/120Hz consistent).
    LaunchedEffect(Unit) {
        var lastTime = withFrameNanos { it }
        while (isActive) {
            val now = withFrameNanos { it }
            val dt = ((now - lastTime) / 1e9f).coerceIn(0f, 0.05f)
            lastTime = now

            val target = renderState.targetAmplitude
            val reduced = currentReducedMotion

            if (reduced) {
                renderState.smoothWaveAmplitude = target
                renderState.smoothBarsLevel = target
                renderState.phase = WaveformBarMath.IDLE_PHASE
            } else {
                // Fast attack, smooth release for both styles (no bouncy pump on bars).
                val waveRate = if (target > renderState.smoothWaveAmplitude) 16f else 7.5f
                renderState.smoothWaveAmplitude = WaveformBarMath.smoothApproach(
                    renderState.smoothWaveAmplitude, target, waveRate, dt
                )
                val barsRate = if (target > renderState.smoothBarsLevel) 18f else 7f
                renderState.smoothBarsLevel = WaveformBarMath.smoothApproach(
                    renderState.smoothBarsLevel, target, barsRate, dt
                )
                val driver = if (currentWaveformStyle == FloatingBubblePreferences.WAVEFORM_BARS) {
                    renderState.smoothBarsLevel
                } else {
                    renderState.smoothWaveAmplitude
                }
                val speed = 0.85f + driver * 3.6f
                renderState.phase = (renderState.phase + speed * dt * 2f * PI.toFloat()) % (1000f * PI.toFloat())
            }

            if (currentWaveformStyle == FloatingBubblePreferences.WAVEFORM_BARS) {
                val count = renderState.barCount.coerceIn(WaveformBarMath.MIN_BARS, WaveformBarMath.MAX_BARS)
                for (i in 0 until count) {
                    val u = WaveformBarMath.barPositionFraction(i, count)
                    val targetBar = WaveformBarMath.barTargetFraction(
                        u = u,
                        phase = renderState.phase,
                        rawLevel = renderState.smoothBarsLevel
                    )
                    renderState.barHeights[i] = if (reduced) {
                        targetBar
                    } else {
                        val barRate = if (targetBar > renderState.barHeights[i]) 24f else 9.5f
                        WaveformBarMath.smoothApproach(renderState.barHeights[i], targetBar, barRate, dt)
                    }
                }
            }

            frameTick.longValue = now
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { intSize ->
                if (intSize.width > 0) {
                    val widthDp = with(density) { intSize.width.toDp().value }
                    renderState.barCount = WaveformBarMath.barCountForWidthDp(widthDp)
                }
            }
    ) {
        // Read frameTick inside DrawScope only -> invalidates draw phase with 0 recompositions.
        @Suppress("UNUSED_VARIABLE")
        val tick = frameTick.longValue

        val width = size.width
        val height = size.height
        if (width < 4f || height < 4f) return@Canvas
        val centerY = height * 0.5f

        drawCache.ensureBrushes(width, primaryColor, forefrontColor)

        if (waveformStyle == FloatingBubblePreferences.WAVEFORM_BARS) {
            val widthDp = width / 1.dp.toPx()
            val insetPx = WaveformBarMath.horizontalInsetDp(widthDp) * 1.dp.toPx()
            val usableWidth = (width - insetPx * 2f).coerceAtLeast(1f)
            val count = renderState.barCount.coerceIn(WaveformBarMath.MIN_BARS, WaveformBarMath.MAX_BARS)
            val maxHalfH = height * 0.43f
            val minHalfH = 1.6.dp.toPx()
            val barStroke = 2.6.dp.toPx()
            val bedStroke = 3.0.dp.toPx()
            val glowStroke = barStroke * 1.95f
            val bloomAlpha = WaveformBarMath.smoothstep(0.14f, 0.72f, renderState.smoothBarsLevel)

            val bedBrush = drawCache.barBedBrush ?: return@Canvas
            val glowBrush = drawCache.barGlowBrush ?: return@Canvas
            val foreBrush = drawCache.barForeBrush ?: return@Canvas

            // Pass 1: subtle primary bed behind each bar (keeps dual-tone mode identity).
            for (i in 0 until count) {
                val u = WaveformBarMath.barPositionFraction(i, count)
                val x = insetPx + u * usableWidth
                val halfH = max(minHalfH, (renderState.barHeights[i] * maxHalfH * 1.05f).coerceAtMost(height * 0.46f))
                drawLine(
                    brush = bedBrush,
                    start = Offset(x, centerY - halfH),
                    end = Offset(x, centerY + halfH),
                    strokeWidth = bedStroke,
                    cap = StrokeCap.Round
                )
            }

            // Pass 2: soft halo ramped via smoothstep (zero threshold pop, zero neighbor overlap).
            if (bloomAlpha > 0.01f) {
                for (i in 0 until count) {
                    val u = WaveformBarMath.barPositionFraction(i, count)
                    val x = insetPx + u * usableWidth
                    val halfH = max(minHalfH, renderState.barHeights[i] * maxHalfH)
                    drawLine(
                        brush = glowBrush,
                        start = Offset(x, centerY - halfH),
                        end = Offset(x, centerY + halfH),
                        strokeWidth = glowStroke,
                        cap = StrokeCap.Round,
                        alpha = bloomAlpha
                    )
                }
            }

            // Pass 3: crisp rounded foreground bars across full pill width.
            for (i in 0 until count) {
                val u = WaveformBarMath.barPositionFraction(i, count)
                val x = insetPx + u * usableWidth
                val halfH = max(minHalfH, renderState.barHeights[i] * maxHalfH)
                drawLine(
                    brush = foreBrush,
                    start = Offset(x, centerY - halfH),
                    end = Offset(x, centerY + halfH),
                    strokeWidth = barStroke,
                    cap = StrokeCap.Round
                )
            }
            return@Canvas
        }

        // Waves mode: exact 3-layer mode-colored waves upgraded to smooth quadratic curves
        // with zero per-frame Path or Brush allocations.
        val smoothedAmplitude = renderState.smoothWaveAmplitude
        val phase1 = renderState.phase
        val phase2 = -phase1 * 0.7f
        val activeAmplitude = (smoothedAmplitude * 0.8f + 0.1f) * (height * 0.45f)
        val vibGain = WaveformBarMath.smoothstep(0.03f, 0.18f, smoothedAmplitude) * smoothedAmplitude
        val stepPx = max(3f, 2.dp.toPx())
        val dpPx = 1.dp.toPx()

        val bgBrush = drawCache.waveBgBrush ?: return@Canvas
        val foreBrush = drawCache.waveForeBrush ?: return@Canvas

        buildSmoothWavePath(drawCache.path1, width, centerY, stepPx) { xVal ->
            val envelope = sin((xVal / width) * PI.toFloat())
            val angle = (xVal / width) * 2f * PI.toFloat() * 1.5f + phase1
            val vibration = sin((xVal / dpPx) * 0.25f + phase1 * 3f) * vibGain * 3.5f * dpPx
            centerY + (sin(angle) * activeAmplitude * 0.5f + vibration) * envelope
        }
        drawPath(path = drawCache.path1, brush = bgBrush, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))

        buildSmoothWavePath(drawCache.path2, width, centerY, stepPx) { xVal ->
            val envelope = sin((xVal / width) * PI.toFloat())
            val angle = (xVal / width) * 2f * PI.toFloat() * 2.5f + phase2
            val vibration = sin((xVal / dpPx) * 0.35f - phase2 * 4f) * vibGain * 2.6f * dpPx
            centerY + (sin(angle) * activeAmplitude * 0.7f + vibration) * envelope
        }
        drawPath(path = drawCache.path2, brush = bgBrush, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))

        buildSmoothWavePath(drawCache.path3, width, centerY, stepPx) { xVal ->
            val envelope = sin((xVal / width) * PI.toFloat())
            val angle = (xVal / width) * 2f * PI.toFloat() * 1.2f + (phase1 - phase2) * 0.5f
            val vibration = sin((xVal / dpPx) * 0.20f + phase1 * 5f) * vibGain * 4.2f * dpPx
            centerY + (sin(angle) * activeAmplitude * 0.9f + vibration) * envelope
        }
        drawPath(path = drawCache.path3, brush = foreBrush, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}
