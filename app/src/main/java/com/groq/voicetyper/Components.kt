package com.groq.voicetyper

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.theme.FluenceMotion

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
