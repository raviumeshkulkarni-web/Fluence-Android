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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import com.groq.voicetyper.theme.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

private data class LlmProviderEntry(
    val id: String,
    val label: String
)

private val LlmProviders = listOf(
    LlmProviderEntry(id = "groq", label = "Groq"),
    LlmProviderEntry(id = "mistral", label = "Mistral"),
    LlmProviderEntry(id = "custom", label = "Custom")
)

private data class LlmSection(
    val id: String,
    val label: String,
    val models: List<String>,
    val refreshFailed: Boolean
)

/** True for speech-to-text and text-to-speech ids, which cleanup excludes. */
internal fun isSpeechModelId(id: String): Boolean {
    val lower = id.lowercase()
    return lower.contains("tts") || SttModelFilter.isAsrModelId(id)
}

// Cleanup model picker for Text Formatting. Same sheet visual language as
// ModelSwitcherSheet (container, drag handle, row treatment), but it lists
// language models from every provider with a saved LLM key, never
// transcription models: keys and base URLs come from the "llm" namespace,
// the same keys entered on the AI Agent Mode screen. The saved pick is
// always kept in its section, so fetching can never strand a selection.
// Selecting a row pins preset + model together; the Follow row clears the
// pick back to AI Agent Mode.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CleanupModelSheet(
    activePreset: String,
    activeModel: String,
    agentPreset: String,
    agentModel: String,
    onSelectModel: (provider: String, model: String) -> Unit,
    onFollowAgent: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var sections by remember { mutableStateOf<List<LlmSection>?>(null) }

    fun dismissThen(action: () -> Unit) {
        coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
            action()
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        val jobs = LlmProviders.map { provider ->
            async {
                val key = SecurityUtils.getProviderApiKey(context, "llm", provider.id)
                val baseUrl = SecurityUtils.getLlmBaseUrl(context, provider.id)
                if (key.isNullOrBlank() || baseUrl.isBlank()) {
                    return@async null
                }
                val fetched = GroqClient.fetchModels(baseUrl = baseUrl, apiKey = key)
                    .getOrElse { emptyList() }
                val saved = if (provider.id == activePreset) {
                    listOf(activeModel).filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
                // Cleanup polishes wording, so transcription and speech models
                // are excluded (inverse of SttModelFilter, plus TTS outputs).
                // The saved pick is always kept, so fetching can never strand
                // a selection.
                val chatOnly = fetched.filterNot { isSpeechModelId(it) }
                LlmSection(
                    id = provider.id,
                    label = provider.label,
                    models = (chatOnly + saved).distinct().sorted(),
                    refreshFailed = fetched.isEmpty()
                )
            }
        }
        sections = jobs.awaitAll().filterNotNull()
    }

    val followingAgent = activePreset.isBlank()
    val agentLabel = LlmProviders.find { it.id == agentPreset }?.label ?: agentPreset

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
                text = "Cleanup model",
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
                text = if (followingAgent && agentModel.isNotBlank()) "Following AI Agent Mode · $agentLabel · $agentModel"
                else "Picks the model that polishes dictation",
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
                    CleanupModelRow(
                        model = "Follow AI Agent Mode",
                        providerCaption = if (agentModel.isNotBlank()) "$agentLabel · $agentModel" else agentLabel,
                        selected = followingAgent,
                        onSelect = { dismissThen(onFollowAgent) }
                    )
                    if (loaded.isEmpty()) {
                        Text(
                            text = "No language model keys yet. Add one in AI Agent Mode to load models.",
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
                            CleanupModelRow(
                                model = model,
                                providerCaption = null,
                                selected = section.id == activePreset && model == activeModel,
                                onSelect = {
                                    onSelectModel(section.id, model)
                                    dismissThen {}
                                }
                            )
                        }
                    }
                    if (loaded.any { it.refreshFailed }) {
                        Text(
                            text = "Some providers could not refresh. Check API keys in AI Agent Mode.",
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
    }
}

@Composable
private fun CleanupModelRow(
    model: String,
    providerCaption: String?,
    selected: Boolean,
    onSelect: () -> Unit,
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
    }
}
