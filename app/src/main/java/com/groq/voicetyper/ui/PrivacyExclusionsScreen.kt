package com.groq.voicetyper.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.FluenceEmptyState
import com.groq.voicetyper.FluenceSectionHeader
import com.groq.voicetyper.PrivacyPreferences
import com.groq.voicetyper.SettingsDivider
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
        apps = withContext(Dispatchers.IO) { loadLaunchableApps(context, excludedPackages) }
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

            // Exclusions are a Fluence-level policy, not an OS permission
            // change: payment apps that block on an enabled accessibility
            // service still block for excluded packages.
            AccessibilityPaymentNotice(
                body = "Google Pay and some banking apps block payments while a third-party accessibility service is enabled. That block is enforced by the payment app and cannot be bypassed from Fluence, even when the app is excluded. To pay, turn the Accessibility Service off; the voice keyboard keeps working without it. Your exclusions still hold: Fluence never reads, dictates into, or learns from excluded apps.",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluenceSpacing.Base)
            )

            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

            FluenceSearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                placeholder = "Search apps…",
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )

            Spacer(modifier = Modifier.height(FluenceSpacing.Md))

            SettingsSectionHeader(
                title = "Installed Apps",
                description = "Excluded apps appear at the top. Toggle apps to exclude them from Fluence.",
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = FluenceSpacing.Base)
                    .clip(FluenceShapes.Medium)
                    .background(if (colors.isLight) colors.cardSurface else colors.panel)
                    .border(
                        1.dp,
                        if (colors.isLight) colors.cardBorder else colors.outlineSubtle,
                        FluenceShapes.Medium
                    )
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
                                    FluenceIcons.Smartphone
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
                        val (excluded, rest) = filteredApps.partition { it.packageName in excludedPackages }
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            if (excluded.isNotEmpty()) {
                                item(key = "excluded-header") {
                                    FluenceSectionHeader(
                                        label = "EXCLUDED",
                                        modifier = Modifier.padding(
                                            horizontal = FluenceSpacing.Base,
                                            vertical = FluenceSpacing.Sm
                                        )
                                    )
                                }
                                itemsIndexed(excluded, key = { _, app -> "ex-${app.packageName}" }) { index, app ->
                                    AppExclusionRow(
                                        app = app,
                                        isExcluded = true,
                                        onCheckedChange = { shouldExclude ->
                                            PrivacyPreferences.setPackageExcluded(context, app.packageName, shouldExclude)
                                            excludedPackages = excludedPackages.toMutableSet().apply {
                                                if (shouldExclude) add(app.packageName) else remove(app.packageName)
                                            }
                                        }
                                    )
                                    if (index < excluded.lastIndex || rest.isNotEmpty()) {
                                        SettingsDivider()
                                    }
                                }
                            }
                            if (rest.isNotEmpty()) {
                                if (excluded.isNotEmpty()) {
                                    item(key = "all-header") {
                                        FluenceSectionHeader(
                                            label = "ALL APPS",
                                            modifier = Modifier.padding(
                                                horizontal = FluenceSpacing.Base,
                                                vertical = FluenceSpacing.Sm
                                            )
                                        )
                                    }
                                }
                                itemsIndexed(rest, key = { _, app -> "all-${app.packageName}" }) { index, app ->
                                    AppExclusionRow(
                                        app = app,
                                        isExcluded = false,
                                        onCheckedChange = { shouldExclude ->
                                            PrivacyPreferences.setPackageExcluded(context, app.packageName, shouldExclude)
                                            excludedPackages = excludedPackages.toMutableSet().apply {
                                                if (shouldExclude) add(app.packageName) else remove(app.packageName)
                                            }
                                        }
                                    )
                                    if (index < rest.lastIndex) {
                                        SettingsDivider()
                                    }
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
            colors = fluenceSwitchColors(),
            modifier = Modifier.semantics {
                contentDescription = "Exclude ${app.label}"
                stateDescription = if (isExcluded) "Excluded" else "Not excluded"
            }
        )
    }
}

private fun loadLaunchableApps(
    context: Context,
    excludedPackages: Set<String>
): List<LaunchableApp> {
    val packageManager = context.packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val launchable = packageManager.queryIntentActivities(launcherIntent, 0)
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
        .toMutableList()

    val presentPackages = launchable.mapTo(HashSet()) { it.packageName }
    for (pkg in excludedPackages) {
        if (pkg.isBlank() || pkg == context.packageName || pkg in presentPackages) continue
        val resolved = try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            LaunchableApp(
                packageName = pkg,
                label = appInfo.loadLabel(packageManager).toString(),
                icon = appInfo.loadIcon(packageManager)
            )
        } catch (_: PackageManager.NameNotFoundException) {
            LaunchableApp(
                packageName = pkg,
                label = pkg,
                icon = packageManager.defaultActivityIcon
            )
        }
        launchable.add(resolved)
        presentPackages.add(pkg)
    }

    return launchable.sortedBy { it.label.lowercase() }
}
