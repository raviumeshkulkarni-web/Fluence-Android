package com.groq.voicetyper.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.theme.*
import com.groq.voicetyper.ui.icons.FluenceIcons

private data class DrawerEntry(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
)

// Windows destination order and terminology: Dashboard, History,
// Dictionary, Snippets, Sync. (General/Providers/About live in the Android
// Settings hub — deliberate mobile IA, not a drawer gap.)
private val topEntries = listOf(
    DrawerEntry(Screen.Home, "Dashboard", FluenceIcons.LayoutDashboard),
    DrawerEntry(Screen.History, "History", FluenceIcons.History),
    DrawerEntry(Screen.CustomDictionary, "Dictionary", FluenceIcons.BookOpen),
    DrawerEntry(Screen.Snippets, "Snippets", FluenceIcons.Braces),
)

private val bottomEntries = listOf(
    DrawerEntry(Screen.SyncConfig, "Sync", FluenceIcons.RefreshCw),
    DrawerEntry(Screen.SettingsHub, "Settings", FluenceIcons.Settings),
)

@Composable
fun FluenceDrawer(
    current: Screen,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    Column(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(colors.sidebar)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(vertical = FluenceSpacing.Md)
    ) {
        // No logo or name at top of hamburger menu expansion as requested
        Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

        // Top section segregation header
        Text(
            text = "WORKSPACE",
            color = colors.textTertiary,
            style = FluenceTypography.labelSmall.copy(
                fontFamily = GeistMonoFont,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            ),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = FluenceSpacing.Xs)
        )

        Spacer(modifier = Modifier.height(FluenceSpacing.Xs))

        // Top section: Dashboard, History, Dictionary, Snippets
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            topEntries.forEach { entry ->
                DrawerRow(
                    label = entry.label,
                    icon = entry.icon,
                    selected = isSameRoot(current, entry.screen),
                    onClick = { onNavigate(entry.screen) }
                )
            }
        }

        // Divider separating top section and bottom section
        HorizontalDivider(
            color = colors.outlineSubtle,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm)
        )

        // Bottom section segregation header
        Text(
            text = "PREFERENCES",
            color = colors.textTertiary,
            style = FluenceTypography.labelSmall.copy(
                fontFamily = GeistMonoFont,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            ),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = FluenceSpacing.Xs)
        )

        Spacer(modifier = Modifier.height(FluenceSpacing.Xs))

        // Bottom section: Sync, Settings
        bottomEntries.forEach { entry ->
            DrawerRow(
                label = entry.label,
                icon = entry.icon,
                selected = isSameRoot(current, entry.screen),
                onClick = { onNavigate(entry.screen) }
            )
        }

        Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
    }
}

private fun isSameRoot(current: Screen, target: Screen): Boolean {
    return current::class == target::class
}

@Composable
private fun DrawerRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = PrecisionTheme.colors
    // Windows selected treatment: elevated surface + structural border +
    // primary semibold text; idle icons sit at reduced opacity.
    val bg = if (selected) colors.panelElevated else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Sm)
            .clip(FluenceShapes.Small)
            .background(bg)
            .then(if (selected) Modifier.border(1.dp, colors.outlineSubtle, FluenceShapes.Small) else Modifier)
            .clickable(onClickLabel = "Open $label", onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) colors.textPrimary else colors.textSecondary,
            modifier = Modifier
                .size(20.dp)
                .alpha(if (selected) 1f else 0.65f)
        )
        Spacer(modifier = Modifier.width(FluenceSpacing.Base))
        Text(
            text = label,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            style = FluenceTypography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
    }
}
