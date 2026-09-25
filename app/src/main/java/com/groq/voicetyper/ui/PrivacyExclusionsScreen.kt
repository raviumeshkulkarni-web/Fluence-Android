package com.groq.voicetyper.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.FluenceEmptyState
import com.groq.voicetyper.PrivacyPreferences
import com.groq.voicetyper.SettingsJointCard
import com.groq.voicetyper.SettingsSectionHeader
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.PrecisionTheme
import com.groq.voicetyper.ui.icons.FluenceIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

@Composable
fun PrivacyExclusionsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableApp>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var excludedPackages by remember {
        mutableStateOf(PrivacyPreferences.getExcludedPackages(context))
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
        isLoading = false
    }

    val filteredApps = remember(apps, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                app.label.lowercase().contains(query) || app.packageName.lowercase().contains(query)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            SettingsTopBar(
                title = "Privacy & App Exclusions",
                onBack = onNavigateBack,
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )

            Text(
                text = "Excluded apps keep Fluence's bubble, dictation, context capture, and Agent actions unavailable.",
                color = colors.textSecondary,
                style = FluenceTypography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = FluenceSpacing.Base,
                        end = FluenceSpacing.Base,
                        top = FluenceSpacing.Xs,
                        bottom = FluenceSpacing.Sm
                    )
            )

            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

            // User-approved expectation note: bank security warnings about
            // accessibility-enabled apps are misattributed to Fluence.
            SettingsJointCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluenceSpacing.Base)
            ) {
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
                            text = "A note on banking apps",
                            color = colors.textPrimary,
                            style = FluenceTypography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(FluenceSpacing.Xxs))
                        Text(
                            text = "Your bank may warn that an app with advanced control capabilities is active. That warning comes from the bank, not Fluence. It appears because Fluence's floating bubble requires Android's accessibility permission, and it would appear even with every app excluded. Your exclusions still hold: Fluence never reads, dictates into, or learns from excluded apps.",
                            color = colors.textSecondary,
                            style = FluenceTypography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

            // Home-consistent Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text("Search apps…", color = colors.textSecondary, style = FluenceTypography.bodySmall)
                },
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = FluenceIcons.Search,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier
                                .size(FluenceSpacing.Xxl)
                                .pressScale(remember { MutableInteractionSource() })
                        ) {
                            Icon(
                                imageVector = FluenceIcons.X,
                                contentDescription = "Clear search",
                                tint = colors.textTertiary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.textPrimary,
                    unfocusedTextColor = colors.textPrimary,
                    focusedBorderColor = if (colors.isLight) colors.brandCyan else colors.textPrimary,
                    unfocusedBorderColor = colors.inputBorder,
                    focusedContainerColor = colors.panelElevated,
                    unfocusedContainerColor = colors.panelElevated,
                    cursorColor = colors.textPrimary
                ),
                shape = FluenceShapes.Small,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = FluenceSpacing.Base),
                textStyle = FluenceTypography.bodySmall
            )

            Spacer(modifier = Modifier.height(FluenceSpacing.Md))

            SettingsSectionHeader(
                title = "Installed Apps",
                description = "Toggle apps to completely exclude them from Fluence",
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = FluenceSpacing.Base)
                    .clip(FluenceShapes.Medium)
                    .background(colors.cardSurface)
                    .border(1.dp, colors.cardBorder, FluenceShapes.Medium)
            ) {
                when {
                    isLoading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.textSecondary)
                        }
                    }
                    filteredApps.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            FluenceEmptyState(
                                icon = if (apps.isEmpty()) {
                                    Icons.Default.PhoneAndroid
                                } else {
                                    FluenceIcons.Search
                                },
                                title = if (apps.isEmpty()) {
                                    "No launchable apps found"
                                } else {
                                    "No apps match your search"
                                },
                                description = if (apps.isEmpty()) {
                                    "No apps installed that can be excluded."
                                } else {
                                    "Try a different word or app name."
                                }
                            )
                        }
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(filteredApps, key = { _, app -> app.packageName }) { index, app ->
                                val isExcluded = excludedPackages.contains(app.packageName)
                                AppExclusionRow(
                                    app = app,
                                    isExcluded = isExcluded,
                                    onCheckedChange = { excluded ->
                                        PrivacyPreferences.setPackageExcluded(context, app.packageName, excluded)
                                        excludedPackages = excludedPackages.toMutableSet().apply {
                                            if (excluded) add(app.packageName) else remove(app.packageName)
                                        }
                                    }
                                )
                                if (index < filteredApps.size - 1) {
                                    HorizontalDivider(color = colors.divider, thickness = 1.dp)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Base))
        }
    }
}

@Composable
private fun AppExclusionRow(
    app: LaunchableApp,
    isExcluded: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = PrecisionTheme.colors
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactionSource)
            .toggleable(
                value = isExcluded,
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Md)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncAppIcon(
            packageName = app.packageName,
            fallbackDrawable = app.icon
        )

        Spacer(modifier = Modifier.width(FluenceSpacing.Md))

        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, color = colors.textPrimary, style = FluenceTypography.titleMedium)
        }

        // Monochrome Switch Styling matching app design system
        Switch(
            checked = isExcluded,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = if (colors.isLight) androidx.compose.ui.graphics.Color.White else colors.panel,
                checkedTrackColor = if (colors.isLight) colors.charcoal else colors.textPrimary,
                uncheckedThumbColor = colors.textPrimary,
                uncheckedTrackColor = colors.panel
            ),
            modifier = Modifier.semantics {
                contentDescription = "Exclude ${app.label}"
                stateDescription = if (isExcluded) "Excluded" else "Not excluded"
            }
        )
    }
}

private fun loadLaunchableApps(context: Context): List<LaunchableApp> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    return packageManager.queryIntentActivities(launcherIntent, 0)
        .asSequence()
        .map { it.activityInfo.applicationInfo }
        .filter { it.packageName != context.packageName }
        .distinctBy(ApplicationInfo::packageName)
        .map { applicationInfo ->
            LaunchableApp(
                packageName = applicationInfo.packageName,
                label = applicationInfo.loadLabel(packageManager).toString(),
                icon = applicationInfo.loadIcon(packageManager)
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}
