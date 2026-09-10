package com.groq.voicetyper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.CardBorder
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.Panel
import com.groq.voicetyper.theme.PanelElevated
import com.groq.voicetyper.theme.TextPrimary
import com.groq.voicetyper.theme.TextSecondary

// ── Fluence segmented control ───────────────────────────────────────────────
// Shared implementation behind the Activity chart range selector and the
// History date filter — one control, one visual language (Windows Radix
// Tabs parity: neutral elevated selected segment + primary text, secondary
// unselected text, 44dp touch target, Hanken 12sp medium in both states
// with color-only selection and no weight shift). Add new usages here;
// do not fork it.
// ────────────────────────────────────────────────────────────────────────────

data class SegmentChoice(
    val label: String,
    val accessibilityLabel: String,
)

@Composable
fun FluenceSegmentedControl(
    options: List<SegmentChoice>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Panel, FluenceShapes.Small)
            .border(1.dp, CardBorder, FluenceShapes.Small)
            .clip(FluenceShapes.Small)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(FluenceShapes.ExtraSmall)
                    .background(if (isSelected) PanelElevated else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        onClick = { onSelect(index) },
                        role = Role.Tab,
                        interactionSource = interactionSource,
                        indication = null,
                    )
                    .pressScale(interactionSource)
                    .semantics { contentDescription = option.accessibilityLabel },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    color = if (isSelected) TextPrimary else TextSecondary,
                    style = FluenceTypography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
