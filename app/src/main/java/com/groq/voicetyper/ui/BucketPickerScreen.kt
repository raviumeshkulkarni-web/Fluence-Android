package com.groq.voicetyper.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.FeedbackBus
import com.groq.voicetyper.FluenceEmptyState
import com.groq.voicetyper.FluenceSectionHeader
import com.groq.voicetyper.SettingsJointCard
import com.groq.voicetyper.SettingsSectionHeader
import com.groq.voicetyper.formatting.AppAwareFormatter
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.formatting.BuiltInApps
import com.groq.voicetyper.formatting.FormattingCategory
import com.groq.voicetyper.formatting.FormattingPreferences
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.PrecisionTheme
import com.groq.voicetyper.ui.icons.FluenceIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class PickerApp(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

// One style bucket's app picker. Single-select by construction: each app
// holds at most one override, so tapping an app here moves it into this
// bucket (clearing any other bucket), and tapping it again sends it back
// to Auto. Moves are announced with a lightweight confirmation and are
// trivially reversible, so no blocking dialog is ever shown.
@Composable
fun BucketPickerScreen(
    bucket: FormattingCategory,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<PickerApp>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var overrides by remember {
        mutableStateOf(FormattingPreferences.getOverrides(context))
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadPickerApps(context) }
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
                title = "${FormattingCategory.label(bucket)} apps",
                onBack = onNavigateBack,
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )

            Text(
                text = "Tap an app to file it in this bucket. Tap again to move it back to Auto. Each app follows exactly one bucket.",
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

            // Live example computed by the real formatter, shown where the
            // decision happens. Set in its own quiet block with bullets so it
            // reads as an example, not as settings text. No quotes, so
            // punctuation stays readable.
            val sampleIn = "Hello world. hello again."
            val sampleOut = remember(bucket) { AppAwareFormatter.format(sampleIn, bucket) }
            SettingsSectionHeader(
                title = "Example",
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            )
            SettingsJointCard(
                modifier = Modifier.padding(horizontal = FluenceSpacing.Base)
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = FluenceSpacing.Base,
                        vertical = FluenceSpacing.Sm
                    )
                ) {
                    Text(
                        text = "•  Mic heard: $sampleIn",
                        color = colors.textSecondary,
                        style = FluenceTypography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "•  ${FormattingCategory.label(bucket)} makes: $sampleOut",
                        color = colors.textPrimary,
                        style = FluenceTypography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

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
                        val clearSearchInteraction = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = { searchQuery = "" },
                            interactionSource = clearSearchInteraction,
                            modifier = Modifier
                                .size(FluenceSpacing.Xxl)
                                .pressScale(clearSearchInteraction)
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
                description = "Tap an app to assign this formatting style",
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
                                    "No apps installed that can be placed."
                                } else {
                                    "Try a different word or app name."
                                }
                            )
                        }
                    }
                    else -> {
                        // Selected apps pin to an INCLUDED section on top so the
                        // user always sees what is in this bucket without
                        // scrolling. Everything else follows under ALL APPS.
                        val (included, rest) = filteredApps.partition { app ->
                            overrides[app.packageName] == bucket
                        }
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            if (included.isNotEmpty()) {
                                item(key = "included-header") {
                                    FluenceSectionHeader(
                                        label = "INCLUDED",
                                        modifier = Modifier.padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm)
                                    )
                                }
                                itemsIndexed(included, key = { _, it -> "in-${it.packageName}" }) { index, app ->
                                    BucketPickerItem(
                                        app = app,
                                        bucket = bucket,
                                        overrides = overrides,
                                        onMove = { label -> FeedbackBus.show(label) },
                                        onOverridesChange = { overrides = it }
                                    )
                                    if (index < included.size - 1 || rest.isNotEmpty()) {
                                        HorizontalDivider(color = colors.divider, thickness = 1.dp)
                                    }
                                }
                            }
                            if (rest.isNotEmpty()) {
                                if (included.isNotEmpty()) {
                                    item(key = "all-header") {
                                        FluenceSectionHeader(
                                            label = "ALL APPS",
                                            modifier = Modifier.padding(horizontal = FluenceSpacing.Base, vertical = FluenceSpacing.Sm)
                                        )
                                    }
                                }
                                itemsIndexed(rest, key = { _, it -> "all-${it.packageName}" }) { index, app ->
                                    BucketPickerItem(
                                        app = app,
                                        bucket = bucket,
                                        overrides = overrides,
                                        onMove = { label -> FeedbackBus.show(label) },
                                        onOverridesChange = { overrides = it }
                                    )
                                    if (index < rest.size - 1) {
                                        HorizontalDivider(color = colors.divider, thickness = 1.dp)
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
private fun BucketPickerItem(
    app: PickerApp,
    bucket: FormattingCategory,
    overrides: Map<String, FormattingCategory>,
    onMove: (String) -> Unit,
    onOverridesChange: (Map<String, FormattingCategory>) -> Unit
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val override = overrides[app.packageName]
    val checked = override == bucket
    val badge = when (override) {
        null -> BuiltInApps.PACKAGE_CATEGORY[app.packageName]?.let { "${FormattingCategory.label(it)} · Auto" } ?: "Auto"
        else -> FormattingCategory.label(override)
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
                        FormattingPreferences.setOverride(context, app.packageName, null)
                        onOverridesChange(overrides.toMutableMap().apply { remove(app.packageName) })
                        onMove("${app.label} back to Auto")
                    } else {
                        FormattingPreferences.setOverride(context, app.packageName, bucket)
                        onOverridesChange(overrides.toMutableMap().apply { put(app.packageName, bucket) })
                        onMove("${app.label} moved to ${FormattingCategory.label(bucket)}")
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

private fun loadPickerApps(context: Context): List<PickerApp> {
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
            PickerApp(
                packageName = applicationInfo.packageName,
                label = applicationInfo.loadLabel(packageManager).toString(),
                icon = applicationInfo.loadIcon(packageManager)
            )
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}
