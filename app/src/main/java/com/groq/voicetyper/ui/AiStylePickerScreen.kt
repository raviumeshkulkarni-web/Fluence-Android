package com.groq.voicetyper.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.FeedbackBus
import com.groq.voicetyper.FluenceEmptyState
import com.groq.voicetyper.FluenceSectionHeader
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.cleanup.AiCleanupPreferences
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.PrecisionTheme
import com.groq.voicetyper.ui.icons.FluenceIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class AiPickerApp(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

// One AI style's app picker. Same pattern as BucketPickerScreen: single
// select within AI styles, each app holds at most one AI override. Tapping
// moves the app here, tapping again sends it back to Auto. Deterministic
// buckets are untouched, so an app can hold one bucket plus one AI style.
@Composable
fun AiStylePickerScreen(
    styleId: String,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AiPickerApp>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var overrides by remember {
        mutableStateOf(AiCleanupPreferences.getOverrides(context))
    }

    val title = remember(styleId) {
        AiCleanupPreferences.styleTitle(context, styleId)
    }
    // Built-in styles show a fixed before/after example. Custom styles have
    // user-written instructions, so no canned example applies — the section
    // is hidden for them instead of showing a misleading generic one.
    val isBuiltIn = remember(styleId) { AiCleanupPreferences.isBuiltIn(styleId) }
    val exampleIn = remember(styleId) {
        if (isBuiltIn) AiCleanupPreferences.builtInMeta(styleId).exampleIn else ""
    }
    val exampleOut = remember(styleId) {
        if (isBuiltIn) AiCleanupPreferences.builtInMeta(styleId).exampleOut else ""
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadAiPickerApps(context) }
        isLoading = false
    }

    val filteredApps = remember(apps, searchQuery) {
        val query = searchQuery.trim().lowercase()
        if (query.isBlank()) apps
        else apps.filter { app ->
            app.label.lowercase().contains(query) || app.packageName.lowercase().contains(query)
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
        Column(modifier = Modifier.fillMaxSize()) {
            SettingsTopBar(
                title = "$title apps",
                onBack = onNavigateBack,
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )

            androidx.compose.material3.Text(
                text = "Tap an app to give it this style. Tap again to move it back to Auto. Each app follows exactly one style.",
                color = colors.textSecondary,
                style = FluenceTypography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = FluenceSpacing.Base,
                        end = FluenceSpacing.Base,
                        top = FluenceSpacing.Xs,
                        bottom = FluenceSpacing.Sm
                    )
            )

            if (isBuiltIn) {
                FluenceSectionHeader(label = "EXAMPLE")
                Surface(
                    color = colors.panel,
                    shape = FluenceShapes.Small,
                    border = BorderStroke(1.dp, colors.outlineSubtle),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = FluenceSpacing.Base)
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = FluenceSpacing.Md,
                            vertical = FluenceSpacing.Sm
                        )
                    ) {
                        Text(
                            text = "Mic heard: $exampleIn",
                            color = colors.textSecondary,
                            style = FluenceTypography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$title makes: $exampleOut",
                            color = colors.textPrimary,
                            style = FluenceTypography.bodySmall
                        )
                    }
                }

                Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
            }

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
                    focusedBorderColor = if (colors.isLight) colors.brandCyan.copy(alpha = 0.55f) else colors.textSecondary,
                    unfocusedBorderColor = colors.outlineSubtle,
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
            HorizontalDivider(color = colors.outlineSubtle)

            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colors.textSecondary)
                    }
                }
                filteredApps.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        FluenceEmptyState(
                            icon = if (apps.isEmpty()) Icons.Default.PhoneAndroid else FluenceIcons.Search,
                            title = if (apps.isEmpty()) "No launchable apps found" else "No apps match your search",
                            description = if (apps.isEmpty()) "No apps installed that can be placed."
                            else "Try a different word or app name."
                        )
                    }
                }
                else -> {
                    val (included, rest) = filteredApps.partition { app ->
                        overrides[app.packageName] == styleId
                    }
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (included.isNotEmpty()) {
                            item(key = "included-header") {
                                FluenceSectionHeader(label = "INCLUDED")
                            }
                            items(included, key = { "in-${it.packageName}" }) { app ->
                                AiPickerItem(
                                    app = app,
                                    styleId = styleId,
                                    overrides = overrides,
                                    onMove = { label -> FeedbackBus.show(label) },
                                    onOverridesChange = { overrides = it }
                                )
                                HorizontalDivider(color = colors.outlineSubtle, modifier = Modifier.padding(start = 76.dp))
                            }
                        }
                        if (rest.isNotEmpty()) {
                            item(key = "all-header") {
                                FluenceSectionHeader(label = "ALL APPS")
                            }
                            items(rest, key = { "all-${it.packageName}" }) { app ->
                                AiPickerItem(
                                    app = app,
                                    styleId = styleId,
                                    overrides = overrides,
                                    onMove = { label -> FeedbackBus.show(label) },
                                    onOverridesChange = { overrides = it }
                                )
                                HorizontalDivider(color = colors.outlineSubtle, modifier = Modifier.padding(start = 76.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AiPickerItem(
    app: AiPickerApp,
    styleId: String,
    overrides: Map<String, String>,
    onMove: (String) -> Unit,
    onOverridesChange: (Map<String, String>) -> Unit
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val override = overrides[app.packageName]
    val checked = override == styleId
    val badge = when (override) {
        null -> "Auto"
        styleId -> AiCleanupPreferences.styleTitle(context, styleId)
        else -> AiCleanupPreferences.styleTitle(context, override)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClickLabel = if (checked) "Remove ${app.label}" else "Move ${app.label} here",
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = {
                    if (checked) {
                        AiCleanupPreferences.setOverride(context, app.packageName, null)
                        onOverridesChange(overrides.toMutableMap().apply { remove(app.packageName) })
                        onMove("${app.label} back to Auto")
                    } else {
                        AiCleanupPreferences.setOverride(context, app.packageName, styleId)
                        onOverridesChange(overrides.toMutableMap().apply { put(app.packageName, styleId) })
                        onMove("${app.label} moved to ${AiCleanupPreferences.styleTitle(context, styleId)}")
                    }
                }
            )
            .padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Md)
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncAppIcon(
            packageName = app.packageName,
            fallbackDrawable = app.icon
        )

        Spacer(modifier = Modifier.width(FluenceSpacing.Md))

        Column(modifier = Modifier.weight(1f)) {
            Text(app.label, color = colors.textPrimary, style = FluenceTypography.titleMedium)
            Spacer(modifier = Modifier.height(2.dp))
            Text(badge, color = colors.textSecondary, style = FluenceTypography.bodySmall)
        }

        if (checked) {
            Icon(
                imageVector = FluenceIcons.Check,
                contentDescription = null,
                tint = colors.textPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun loadAiPickerApps(context: Context): List<AiPickerApp> {
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
            AiPickerApp(
                packageName = applicationInfo.packageName,
                label = applicationInfo.loadLabel(packageManager).toString(),
                icon = applicationInfo.loadIcon(packageManager)
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}
