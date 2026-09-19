package com.groq.voicetyper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.FluenceSectionHeader
import com.groq.voicetyper.GroqClient
import com.groq.voicetyper.SecurityUtils
import com.groq.voicetyper.SttModelFilter
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

private data class ProviderEntry(
    val id: String,
    val label: String,
    val baseUrl: String,
    val fetchable: Boolean
)

private val SttProviders = listOf(
    ProviderEntry(id = "groq", label = "Groq", baseUrl = "https://api.groq.com/openai", fetchable = true),
    ProviderEntry(id = "mistral", label = "Mistral", baseUrl = "https://api.mistral.ai", fetchable = true),
    ProviderEntry(id = "custom", label = "Custom", baseUrl = "", fetchable = false)
)

private data class ProviderSection(
    val id: String,
    val label: String,
    val models: List<String>,
    val refreshFailed: Boolean
)

private data class FavoritePick(
    val providerId: String,
    val providerLabel: String,
    val model: String
)

// Home model quick-switcher, Windows parity for the picker contract.
// Lists transcription capable models from every configured provider, not
// just the active one. Each provider list passes through SttModelFilter
// (same markers as the Windows picker, streaming and batch both pass) with
// the saved model kept, so fetching can never strand a selection.
// Selecting a row switches provider and model together through the same
// SecurityUtils selection Settings edits, so the two surfaces never drift.
// "Detailed selection" escapes to SttConfig, the full editor with keys,
// language and streaming. Styling mirrors HistorySortBottomSheet (same
// container, drag handle, row language): one sheet visual language.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSwitcherSheet(
    activeProvider: String,
    activeModel: String,
    isOffline: Boolean,
    offlineLabel: String,
    onSelectModel: (provider: String, model: String) -> Unit,
    onOpenDetailed: () -> Unit,
    onOpenOfflineConfig: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var favoritesByProvider by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var sections by remember { mutableStateOf<List<ProviderSection>?>(null) }

    fun toggleFavorite(providerId: String, model: String) {
        val current = favoritesByProvider[providerId].orEmpty()
        val next = if (model in current) current - model else current + model
        favoritesByProvider = favoritesByProvider + (providerId to next)
        SecurityUtils.saveFavoriteSttModels(context, providerId, next)
    }

    fun dismissThen(action: () -> Unit) {
        coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
            action()
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        val favs = SttProviders.associate { it.id to SecurityUtils.getFavoriteSttModels(context, it.id) }
        favoritesByProvider = favs
        val jobs = SttProviders.map { provider ->
            async {
                val key = SecurityUtils.getProviderApiKey(context, "stt", provider.id)
                val baseUrl = if (provider.id == "custom") {
                    SecurityUtils.getSttBaseUrl(context, "custom")
                } else {
                    provider.baseUrl
                }
                val saved = SecurityUtils.getSttModel(context, provider.id)
                if (key.isNullOrBlank() || (provider.id == "custom" && baseUrl.isBlank())) {
                    return@async null
                }
                if (!provider.fetchable) {
                    return@async ProviderSection(
                        id = provider.id,
                        label = provider.label,
                        models = SttModelFilter.applyFilter(listOf(saved), saved),
                        refreshFailed = false
                    )
                }
                val fetched = GroqClient.fetchModels(baseUrl = baseUrl, apiKey = key)
                    .getOrElse { emptyList() }
                ProviderSection(
                    id = provider.id,
                    label = provider.label,
                    models = SttModelFilter.applyFilter(fetched, saved),
                    refreshFailed = fetched.isEmpty()
                )
            }
        }
        sections = jobs.awaitAll().filterNotNull()
    }

    val activeLabel = SttProviders.find { it.id == activeProvider }?.label ?: activeProvider

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.panelElevated,
        contentColor = colors.textPrimary,
        shape = FluenceShapes.Large,
        windowInsets = WindowInsets(0, 0, 0, 0),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = FluenceSpacing.Md)
                    .width(FluenceSpacing.Xl)
                    .height(FluenceSpacing.Xs)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.textPrimary.copy(alpha = 0.18f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = FluenceSpacing.Base)
        ) {
            Text(
                text = "Transcription model",
                color = colors.textPrimary,
                style = FluenceTypography.headlineMedium,
                modifier = Modifier.padding(
                    start = FluenceSpacing.Lg,
                    end = FluenceSpacing.Lg,
                    top = FluenceSpacing.Xs,
                    bottom = FluenceSpacing.Xs
                )
            )
            Text(
                text = if (isOffline) "$offlineLabel (Offline)" else "$activeLabel · $activeModel",
                color = colors.textSecondary,
                style = FluenceTypography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    start = FluenceSpacing.Lg,
                    end = FluenceSpacing.Lg,
                    bottom = FluenceSpacing.Sm
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (isOffline) {
                    Text(
                        text = "Offline mode is on. The on-device engine handles transcription. Switch back to Online from the Home screen, or manage engines below.",
                        color = colors.textSecondary,
                        style = FluenceTypography.bodyMedium,
                        modifier = Modifier.padding(
                            horizontal = FluenceSpacing.Lg,
                            vertical = FluenceSpacing.Sm
                        )
                    )
                } else {
                    val loaded = sections
                    if (loaded == null) {
                        Text(
                            text = "Fetching models…",
                            color = colors.textSecondary,
                            style = FluenceTypography.bodyMedium,
                            modifier = Modifier.padding(
                                horizontal = FluenceSpacing.Lg,
                                vertical = FluenceSpacing.Sm
                            )
                        )
                    } else {
                        val picks = SttProviders.flatMap { provider ->
                            favoritesByProvider[provider.id].orEmpty().sorted().map { model ->
                                FavoritePick(
                                    providerId = provider.id,
                                    providerLabel = provider.label,
                                    model = model
                                )
                            }
                        }
                        if (picks.isNotEmpty()) {
                            FluenceSectionHeader(label = "Favorites")
                            Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
                            picks.forEach { pick ->
                                ModelRow(
                                    model = pick.model,
                                    providerCaption = pick.providerLabel,
                                    selected = pick.providerId == activeProvider && pick.model == activeModel,
                                    favorite = true,
                                    favoriteContentDescription = "Remove ${pick.model} from favorites",
                                    onSelect = {
                                        onSelectModel(pick.providerId, pick.model)
                                        dismissThen {}
                                    },
                                    onToggleFavorite = { toggleFavorite(pick.providerId, pick.model) }
                                )
                            }
                            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
                        }

                        if (loaded.isEmpty() && picks.isEmpty()) {
                            Text(
                                text = "No provider keys yet. Add one in Detailed selection to load models.",
                                color = colors.textSecondary,
                                style = FluenceTypography.bodyMedium,
                                modifier = Modifier.padding(
                                    horizontal = FluenceSpacing.Lg,
                                    vertical = FluenceSpacing.Sm
                                )
                            )
                        }
                        loaded.forEach { section ->
                            FluenceSectionHeader(label = section.label)
                            Spacer(modifier = Modifier.height(FluenceSpacing.Xs))
                            section.models.forEach { model ->
                                val isFav = model in favoritesByProvider[section.id].orEmpty()
                                ModelRow(
                                    model = model,
                                    providerCaption = null,
                                    selected = section.id == activeProvider && model == activeModel,
                                    favorite = isFav,
                                    favoriteContentDescription = if (isFav) {
                                        "Remove $model from favorites"
                                    } else {
                                        "Add $model to favorites"
                                    },
                                    onSelect = {
                                        onSelectModel(section.id, model)
                                        dismissThen {}
                                    },
                                    onToggleFavorite = { toggleFavorite(section.id, model) }
                                )
                            }
                        }
                        if (loaded.any { it.refreshFailed }) {
                            Text(
                                text = "Some providers could not refresh. Check API keys in Detailed selection.",
                                color = colors.textTertiary,
                                style = FluenceTypography.labelMedium,
                                modifier = Modifier.padding(
                                    horizontal = FluenceSpacing.Lg,
                                    vertical = FluenceSpacing.Xs
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))
            Row(modifier = Modifier.fillMaxWidth()) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        if (isOffline) dismissThen(onOpenOfflineConfig) else dismissThen(onOpenDetailed)
                    },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .padding(end = FluenceSpacing.Base)
                ) {
                    Text(
                        text = if (isOffline) "Manage offline models" else "Detailed selection",
                        color = colors.textPrimary,
                        style = FluenceTypography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: String,
    providerCaption: String?,
    selected: Boolean,
    favorite: Boolean,
    favoriteContentDescription: String,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val colors = PrecisionTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .padding(horizontal = FluenceSpacing.Lg, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = model,
            color = if (selected) colors.textPrimary else colors.textSecondary,
            style = FluenceTypography.bodyLarge.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (providerCaption != null) {
            Text(
                text = providerCaption,
                color = colors.textTertiary,
                style = FluenceTypography.labelSmall,
                maxLines = 1,
                modifier = Modifier.padding(start = FluenceSpacing.Sm)
            )
        }
        if (selected) {
            Spacer(modifier = Modifier.width(FluenceSpacing.Xs))
            Icon(
                imageVector = com.groq.voicetyper.ui.icons.FluenceIcons.Check,
                contentDescription = null,
                tint = colors.textPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
        val starInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        IconButton(
            onClick = onToggleFavorite,
            modifier = Modifier
                .size(48.dp)
                .pressScale(starInteraction),
            interactionSource = starInteraction
        ) {
            Icon(
                imageVector = if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = favoriteContentDescription,
                tint = if (favorite) colors.textPrimary else colors.textTertiary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
