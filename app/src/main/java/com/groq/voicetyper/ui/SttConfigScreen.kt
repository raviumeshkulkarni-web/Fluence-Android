package com.groq.voicetyper.ui

import com.groq.voicetyper.FeedbackBus
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.GroqClient
import com.groq.voicetyper.SecurityUtils
import com.groq.voicetyper.SettingsDivider
import com.groq.voicetyper.SettingsJointCard
import com.groq.voicetyper.SettingsSectionHeader
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.FluenceMotion
import com.groq.voicetyper.theme.FluenceShapes
import com.groq.voicetyper.theme.FluenceSpacing
import com.groq.voicetyper.theme.FluenceTypography
import com.groq.voicetyper.theme.LocalMotionPreferences
import com.groq.voicetyper.theme.PrecisionTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun SttCredentialActionsRow(
    onSave: () -> Unit,
    onTest: () -> Unit,
    isTesting: Boolean,
    testResult: Pair<Boolean, String>?
) {
    val colors = PrecisionTheme.colors
    Spacer(modifier = Modifier.height(FluenceSpacing.Base))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val saveInteraction = remember { MutableInteractionSource() }
        Button(
            onClick = onSave,
            colors = ButtonDefaults.buttonColors(containerColor = colors.buttonSecondary),
            shape = FluenceShapes.Medium,
            interactionSource = saveInteraction,
            modifier = Modifier
                .weight(1f)
                .pressScale(saveInteraction)
        ) {
            Text(text = "Save", color = colors.textPrimary, style = FluenceTypography.labelLarge)
        }

        val testInteraction = remember { MutableInteractionSource() }
        Button(
            onClick = onTest,
            colors = ButtonDefaults.buttonColors(containerColor = colors.buttonSecondary),
            shape = FluenceShapes.Medium,
            interactionSource = testInteraction,
            modifier = Modifier
                .weight(1f)
                .pressScale(testInteraction),
            enabled = !isTesting
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    color = colors.textPrimary,
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 1.5.dp
                )
            } else {
                Text(text = "Test Connection", color = colors.textPrimary, style = FluenceTypography.labelLarge)
            }
        }
    }

    testResult?.let { result ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = result.second,
            color = if (result.first) colors.success else colors.errorText,
            style = FluenceTypography.bodySmall
        )
    }
}

private data class SttLoadedState(
    val preset: String,
    val groqKey: String,
    val mistralKey: String,
    val customKey: String,
    val customUrl: String,
    val customModel: String,
    val modelsByProvider: Map<String, String>,
    val language: String?
)

@Composable
fun SttConfigScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val reducedMotion = LocalMotionPreferences.current.reducedMotion
    val sizeAnimationSpec = remember(reducedMotion) {
        if (reducedMotion) {
            snap<IntSize>()
        } else {
            tween<IntSize>(
                durationMillis = FluenceMotion.durationStructural,
                easing = FastOutSlowInEasing
            )
        }
    }
    val expandEnter = remember(reducedMotion) {
        if (reducedMotion) EnterTransition.None
        else fadeIn(tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing)) +
            expandVertically(tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing))
    }
    val collapseExit = remember(reducedMotion) {
        if (reducedMotion) ExitTransition.None
        else fadeOut(tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing)) +
            shrinkVertically(tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing))
    }

    var selectedProvider by remember { mutableStateOf("groq") }
    var apiKey by remember { mutableStateOf("") }
    var mistralApiKey by remember { mutableStateOf("") }
    var customApiKey by remember { mutableStateOf("") }
    var customBaseUrl by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf("") }
    var modelsByProvider by remember {
        mutableStateOf(
            mapOf(
                "groq" to "whisper-large-v3",
                "mistral" to "voxtral-mini-latest",
                "custom" to ""
            )
        )
    }
    var fetchedModelsByProvider by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var fetchGeneration by remember { mutableStateOf(0) }
    var selectedLanguage by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var savedKeyProviders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var initialLoaded by remember { mutableStateOf(false) }

    val selectedModel = modelsByProvider[selectedProvider] ?: "whisper-large-v3"
    val fetchedModels = fetchedModelsByProvider[selectedProvider].orEmpty()

    fun fetchModelsForProvider(provider: String = selectedProvider) {
        val currentKey = when (provider) {
            "groq" -> apiKey
            "mistral" -> mistralApiKey
            "custom" -> customApiKey
            else -> ""
        }
        val currentBaseUrl = when (provider) {
            "groq" -> "https://api.groq.com/openai"
            "mistral" -> "https://api.mistral.ai"
            "custom" -> customBaseUrl
            else -> ""
        }
        fetchGeneration++
        val generation = fetchGeneration
        if (currentKey.isBlank() || provider == "custom") {
            return
        }
        coroutineScope.launch {
            val result = GroqClient.fetchModels(baseUrl = currentBaseUrl, apiKey = currentKey)
            result.fold(
                onSuccess = { models ->
                    if (provider != selectedProvider || generation != fetchGeneration) return@fold
                    fetchedModelsByProvider = fetchedModelsByProvider + (provider to models)
                    val currentSelected = modelsByProvider[provider].orEmpty()
                    if (models.isNotEmpty() && currentSelected !in models) {
                        val first = models.first()
                        modelsByProvider = modelsByProvider + (provider to first)
                        SecurityUtils.saveSttModel(context, provider, first)
                    }
                },
                onFailure = {
                    if (provider == selectedProvider && generation == fetchGeneration) {
                        fetchedModelsByProvider = fetchedModelsByProvider - provider
                    }
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val customMod = SecurityUtils.getSttModel(context, "custom")
            SttLoadedState(
                preset = SecurityUtils.getSttPreset(context),
                groqKey = SecurityUtils.getProviderApiKey(context, "stt", "groq") ?: "",
                mistralKey = SecurityUtils.getProviderApiKey(context, "stt", "mistral") ?: "",
                customKey = SecurityUtils.getProviderApiKey(context, "stt", "custom") ?: "",
                customUrl = SecurityUtils.getSttBaseUrl(context, "custom"),
                customModel = customMod,
                modelsByProvider = mapOf(
                    "groq" to SecurityUtils.getSttModel(context, "groq"),
                    "mistral" to SecurityUtils.getSttModel(context, "mistral"),
                    "custom" to customMod
                ),
                language = SecurityUtils.getSttLanguage(context).ifBlank { null }
            )
        }
        selectedProvider = loaded.preset
        apiKey = loaded.groqKey
        mistralApiKey = loaded.mistralKey
        customApiKey = loaded.customKey
        customBaseUrl = loaded.customUrl
        customModel = loaded.customModel
        modelsByProvider = loaded.modelsByProvider
        selectedLanguage = loaded.language
        savedKeyProviders = buildSet {
            if (apiKey.isNotBlank()) add("groq")
            if (mistralApiKey.isNotBlank()) add("mistral")
            if (customApiKey.isNotBlank()) add("custom")
        }
        initialLoaded = true
        fetchModelsForProvider(selectedProvider)
    }

    LaunchedEffect(selectedProvider) {
        if (!initialLoaded) return@LaunchedEffect
        showPassword = false
        testResult = null
        if (fetchedModelsByProvider[selectedProvider].isNullOrEmpty()) {
            fetchModelsForProvider(selectedProvider)
        }
    }

    val languages = listOf(
        null to "Auto-detect",
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "nl" to "Dutch",
        "ja" to "Japanese",
        "zh" to "Chinese",
        "ko" to "Korean",
        "hi" to "Hindi",
        "mr" to "Marathi",
        "pa" to "Punjabi",
        "ar" to "Arabic",
        "ru" to "Russian",
        "hu" to "Hungarian"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .windowInsetsPadding(
                WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = FluenceSpacing.Base)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsTopBar(title = "AI Transcription", onBack = onNavigateBack)

                        // Section 1: Provider & Language
            SettingsSectionHeader(
                title = "Provider & Language",
                description = "Speech recognition engine and transcription language"
            )
            SettingsJointCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FluenceSpacing.Base)
                ) {
                    Text(
                        text = "Provider",
                        color = colors.textPrimary,
                        style = FluenceTypography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FluenceProviderChipsRow(
                        providers = listOf("groq" to "Groq", "mistral" to "Mistral", "custom" to "Custom"),
                        selectedProvider = selectedProvider,
                        onSelect = { id ->
                            selectedProvider = id
                            testResult = null
                            SecurityUtils.saveSttPreset(context, id)
                        }
                    )
                }

                SettingsDivider()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FluenceSpacing.Base)
                ) {
                    Text(
                        text = "Language",
                        color = colors.textPrimary,
                        style = FluenceTypography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Leave as Auto-detect for automatic language detection.",
                        color = colors.textSecondary,
                        style = FluenceTypography.labelMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val currentLanguageLabel = languages.find { it.first == selectedLanguage }?.second ?: "Auto-detect"
                    FluenceDropdownField(
                        selectedLabel = currentLanguageLabel,
                        options = languages,
                        selectedValue = selectedLanguage,
                        onSelect = { code ->
                            selectedLanguage = code
                            SecurityUtils.saveSttLanguage(context, code ?: "")
                        },
                        clickLabel = "Select language",
                        menuWidth = 220.dp
                    )
                }
            }

            // Section 2: Model & Mode
            SettingsSectionHeader(
                title = "Model & Mode",
                description = "Speech recognition model and real-time streaming preferences"
            )
            SettingsJointCard(
                modifier = Modifier.animateContentSize(animationSpec = sizeAnimationSpec)
            ) {
                AnimatedVisibility(
                    visible = selectedProvider != "custom",
                    enter = expandEnter,
                    exit = collapseExit
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(FluenceSpacing.Base)
                        ) {
                            Text(
                                text = "Transcription Model",
                                color = colors.textPrimary,
                                style = FluenceTypography.labelLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Select speech recognition model for this provider.",
                                color = colors.textSecondary,
                                style = FluenceTypography.labelMedium,
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            val availableModels = remember(fetchedModels, selectedProvider, selectedModel) {
                                if (fetchedModels.isNotEmpty()) {
                                    (fetchedModels + listOfNotNull(selectedModel.takeIf { it.isNotBlank() })).distinct()
                                } else if (selectedModel.isNotBlank()) {
                                    listOf(selectedModel)
                                } else {
                                    listOf(SecurityUtils.getSttModel(context, selectedProvider))
                                }
                            }
                            FluenceDropdownField(
                                selectedLabel = selectedModel.ifBlank { availableModels.firstOrNull() ?: "whisper-large-v3" },
                                options = availableModels.map { it to it },
                                selectedValue = selectedModel,
                                onSelect = { m ->
                                    modelsByProvider = modelsByProvider + (selectedProvider to m)
                                    SecurityUtils.saveSttModel(context, selectedProvider, m)
                                },
                                clickLabel = "Select model"
                            )
                        }

                        SettingsDivider()
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FluenceSpacing.Base)
                ) {
                    var isStreamingEnabled by remember { mutableStateOf(SecurityUtils.isStreamingEnabled(context)) }
                    val isStreamingSupported = selectedProvider == "mistral" || selectedProvider == "custom"

                    Text(
                        text = "Transcription Mode",
                        color = colors.textPrimary,
                        style = FluenceTypography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isStreamingSupported) {
                            "Choose between standard post-recording upload or live real-time streaming dictation. Agent Mode works with both and is independent of the transcription mode."
                        } else {
                            "Real-time streaming is not supported by ${selectedProvider.uppercase()}. Standard post-recording mode will be used."
                        },
                        color = colors.textSecondary,
                        style = FluenceTypography.labelMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = !isStreamingEnabled || !isStreamingSupported,
                            onClick = {
                                isStreamingEnabled = false
                                SecurityUtils.saveStreamingEnabled(context, false)
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text("Standard", style = FluenceTypography.bodySmall) },
                            shape = FluenceShapes.ExtraSmall,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.textPrimary.copy(alpha = 0.10f),
                                selectedLabelColor = colors.textPrimary,
                                containerColor = colors.buttonSecondary,
                                labelColor = colors.textSecondary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = colors.inputBorder,
                                selectedBorderColor = colors.selectionBorder,
                                enabled = true,
                                selected = !isStreamingEnabled || !isStreamingSupported
                            )
                        )

                        FilterChip(
                            selected = isStreamingEnabled && isStreamingSupported,
                            enabled = isStreamingSupported,
                            onClick = {
                                isStreamingEnabled = true
                                SecurityUtils.saveStreamingEnabled(context, true)
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text("Real-time Streaming", style = FluenceTypography.bodySmall) },
                            shape = FluenceShapes.ExtraSmall,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = colors.textPrimary.copy(alpha = 0.10f),
                                selectedLabelColor = colors.textPrimary,
                                containerColor = colors.buttonSecondary,
                                labelColor = colors.textSecondary,
                                disabledContainerColor = colors.buttonSecondary.copy(alpha = 0.4f),
                                disabledLabelColor = colors.textDisabled
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = colors.inputBorder,
                                selectedBorderColor = colors.selectionBorder,
                                enabled = isStreamingSupported,
                                selected = isStreamingEnabled && isStreamingSupported
                            )
                        )
                    }

                    AnimatedVisibility(
                        visible = isStreamingEnabled && isStreamingSupported,
                        enter = expandEnter,
                        exit = collapseExit
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Notice: Real-time streaming mode transmits encrypted audio continuously while speaking. Cancelling stops further transmission, but audio already transmitted is processed by the cloud provider.",
                                color = colors.textSecondary.copy(alpha = 0.8f),
                                style = FluenceTypography.labelSmall,
                            )
                            if (selectedProvider == "custom") {
                                Text(
                                    text = "Custom streaming requires a Mistral-compatible realtime transcription endpoint (e.g. a server exposing /v1/audio/transcriptions/realtime).",
                                    color = colors.textSecondary.copy(alpha = 0.8f),
                                    style = FluenceTypography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }

            // Section 3: Credentials & Connection
            SettingsSectionHeader(
                title = "Credentials & Connection",
                description = "Authentication keys and API endpoint configuration"
            )
            SettingsJointCard(
                modifier = Modifier.animateContentSize(animationSpec = sizeAnimationSpec)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FluenceSpacing.Base)
                ) {
                    val keyLabel = when (selectedProvider) {
                        "groq" -> "Groq API Key"
                        "mistral" -> "Mistral API Key"
                        else -> "API Key"
                    }
                    val keyPlaceholder = when (selectedProvider) {
                        "groq" -> "gsk_..."
                        "mistral" -> "9A..."
                        else -> "API Key"
                    }
                    val activeKey = when (selectedProvider) {
                        "groq" -> apiKey
                        "mistral" -> mistralApiKey
                        else -> customApiKey
                    }

                    FluenceApiKeyField(
                        label = keyLabel,
                        value = activeKey,
                        onValueChange = { raw ->
                            val trimmed = raw.trim()
                            when (selectedProvider) {
                                "groq" -> apiKey = trimmed
                                "mistral" -> mistralApiKey = trimmed
                                else -> customApiKey = trimmed
                            }
                        },
                        placeholder = keyPlaceholder,
                        showPassword = showPassword,
                        onTogglePassword = { showPassword = !showPassword },
                        isSaved = selectedProvider in savedKeyProviders
                    )

                    AnimatedVisibility(
                        visible = selectedProvider == "custom",
                        enter = expandEnter,
                        exit = collapseExit
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Spacer(modifier = Modifier.height(FluenceSpacing.Base))

                            OutlinedTextField(
                                value = customBaseUrl,
                                onValueChange = { customBaseUrl = it },
                                placeholder = { Text("https://api.example.com/v1", color = colors.textSecondary) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary,
                                    focusedBorderColor = colors.controlFocusBorder,
                                    unfocusedBorderColor = colors.inputBorder,
                                    focusedContainerColor = colors.inputBg,
                                    unfocusedContainerColor = colors.inputBg,
                                    cursorColor = colors.textPrimary
                                ),
                                shape = FluenceShapes.Medium,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Base URL", color = colors.textSecondary) }
                            )

                            Spacer(modifier = Modifier.height(FluenceSpacing.Base))

                            OutlinedTextField(
                                value = customModel,
                                onValueChange = { customModel = it.trim() },
                                placeholder = { Text("whisper-large-v3", color = colors.textSecondary) },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = colors.textPrimary,
                                    unfocusedTextColor = colors.textPrimary,
                                    focusedBorderColor = colors.controlFocusBorder,
                                    unfocusedBorderColor = colors.inputBorder,
                                    focusedContainerColor = colors.inputBg,
                                    unfocusedContainerColor = colors.inputBg,
                                    cursorColor = colors.textPrimary
                                ),
                                shape = FluenceShapes.Medium,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("Model", color = colors.textSecondary) }
                            )
                        }
                    }

                    SttCredentialActionsRow(
                        onSave = {
                            if (selectedProvider == "custom") {
                                if (!customBaseUrl.startsWith("https://", ignoreCase = true)) {
                                    FeedbackBus.show("Base URL must use HTTPS.")
                                    return@SttCredentialActionsRow
                                }
                                try {
                                    SecurityUtils.saveProviderApiKey(context, "stt", "custom", customApiKey)
                                    SecurityUtils.saveSttBaseUrl(context, "custom", customBaseUrl)
                                    SecurityUtils.saveSttModel(context, "custom", customModel)
                                    savedKeyProviders = if (customApiKey.isNotBlank()) savedKeyProviders + "custom" else savedKeyProviders - "custom"
                                    showPassword = false
                                    FeedbackBus.show("Settings saved")
                                } catch (e: IllegalArgumentException) {
                                    FeedbackBus.show(e.message ?: "Base URL must use https://")
                                }
                            } else {
                                SecurityUtils.saveProviderApiKey(context, "stt", selectedProvider, activeKey)
                                savedKeyProviders = if (activeKey.isNotBlank()) {
                                    savedKeyProviders + selectedProvider
                                } else {
                                    savedKeyProviders - selectedProvider
                                }
                                showPassword = false
                                FeedbackBus.show(if (selectedProvider == "mistral") "Mistral API Key saved" else "API Key saved")
                            }
                        },
                        onTest = {
                            if (selectedProvider == "custom") {
                                if (customApiKey.isBlank() || customBaseUrl.isBlank()) {
                                    FeedbackBus.show("Please enter API Key and Base URL.")
                                    return@SttCredentialActionsRow
                                }
                                if (!customBaseUrl.startsWith("https://", ignoreCase = true)) {
                                    FeedbackBus.show("Base URL must use HTTPS.")
                                    return@SttCredentialActionsRow
                                }
                            } else if (activeKey.isBlank()) {
                                FeedbackBus.show("Please enter a key to test.")
                                return@SttCredentialActionsRow
                            }
                            isTesting = true
                            testResult = null
                            val providerSnapshot = selectedProvider
                            val keySnapshot = activeKey
                            val baseUrlSnapshot = customBaseUrl
                            coroutineScope.launch {
                                val (success, message) = verifyProviderApiKey(
                                    key = keySnapshot,
                                    providerId = providerSnapshot,
                                    baseUrl = if (providerSnapshot == "custom") baseUrlSnapshot else null
                                )
                                isTesting = false
                                testResult = success to message
                            }
                        },
                        isTesting = isTesting,
                        testResult = testResult
                    )
                }
            }

            Spacer(modifier = Modifier.height(FluenceSpacing.Xl))
        }
    }
}
