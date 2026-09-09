package com.groq.voicetyper.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.groq.voicetyper.theme.*

private data class DrawerEntry(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
)

private val topEntries = listOf(
    DrawerEntry(Screen.Home, "Dashboard", Icons.Default.Dashboard),
    DrawerEntry(Screen.History, "History", Icons.Default.History),
    DrawerEntry(Screen.Snippets, "Voice Snippets", Icons.AutoMirrored.Filled.TextSnippet),
    DrawerEntry(Screen.CustomDictionary, "Custom Dictionary", Icons.Default.Book),
)

private val bottomEntries = listOf(
    DrawerEntry(Screen.SyncConfig, "Sync", Icons.Default.CloudSync),
    DrawerEntry(Screen.SettingsHub, "Settings", Icons.Default.Settings),
)

@Composable
fun FluenceDrawer(
    current: Screen,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(Sidebar)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(vertical = FluenceSpacing.Md)
    ) {
        // No logo or name at top of hamburger menu expansion as requested
        Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

        // Top section segregation header
        Text(
            text = "WORKSPACE",
            color = TextTertiary,
            style = FluenceTypography.labelSmall.copy(
                fontFamily = GeistMonoFont,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp
            ),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = FluenceSpacing.Xs)
        )

        Spacer(modifier = Modifier.height(FluenceSpacing.Xs))

        // Top section: Dashboard, History, Voice Snippets, Custom Dictionary
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
            color = OutlineSubtle,
            thickness = 1.dp,
            modifier = Modifier.padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm)
        )

        // Bottom section segregation header
        Text(
            text = "PREFERENCES",
            color = TextTertiary,
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
    val bg = if (selected) TextPrimary.copy(alpha = 0.08f) else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FluenceSpacing.Sm)
            .clip(FluenceShapes.Small)
            .background(bg)
            .clickable(onClickLabel = "Open $label", onClick = onClick)
            .padding(horizontal = FluenceSpacing.Md, vertical = FluenceSpacing.Sm)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) TextPrimary else TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(FluenceSpacing.Md))
        Text(
            text = label,
            color = if (selected) TextPrimary else TextSecondary,
            style = FluenceTypography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}
