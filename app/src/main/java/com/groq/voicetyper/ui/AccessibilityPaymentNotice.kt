package com.groq.voicetyper.ui

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.FeedbackBus
import com.groq.voicetyper.SettingsJointCard
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.PrecisionTheme

/**
 * Honest, persistent notice for the AccessibilityService trade-off.
 *
 * Payment apps enforce their own block whenever a third-party accessibility
 * service is enabled. That block is app-level detection in the payment app,
 * not a Fluence policy: privacy exclusions cannot change it, and Android
 * offers no programmatic per-app or self toggle. The action therefore opens
 * the system accessibility settings, where the user keeps full control.
 */
@Composable
fun AccessibilityPaymentNotice(
    body: String,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current

    SettingsJointCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(FluenceSpacing.Md),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
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
                TextButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }.onFailure {
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
        }
    }
}
