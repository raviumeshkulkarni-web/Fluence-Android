package com.groq.voicetyper.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.R

/**
 * Fluence product lockup (Application Identity surface).
 *
 * [Orb] fluence   <Product>
 *
 * Horizontal lockup. The orb is the standard Fluence concentric-ring mark.
 * The product name ("Transcribe" / "Capture") is brand artwork rendered in Allura.
 * Proportions mirror the Windows desktop lockup exactly:
 *   - wordmark "fluence": weight 700, letter-spacing -0.03em
 *   - "ence" in functional cyan (teal #0E7490 in white mode, Windows parity)
 *   - product name: Allura, weight 400, ≈91% of wordmark size, gap ≈30% of
 *     product size, color = textSecondary (Web #A0A0A0 dark / #52525B white).
 * The orb artwork stays frozen in both modes (Windows keeps the logo-mark
 * gradient stops untouched in white mode). Only the three text runs follow
 * the theme — sizes, weights, and spacing never change.
 * Do not use this for Feature Identity surfaces — those use the master orb only.
 */
@Composable
fun FluenceProductLockup(
    productName: String,
    modifier: Modifier = Modifier,
    orbSize: Dp = 32.dp,
    wordmarkSize: TextUnit = 20.sp,
) {
    val productSize = (wordmarkSize.value * 0.91).sp
    val productGap = (productSize.value * 0.30).dp
    val colors = PrecisionTheme.colors
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(id = R.drawable.ic_fluence_logo),
            contentDescription = null,
            modifier = Modifier.width(orbSize).height(orbSize),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "flu",
            color = colors.textPrimary,
            fontSize = wordmarkSize,
            fontWeight = FontWeight.Bold,
            fontFamily = SoraFont,
            letterSpacing = (-0.03 * wordmarkSize.value).sp,
        )
        Text(
            text = "ence",
            color = colors.brandCyan,
            fontSize = wordmarkSize,
            fontWeight = FontWeight.Bold,
            fontFamily = SoraFont,
            letterSpacing = (-0.03 * wordmarkSize.value).sp,
        )
        Spacer(modifier = Modifier.width(productGap))
        Text(
            text = productName,
            color = colors.textSecondary,
            fontSize = productSize,
            fontWeight = FontWeight.Normal,
            fontFamily = AlluraFont,
            lineHeight = TextUnit.Unspecified,
            modifier = Modifier.offset(y = (productSize.value * 0.08).dp),
        )
    }
}
