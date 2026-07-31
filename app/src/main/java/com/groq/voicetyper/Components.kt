package com.groq.voicetyper

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.groq.voicetyper.theme.Canvas
import com.groq.voicetyper.theme.FluenceMotion
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.OutlineSubtle
import com.groq.voicetyper.theme.PanelElevated
import com.groq.voicetyper.theme.TextPrimary
import com.groq.voicetyper.theme.TextSecondary

// ── Press Scale Animation ───────────────────────────────────────────────────
// Borrowed from Fluence Capture — provides tactile press feedback.
// ────────────────────────────────────────────────────────────────────────────
@Composable
fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = tween(
            durationMillis = FluenceMotion.durationImmediate,
            easing = FastOutSlowInEasing
        ),
        label = "press_scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = tween(
            durationMillis = FluenceMotion.durationImmediate,
            easing = FastOutSlowInEasing
        ),
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
// back affordance (48dp touch target) and screen title typography.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun SettingsTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FluenceSpacing.Md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .size(FluenceSpacing.Xxl)
                .pressScale(remember { MutableInteractionSource() })
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = TextPrimary,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(FluenceSpacing.Base))

        Text(
            text = title,
            color = TextPrimary,
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
                .background(PanelElevated)
                .border(1.dp, OutlineSubtle, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(modifier = Modifier.height(FluenceSpacing.Base))
        Text(
            text = title,
            color = TextPrimary,
            style = FluenceTypography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
        Text(
            text = description,
            color = TextSecondary,
            style = FluenceTypography.bodyMedium,
            textAlign = TextAlign.Center
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(FluenceSpacing.Lg))
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TextPrimary,
                    contentColor = Canvas
                ),
                shape = FluenceShapes.Small
            ) {
                Text(
                    text = actionLabel,
                    style = FluenceTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}
