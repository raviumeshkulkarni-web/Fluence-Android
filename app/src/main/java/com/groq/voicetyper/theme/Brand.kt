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
 *   - "ence" in brand cyan #0BD6E3 (BrandCyan)
 *   - product name: Allura, weight 400, ≈91% of wordmark size, gap ≈30% of
 *     product size, color = TextSecondary (matches Web text-secondary #A0A0A0).
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
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(id = R.drawable.ic_fluence_logo),
            contentDescription = null,
            modifier = Modifier.width(orbSize).height(orbSize),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "flu",
            color = TextPrimary,
            fontSize = wordmarkSize,
            fontWeight = FontWeight.Bold,
            fontFamily = SoraFont,
            letterSpacing = (-0.03 * wordmarkSize.value).sp,
        )
        Text(
            text = "ence",
            color = BrandCyan,
            fontSize = wordmarkSize,
            fontWeight = FontWeight.Bold,
            fontFamily = SoraFont,
            letterSpacing = (-0.03 * wordmarkSize.value).sp,
        )
        Spacer(modifier = Modifier.width(productGap))
        Text(
            text = productName,
            color = TextSecondary,
            fontSize = productSize,
            fontWeight = FontWeight.Normal,
            fontFamily = AlluraFont,
            lineHeight = TextUnit.Unspecified,
            modifier = Modifier.offset(y = (productSize.value * 0.08).dp),
        )
    }
}
