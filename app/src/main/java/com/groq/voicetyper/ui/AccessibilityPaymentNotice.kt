package com.groq.voicetyper.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.groq.voicetyper.FeedbackBus
import com.groq.voicetyper.FluenceAccessibilityService
import com.groq.voicetyper.SettingsJointCard
import com.groq.voicetyper.isAccessibilityServiceEnabled
import com.groq.voicetyper.quickcontrols.FluenceQuickControls
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.PrecisionTheme
import com.groq.voicetyper.ui.icons.FluenceIcons

/**
 * Honest, persistent notice for the AccessibilityService trade-off.
 *
 * Payment apps enforce their own block whenever a third-party accessibility
 * service is enabled. When active, users can pause the service in one tap
 * (via disableSelf) before opening a payment app, add the Quick Settings tile
 * on Android 13+, or jump directly to system accessibility settings to turn
 * it back on.
 */
@Composable
fun AccessibilityPaymentNotice(
    body: String,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var accessibilityEnabled by remember {
        mutableStateOf(
            isAccessibilityServiceEnabled(context, FluenceAccessibilityService::class.java)
        )
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = isAccessibilityServiceEnabled(
                    context,
                    FluenceAccessibilityService::class.java
                )
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsJointCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(FluenceSpacing.Md),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = FluenceIcons.Info,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(FluenceSpacing.Sm))
            Column {
                Text(
                    text = "Payment apps and accessibility",
                    color = colors.textPrimary,
                    style = FluenceTypography.titleSmall
                )
                Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                Text(
                    text = body,
                    color = colors.textSecondary,
                    style = FluenceTypography.bodySmall
                )
                if (accessibilityEnabled) {
                    TextButton(
                        onClick = {
                            when (FluenceQuickControls.pauseBubbleForPayments(context)) {
                                FluenceQuickControls.PauseBubbleOutcome.PAUSED_PROGRAMMATICALLY -> {
                                    accessibilityEnabled = false
                                    FeedbackBus.show(
                                        "Fluence Bubble paused. Voice Keyboard still works.",
                                        long = true
                                    )
                                }

                                FluenceQuickControls.PauseBubbleOutcome.OPENED_SETTINGS_FALLBACK -> {
                                    FeedbackBus.show(
                                        "Turn off Fluence On-Screen Dictation before paying",
                                        long = true
                                    )
                                }

                                FluenceQuickControls.PauseBubbleOutcome.ALREADY_PAUSED -> {
                                    accessibilityEnabled = false
                                }
                            }
                        },
                        contentPadding = PaddingValues(
                            start = 0.dp,
                            end = FluenceSpacing.Md,
                            top = 0.dp,
                            bottom = 0.dp
                        ),
                        modifier = Modifier.heightIn(min = FluenceSpacing.Xxl)
                    ) {
                        Text(
                            text = "Pause Bubble now",
                            color = colors.textLink,
                            style = FluenceTypography.labelMedium
                        )
                    }
                } else {
                    TextButton(
                        onClick = {
                            if (!FluenceQuickControls.openAccessibilitySettings(context)) {
                                FeedbackBus.show("Could not open accessibility settings")
                            }
                        },
                        contentPadding = PaddingValues(
                            start = 0.dp,
                            end = FluenceSpacing.Md,
                            top = 0.dp,
                            bottom = 0.dp
                        ),
                        modifier = Modifier.heightIn(min = FluenceSpacing.Xxl)
                    ) {
                        Text(
                            text = "Open accessibility settings",
                            color = colors.textLink,
                            style = FluenceTypography.labelMedium
                        )
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    TextButton(
                        onClick = {
                            FluenceQuickControls.requestAddBubbleQuickSettingsTile(context) { msg ->
                                FeedbackBus.show(msg)
                            }
                        },
                        contentPadding = PaddingValues(
                            start = 0.dp,
                            end = FluenceSpacing.Md,
                            top = 0.dp,
                            bottom = 0.dp
                        ),
                        modifier = Modifier.heightIn(min = FluenceSpacing.Xxl)
                    ) {
                        Text(
                            text = "Add Quick Settings tile",
                            color = colors.textSecondary,
                            style = FluenceTypography.labelMedium
                        )
                    }
                }
            }
        }
    }
}
