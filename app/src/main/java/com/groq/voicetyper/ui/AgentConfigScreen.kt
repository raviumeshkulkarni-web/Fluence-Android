package com.groq.voicetyper.ui

import com.groq.voicetyper.FeedbackBus
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AgentLoadedState(
    val preset: String,
    val keysByProvider: Map<String, String>,
    val modelsByProvider: Map<String, String>,
    val customBaseUrl: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConfigScreen(
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

    var selectedProvider by remember { mutableStateOf("groq") }
    var apiKeysByProvider by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var modelsByProvider by remember {
        mutableStateOf(
            mapOf(
                "groq" to "llama-3.3-70b-versatile",
                "mistral" to "mistral-large-latest",
                "custom" to ""
            )
        )
    }
    var customBaseUrl by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var fetchedModelsByProvider by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var modelFetchErrorsByProvider by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var fetchGeneration by remember { mutableStateOf(0) }
    var lastFetchedKeyByProvider by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var savedKeyProviders by remember { mutableStateOf<Set<String>>(emptySet()) }
    var initialLoaded by remember { mutableStateOf(false) }

    val apiKey = apiKeysByProvider[selectedProvider].orEmpty()
    val model = modelsByProvider[selectedProvider].orEmpty()
    val hasSavedKey = selectedProvider in savedKeyProviders
    val fetchedModels = fetchedModelsByProvider[selectedProvider].orEmpty()
    val modelFetchError = modelFetchErrorsByProvider[selectedProvider]

    val providerBaseUrl = mapOf(
        "groq" to "https://api.groq.com/openai",
        "mistral" to "https://api.mistral.ai",
        "custom" to ""
    )

    fun currentBaseUrl(provider: String = selectedProvider): String {
        return if (provider == "custom") customBaseUrl
        else providerBaseUrl[provider] ?: ""
    }

    fun fetchModelsForProvider(provider: String = selectedProvider) {
        val currentKey = apiKeysByProvider[provider].orEmpty()
        val currentUrl = currentBaseUrl(provider)
        fetchGeneration++
        val gen = fetchGeneration
        if (provider == "custom" || currentKey.isBlank()) {
            modelFetchErrorsByProvider = modelFetchErrorsByProvider - provider
            return
        }
        lastFetchedKeyByProvider = lastFetchedKeyByProvider + (provider to currentKey)
        modelFetchErrorsByProvider = modelFetchErrorsByProvider - provider
        coroutineScope.launch {
            val result = GroqClient.fetchModels(
                baseUrl = currentUrl,
                apiKey = currentKey
            )
            if (provider != selectedProvider || gen != fetchGeneration) {
                return@launch
            }
            result.fold(
                onSuccess = { models ->
                    fetchedModelsByProvider = fetchedModelsByProvider + (provider to models)
                    if (models.isEmpty()) {
                        modelFetchErrorsByProvider = modelFetchErrorsByProvider + (provider to "No models returned. Check your API key.")
                    } else {
                        modelFetchErrorsByProvider = modelFetchErrorsByProvider - provider
                        val currentModel = modelsByProvider[provider].orEmpty()
                        if (currentModel !in models) {
                            modelsByProvider = modelsByProvider + (provider to models.first())
                        }
                    }
                },
                onFailure = {
                    fetchedModelsByProvider = fetchedModelsByProvider - provider
                    modelFetchErrorsByProvider = modelFetchErrorsByProvider + (provider to "Failed to fetch models. Check your API key.")
                }
            )
        }
    }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val providers = listOf("groq", "mistral", "custom")
            AgentLoadedState(
                preset = SecurityUtils.getLlmPreset(context),
                keysByProvider = providers.associateWith { p ->
                    SecurityUtils.getProviderApiKey(context, "llm", p) ?: ""
                },
                modelsByProvider = providers.associateWith { p ->
                    SecurityUtils.getLlmModel(context, p)
                },
                customBaseUrl = SecurityUtils.getLlmBaseUrl(context, "custom")
            )
        }
        selectedProvider = loaded.preset
        apiKeysByProvider = loaded.keysByProvider
        modelsByProvider = loaded.modelsByProvider
        customBaseUrl = loaded.customBaseUrl
        savedKeyProviders = loaded.keysByProvider.filterValues { it.isNotBlank() }.keys
        lastFetchedKeyByProvider = loaded.keysByProvider
        initialLoaded = true
        fetchModelsForProvider(selectedProvider)
    }

    LaunchedEffect(selectedProvider) {
        if (!initialLoaded) return@LaunchedEffect
        showPassword = false
        testResult = null
        if (fetchedModelsByProvider[selectedProvider].isNullOrEmpty() && apiKey.isNotBlank()) {
            fetchModelsForProvider(selectedProvider)
        }
    }

    LaunchedEffect(apiKey, selectedProvider) {
        if (!initialLoaded) return@LaunchedEffect
        if (apiKey.isBlank() || apiKey == lastFetchedKeyByProvider[selectedProvider]) return@LaunchedEffect
        delay(600)
        fetchModelsForProvider(selectedProvider)
    }

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
            SettingsTopBar(title = "AI Agent Mode", onBack = onNavigateBack)

            Spacer(modifier = Modifier.height(FluenceSpacing.Sm))

            Text(
                text = "Configure an AI provider for agent transcription mode.",
                color = colors.textSecondary,
                style = FluenceTypography.labelLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )

            SettingsSectionHeader(
                title = "Provider & Model",
                description = "Select language model provider and model architecture"
            )

            SettingsJointCard(
                modifier = Modifier.animateContentSize(animationSpec = sizeAnimationSpec)
            ) {
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
                        providers = listOf(
                            "groq" to "Groq",
                            "mistral" to "Mistral",
                            "custom" to "Custom"
                        ),
                        selectedProvider = selectedProvider,
                        onSelect = { selectedProvider = it }
                    )
                }

                SettingsDivider()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FluenceSpacing.Base)
                ) {
                    Text(
                        text = "Model",
                        color = colors.textPrimary,
                        style = FluenceTypography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    AnimatedContent(
                        targetState = selectedProvider == "custom",
                        transitionSpec = {
                            if (reducedMotion) {
                                ContentTransform(EnterTransition.None, ExitTransition.None)
                            } else {
                                fadeIn(tween(FluenceMotion.durationStructural, easing = FastOutSlowInEasing)) togetherWith
                                    fadeOut(tween(FluenceMotion.durationImmediate, easing = FastOutSlowInEasing))
                            }
                        },
                        label = "agent_model_section"
                    ) { isCustom ->
                        if (isCustom) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = model,
                                    onValueChange = { updated ->
                                        modelsByProvider = modelsByProvider + ("custom" to updated)
                                    },
                                    placeholder = { Text("e.g. llama-3.3-70b-versatile", color = colors.textSecondary) },
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
                                    singleLine = true
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                Text(
                                    text = "Base URL",
                                    color = colors.textPrimary,
                                    style = FluenceTypography.labelLarge
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                OutlinedTextField(
                                    value = customBaseUrl,
                                    onValueChange = { customBaseUrl = it },
                                    placeholder = { Text("e.g. https://api.example.com", color = colors.textSecondary) },
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
                                    singleLine = true
                                )
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                val availableModels = remember(fetchedModels, model) {
                                    if (fetchedModels.isNotEmpty()) {
                                        (fetchedModels + listOfNotNull(model.takeIf { it.isNotBlank() })).distinct()
                                    } else {
                                        listOfNotNull(model.takeIf { it.isNotBlank() })
                                    }
                                }

                                FluenceDropdownField(
                                    selectedLabel = model,
                                    options = availableModels.map { it to it },
                                    selectedValue = model,
                                    onSelect = { m ->
                                        modelsByProvider = modelsByProvider + (selectedProvider to m)
                                        testResult = null
                                    },
                                    clickLabel = "Select model",
                                    placeholderText = "Select a model",
                                    isPlaceholder = model.isBlank()
                                )

                                modelFetchError?.let { err ->
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(text = err, color = colors.errorText, style = FluenceTypography.labelMedium)
                                }
                            }
                        }
                    }
                }
            }

            SettingsSectionHeader(
                title = "Authentication & Endpoint",
                description = "API keys and endpoint configuration"
            )

            SettingsJointCard(
                modifier = Modifier.animateContentSize(animationSpec = sizeAnimationSpec)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FluenceSpacing.Base)
                ) {
                    FluenceApiKeyField(
                        label = "API Key",
                        value = apiKey,
                        onValueChange = { updated ->
                            apiKeysByProvider = apiKeysByProvider + (selectedProvider to updated)
                        },
                        placeholder = "Enter your API key",
                        showPassword = showPassword,
                        onTogglePassword = { showPassword = !showPassword },
                        isSaved = hasSavedKey
                    )
                }

                SettingsDivider()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FluenceSpacing.Base)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val agentSaveInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = {
                                if (selectedProvider == "custom" && !customBaseUrl.startsWith("https://", ignoreCase = true)) {
                                    FeedbackBus.show("Base URL must use HTTPS.")
                                    return@Button
                                }
                                try {
                                    SecurityUtils.saveProviderApiKey(context, "llm", selectedProvider, apiKey)
                                    SecurityUtils.saveLlmPreset(context, selectedProvider)
                                    SecurityUtils.saveLlmModel(context, selectedProvider, model)
                                    if (selectedProvider == "custom") {
                                        SecurityUtils.saveLlmBaseUrl(context, "custom", customBaseUrl)
                                    }
                                    savedKeyProviders = if (apiKey.isNotBlank()) {
                                        savedKeyProviders + selectedProvider
                                    } else {
                                        savedKeyProviders - selectedProvider
                                    }
                                    showPassword = false
                                    FeedbackBus.show("Settings saved")
                                } catch (e: IllegalArgumentException) {
                                    FeedbackBus.show(e.message ?: "Base URL must use https://")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.buttonSecondary),
                            shape = FluenceShapes.Medium,
                            interactionSource = agentSaveInteraction,
                            modifier = Modifier.weight(1f).pressScale(agentSaveInteraction)
                        ) {
                            Text(text = "Save", color = colors.textPrimary, style = FluenceTypography.labelLarge)
                        }

                        val agentTestInteraction = remember { MutableInteractionSource() }
                        Button(
                            onClick = {
                                if (apiKey.isBlank()) {
                                    FeedbackBus.show("Please enter an API key.")
                                    return@Button
                                }
                                if (selectedProvider == "custom" && !customBaseUrl.startsWith("https://", ignoreCase = true)) {
                                    FeedbackBus.show("Base URL must use HTTPS.")
                                    return@Button
                                }
                                isTesting = true
                                testResult = null
                                val providerSnapshot = selectedProvider
                                val keySnapshot = apiKey
                                val baseUrlSnapshot = customBaseUrl
                                coroutineScope.launch {
                                    val result = verifyProviderApiKey(keySnapshot, providerSnapshot, baseUrlSnapshot)
                                    isTesting = false
                                    testResult = result
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.buttonSecondary),
                            shape = FluenceShapes.Medium,
                            interactionSource = agentTestInteraction,
                            modifier = Modifier.weight(1f).pressScale(agentTestInteraction),
                            enabled = !isTesting
                        ) {
                            if (isTesting) {
                                CircularProgressIndicator(color = colors.textPrimary, modifier = Modifier.size(16.dp), strokeWidth = 1.5.dp)
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
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
