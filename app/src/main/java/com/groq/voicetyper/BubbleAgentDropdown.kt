package com.groq.voicetyper

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.ui.icons.FluenceIcons

/**
 * One-turn agent picker as an independent overlay window. It renders as a
 * small chevron strip flush to the pill edge, pill-width so it reads as part
 * of the pill. Tapping the strip expands or collapses the agent card; picks
 * can change any number of times before the tick confirms. Untouched, the
 * settings default applies. The pill layout, animations, and gestures are
 * never touched. Above or below placement follows the screen half, x is
 * clamped, so corners resolve with no per-corner code.
 */
data class DropdownAgent(
    val id: String,
    val name: String,
    val subtitle: String
)

@Composable
fun AgentDropdownOverlay(
    openBelow: Boolean,
    agents: List<DropdownAgent>,
    initialActiveId: String,
    defaultId: String,
    pillTheme: PillTheme,
    /** Bumped by the service on outside tap: fold back to strip. */
    collapse: androidx.compose.runtime.MutableState<Int>,
    /** False when system animations are off: the card snaps instead. */
    animated: Boolean,
    onExpandedChange: (Boolean) -> Unit = {},
    onPick: (String) -> Unit
) {
    // Card grows out of the strip edge it hangs from: downward when the
    // strip sits below the pill, upward when above. Same 250ms curve as
    // the pill morph, so it reads as one motion. Pill itself untouched.
    val expandFrom = if (openBelow) Alignment.Top else Alignment.Bottom
    val cardEnter = if (animated) {
        expandVertically(
            animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
            expandFrom = expandFrom
        ) + fadeIn(animationSpec = tween(durationMillis = 200))
    } else {
        expandVertically(animationSpec = snap(), expandFrom = expandFrom)
    }
    val cardExit = if (animated) {
        shrinkVertically(
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            shrinkTowards = expandFrom
        ) + fadeOut(animationSpec = tween(durationMillis = 150))
    } else {
        shrinkVertically(animationSpec = snap(), shrinkTowards = expandFrom)
    }
    // Wears the expanded pill's own theme in its agent accent: shell base,
    // teal border and check, fore colors for text. No app-theme tokens here
    // so the strip and card always match the pill beside them.
    val cardBg = pillTheme.shellBase
    val cardBorder = pillTheme.waveA.copy(alpha = 0.45f)
    val titleColor = pillTheme.waveAFore
    val bodyColor = pillTheme.waveAFore.copy(alpha = 0.72f)
    val faintColor = pillTheme.waveAFore.copy(alpha = 0.55f)
    val dividerColor = pillTheme.waveWellBorder
    val checkColor = pillTheme.waveA
    // Entrance mask: the window is added as recording starts, the same
    // moment the pill begins its own 250ms morph. Fading in over the same
    // duration reads as one motion without touching the pill animation.
    var mounted by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { mounted = true }
    val mountAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (mounted) 1f else 0f,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "dropdownMount"
    )
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(collapse.value) {
        if (collapse.value > 0 && expanded) {
            expanded = false
            onExpandedChange(false)
        }
    }
    var currentActive by remember { mutableStateOf(initialActiveId) }
    val activeName = agents.firstOrNull { it.id == currentActive }?.name ?: "Agent"
    val stripSource = remember { MutableInteractionSource() }

    // Fixed frame matching the window: exactly one animation, the card's
    // own expand and shrink, ever changes pixels. The previous root size
    // snap clipped the card mid-motion and differed per state, which read
    // as inconsistent animation. The service sizes the window around this
    // frame, so nothing here fights it.
    Box(
        contentAlignment = if (openBelow) Alignment.TopStart else Alignment.BottomStart,
        modifier = Modifier
            .size(width = 240.dp, height = 350.dp)
            .graphicsLayer { alpha = mountAlpha }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Collapse agent picker",
                role = Role.Button,
                onClick = {
                    if (expanded) {
                        expanded = false
                        onExpandedChange(false)
                    }
                }
            )
    ) {
    Column(
        modifier = Modifier.width(240.dp)
    ) {
            AnimatedVisibility(
                visible = expanded && !openBelow,
                enter = cardEnter,
                exit = cardExit
            ) {
                Column {
                    AgentCard(
                        agents = agents,
                        currentActive = currentActive,
                        defaultId = defaultId,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        titleColor = titleColor,
                        bodyColor = bodyColor,
                        faintColor = faintColor,
                        dividerColor = dividerColor,
                        checkColor = checkColor,
                        onPick = {
                            currentActive = it
                            onPick(it)
                        }
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
            // Chevron strip: pill width, flush to the pill edge.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .semantics {
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }
                    .background(cardBg, FluenceShapes.Medium)
                    .border(1.dp, cardBorder, FluenceShapes.Medium)
                    .clickable(
                        interactionSource = stripSource,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onClickLabel = if (expanded) "Collapse agent picker" else "Expand agent picker",
                        role = Role.Button,
                        onClick = {
                            val next = !expanded
                            expanded = next
                            onExpandedChange(next)
                        }
                    )
                    .padding(horizontal = FluenceSpacing.Base),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (expanded) {
                        if (openBelow) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown
                    } else {
                        if (openBelow) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp
                    },
                    contentDescription = null,
                    tint = checkColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                Text(
                    text = "Change agent",
                    color = titleColor,
                    style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
                Text(
                    text = activeName,
                    color = bodyColor,
                    style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.Normal),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            AnimatedVisibility(
                visible = expanded && openBelow,
                enter = cardEnter,
                exit = cardExit
            ) {
                Column {
                    Spacer(modifier = Modifier.height(6.dp))
                    AgentCard(
                        agents = agents,
                        currentActive = currentActive,
                        defaultId = defaultId,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        titleColor = titleColor,
                        bodyColor = bodyColor,
                        faintColor = faintColor,
                        dividerColor = dividerColor,
                        checkColor = checkColor,
                        onPick = {
                            currentActive = it
                            onPick(it)
                        }
                    )
                }
            }
        }
    }
    }

@Composable
private fun AgentCard(
    agents: List<DropdownAgent>,
    currentActive: String,
    defaultId: String,
    cardBg: androidx.compose.ui.graphics.Color,
    cardBorder: androidx.compose.ui.graphics.Color,
    titleColor: androidx.compose.ui.graphics.Color,
    bodyColor: androidx.compose.ui.graphics.Color,
    faintColor: androidx.compose.ui.graphics.Color,
    dividerColor: androidx.compose.ui.graphics.Color,
    checkColor: androidx.compose.ui.graphics.Color,
    onPick: (String) -> Unit
) {
    Surface(
        color = cardBg,
        shape = FluenceShapes.Medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorder),
        // No elevation shadow: an animating shadow forces offscreen render
        // passes every frame. The border alone defines the edge.
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            // About five rows visible, then the list scrolls inside the
            // card so a long agent collection never clutters the screen.
            .heightIn(max = 300.dp)
    ) {
        Column {
            Text(
                text = "AGENT FOR THIS TURN",
                color = faintColor,
                style = FluenceTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(
                    horizontal = FluenceSpacing.Base,
                    vertical = FluenceSpacing.Sm
                )
            )
            HorizontalDivider(color = dividerColor, thickness = 1.dp)
            LazyColumn {
                items(agents, key = { it.id }) { agent ->
                    val rowSource = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = rowSource,
                                indication = androidx.compose.foundation.LocalIndication.current,
                                onClickLabel = "Use ${agent.name} for this turn",
                                role = Role.Button,
                                onClick = { onPick(agent.id) }
                            )
                            .padding(horizontal = FluenceSpacing.Base, vertical = 12.dp)
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = agent.name,
                                color = titleColor,
                                style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = agent.subtitle,
                                color = bodyColor,
                                style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.Normal),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (agent.id == defaultId) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Default",
                                    color = faintColor,
                                    style = FluenceTypography.labelSmall
                                )
                            }
                        }
                        if (agent.id == currentActive) {
                            Icon(
                                imageVector = FluenceIcons.Check,
                                contentDescription = null,
                                tint = checkColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    HorizontalDivider(
                        color = dividerColor,
                        thickness = 1.dp,
                        modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
                    )
                }
            }
        }
    }
}
