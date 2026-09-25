package com.groq.voicetyper

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.groq.voicetyper.theme.FluenceMotion
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.GeistMonoFont
import com.groq.voicetyper.theme.LocalMotionPreferences
import com.groq.voicetyper.theme.PrecisionTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// ── Press Scale Animation ───────────────────────────────────────────────────
// Borrowed from Fluence Capture — provides tactile press feedback.
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    // Reduced motion: state change still applies (feedback preserved) but it
    // snaps instead of animating.
    val reducedMotion = LocalMotionPreferences.current.reducedMotion
    val pressSpec: AnimationSpec<Float> =
        if (reducedMotion) snap() else tween(
            durationMillis = FluenceMotion.durationImmediate,
            easing = FastOutSlowInEasing
        )
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = pressSpec,
        label = "press_scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = pressSpec,
        label = "press_alpha"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
        this.alpha = alpha
    }
}

// ── Provider Logo ────────────────────────────────────────────────────────────
@Composable
fun ProviderLogo(
    providerId: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp
) {
    val colors = PrecisionTheme.colors
    val drawableRes = when (providerId) {
        "openai"    -> R.drawable.ic_provider_openai
        "anthropic" -> R.drawable.ic_provider_anthropic
        "google"    -> R.drawable.ic_provider_google
        "groq"      -> R.drawable.ic_provider_groq
        "mistral"   -> R.drawable.ic_provider_mistral
        else        -> null
    }
    if (drawableRes != null) {
        Image(
            painter = painterResource(drawableRes),
            contentDescription = null,
            modifier = modifier.size(size)
        )
    }
}

// ── Settings Top Bar ─────────────────────────────────────────────────────────
// Shared header for settings sub-screens — single source of truth for the
// back affordance (48dp touch target, compact 20dp icon visual) and title
// typography.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val backInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            interactionSource = backInteraction,
            modifier = Modifier
                .size(48.dp)
                .pressScale(backInteraction)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = colors.textPrimary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(FluenceSpacing.Base))

        Text(
            text = title,
            color = colors.textPrimary,
            style = FluenceTypography.headlineLarge
        )
    }
}

// ── Empty State ──────────────────────────────────────────────────────────────
// Shared empty-state block: circular icon badge, prominent title, supportive
// description, and an optional monochrome primary action. Keeps every empty
// screen visually consistent and inviting rather than a dead-end.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun FluenceEmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = PrecisionTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Lg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(colors.panelElevated)
                .border(1.dp, colors.outlineSubtle, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(modifier = Modifier.height(FluenceSpacing.Base))
        Text(
            text = title,
            color = colors.textPrimary,
            style = FluenceTypography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
        Text(
            text = description,
            color = colors.textSecondary,
            style = FluenceTypography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(FluenceSpacing.Lg))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                        containerColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                        contentColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.canvas
                ),
                shape = FluenceShapes.Small
            ) {
                Text(
                    text = actionLabel,
                    style = FluenceTypography.labelLarge
                )
            }
        }
    }
}

// ── Section header ────────────────────────────────────────────────────────
// Shared card-section header: mono label + optional trailing text action.
// Same language as the History "Recent Transcriptions" header.
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun FluenceSectionHeader(
    label: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = FluenceSpacing.Base, end = FluenceSpacing.Sm, top = FluenceSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = colors.textTertiary,
            style = FluenceTypography.labelSmall.copy(
                fontFamily = GeistMonoFont,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            ),
            modifier = Modifier.weight(1f)
        )
        if (actionLabel != null && onAction != null) {
            // shadcn-style outline button: bordered pill on panel so the add
            // action reads as a button, not as plain header text. Tokens only,
            // no new values introduced.
            val actionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .pressScale(actionSource)
                    .clip(FluenceShapes.Small)
                    .background(colors.panel)
                    .border(1.dp, colors.outlineSubtle, FluenceShapes.Small)
                    .clickable(
                        interactionSource = actionSource,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onClickLabel = actionLabel,
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClick = onAction
                    )
                    .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Xs),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    actionLabel,
                    color = colors.textPrimary,
                    style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

// ── Settings Section Header ───────────────────────────────────────────────
// Section header rendered OUTSIDE cards, matching the official Windows /
// Codex design system. High-contrast, clear hierarchy, with optional
// right-aligned action button.
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsSectionHeader(
    title: String,
    description: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.textPrimary,
                style = FluenceTypography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    letterSpacing = (-0.2).sp
                )
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    color = colors.textSecondary,
                    style = FluenceTypography.bodySmall.copy(
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp
                    )
                )
            }
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.width(FluenceSpacing.Md))
            val actionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .pressScale(actionSource)
                    .clip(FluenceShapes.Small)
                    .background(colors.panel)
                    .border(1.dp, colors.outlineSubtle, FluenceShapes.Small)
                    .clickable(
                        interactionSource = actionSource,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        onClickLabel = actionLabel,
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClick = onAction
                    )
                    .padding(horizontal = FluenceSpacing.Md, vertical = FluenceSpacing.Sm),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = actionLabel,
                    color = colors.textPrimary,
                    style = FluenceTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

// ── Settings Joint Card ──────────────────────────────────────────────────
// Container card matching the Android v1.28.0 settings-card surface architecture:
// rounded corners (12dp), colors.panel (#1E1E1E) background, colors.outlineSubtle
// 1dp stroke, and clearly visible dividers separating joined rows inside.
// Decoupled from CardSurface (#141414) so the dashboard stays strictly intact.
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsJointCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = PrecisionTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(FluenceShapes.Medium)
            .background(colors.panel)
            .border(1.dp, colors.outlineSubtle, FluenceShapes.Medium),
        content = content
    )
}

// ── Settings Divider ─────────────────────────────────────────────────────
// Full-width internal divider for SettingsJointCard with calibrated contrast
// (colors.divider) so row separators are clearly visible across displays.
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    androidx.compose.material3.HorizontalDivider(
        color = PrecisionTheme.colors.divider,
        thickness = 1.dp,
        modifier = modifier
    )
}

// ── Transient feedback (Material Snackbar) ────────────────────────────────
// System Toasts are banned: transient feedback goes through this bus so it
// renders as one Fluence-styled Material Snackbar — queued, inset-aware —
// from any screen, dialog, or the host Activity. No context, no scope.
// ────────────────────────────────────────────────────────────────────────────
data class FeedbackMessage(val text: String, val long: Boolean)

object FeedbackBus {
    private val _messages = MutableSharedFlow<FeedbackMessage>(extraBufferCapacity = 64)
    val messages: SharedFlow<FeedbackMessage> = _messages.asSharedFlow()

    fun show(message: String, long: Boolean = false) {
        _messages.tryEmit(FeedbackMessage(message, long))
    }
}

@Composable
fun FluenceFeedbackHost(modifier: Modifier = Modifier) {
    val colors = PrecisionTheme.colors
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        FeedbackBus.messages.collect { msg ->
            hostState.showSnackbar(
                message = msg.text,
                duration = if (msg.long) SnackbarDuration.Long else SnackbarDuration.Short
            )
        }
    }
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            shape = FluenceShapes.Small,
            containerColor = colors.dialog,
            contentColor = colors.textPrimary
        )
    }
}
