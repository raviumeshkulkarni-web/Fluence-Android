package com.groq.voicetyper.ui

import com.groq.voicetyper.FeedbackBus
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.GroqClient
import com.groq.voicetyper.ProviderLogo
import com.groq.voicetyper.SecurityUtils
import com.groq.voicetyper.SettingsJointCard
import com.groq.voicetyper.SettingsSectionHeader
import com.groq.voicetyper.SettingsTopBar
import com.groq.voicetyper.pressScale
import com.groq.voicetyper.theme.*
import com.groq.voicetyper.ui.icons.FluenceIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentConfigScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = PrecisionTheme.colors
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedProvider by remember { mutableStateOf("groq") }
    var model by remember { mutableStateOf("llama-3.3-70b-versatile") }
    var apiKey by remember { mutableStateOf("") }
    var customBaseUrl by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<String?>(null) }

    val providerBaseUrl = mapOf(
        "groq" to "https://api.groq.com/openai",
        "mistral" to "https://api.mistral.ai",
        "custom" to ""
    )

    fun currentBaseUrl(): String {
        return if (selectedProvider == "custom") customBaseUrl
        else providerBaseUrl[selectedProvider] ?: ""
    }

    fun fetchModelsForProvider() {
        if (selectedProvider == "custom" || apiKey.isBlank()) {
            fetchedModels = emptyList()
            modelFetchError = null
            return
        }
        isFetchingModels = true
        modelFetchError = null
        coroutineScope.launch {
            val result = GroqClient.fetchModels(
                baseUrl = currentBaseUrl(),
                apiKey = apiKey
            )
            result.fold(
                onSuccess = {
                    fetchedModels = it
                    if (it.isEmpty()) {
                        modelFetchError = "No models returned. Check your API key."
                    } else if (model !in it) {
                        model = it.first()
                    }
                },
                onFailure = {
                    fetchedModels = emptyList()
                    modelFetchError = "Failed to fetch models. Check your API key."
                }
            )
            isFetchingModels = false
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val savedPreset = SecurityUtils.getLlmPreset(context)
            if (savedPreset != selectedProvider) {
                selectedProvider = savedPreset
            }
        }
    }

    LaunchedEffect(selectedProvider) {
        withContext(Dispatchers.IO) {
            apiKey = SecurityUtils.getProviderApiKey(context, "llm", selectedProvider) ?: ""
            model = SecurityUtils.getLlmModel(context, selectedProvider)
            customBaseUrl = SecurityUtils.getLlmBaseUrl(context, "custom")
        }
        testResult = null
        fetchModelsForProvider()
    }

    LaunchedEffect(apiKey) {
        if (apiKey.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(600)
        fetchModelsForProvider()
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "groq" to "Groq",
                            "mistral" to "Mistral",
                            "custom" to "Custom"
                        ).forEach { (value, label) ->
                            FilterChip(
                                selected = selectedProvider == value,
                                onClick = {
                                    selectedProvider = value
                                },
                                modifier = Modifier.heightIn(min = 48.dp),
                                label = { Text(label, style = FluenceTypography.bodySmall) },
                                leadingIcon = {
                                    if (value != "custom") {
                                        ProviderLogo(providerId = value, size = 18.dp)
                                    }
                                },
                                shape = FluenceShapes.ExtraSmall,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = colors.textPrimary.copy(alpha = 0.10f),
                                    selectedLabelColor = colors.textPrimary,
                                    containerColor = colors.buttonSecondary,
                                    labelColor = colors.textSecondary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    borderColor = colors.inputBorder,
                                    selectedBorderColor = if (colors.isLight) colors.brandCyan else colors.textPrimary.copy(alpha = 0.50f),
                                    enabled = true,
                                    selected = selectedProvider == value
                                )
                            )
                        }
                    }
                }

                HorizontalDivider(color = colors.divider, thickness = 1.dp)

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

                    if (selectedProvider == "custom") {
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            placeholder = { Text("e.g. llama-3.3-70b-versatile", color = colors.textSecondary) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = colors.textPrimary,
                                unfocusedTextColor = colors.textPrimary,
                                focusedBorderColor = if (colors.isLight) colors.brandCyan else colors.textPrimary,
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
                                focusedBorderColor = if (colors.isLight) colors.brandCyan else colors.textPrimary,
                                unfocusedBorderColor = colors.inputBorder,
                                focusedContainerColor = colors.inputBg,
                                unfocusedContainerColor = colors.inputBg,
                                cursorColor = colors.textPrimary
                            ),
                            shape = FluenceShapes.Medium,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    } else {
                        if (isFetchingModels) {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(color = colors.textSecondary, modifier = Modifier.size(20.dp), strokeWidth = 1.5.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Fetching models…", color = colors.textSecondary, style = FluenceTypography.bodySmall)
                            }
                        } else {
                            var showModelDropdown by remember { mutableStateOf(false) }

                            val agentTriggerInteraction = remember { MutableInteractionSource() }
                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .pressScale(agentTriggerInteraction)
                                        .clickable(
                                            interactionSource = agentTriggerInteraction,
                                            indication = LocalIndication.current,
                                            role = Role.Button,
                                            onClickLabel = "Select model",
                                            onClick = { showModelDropdown = true }
                                        )
                                        .border(1.dp, colors.inputBorder, FluenceShapes.Medium)
                                        .background(colors.inputBg, FluenceShapes.Medium)
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = model.ifBlank { "Select a model" },
                                        color = if (model.isBlank()) colors.textDisabled else colors.textPrimary,
                                        style = FluenceTypography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Icon(
                                        imageVector = FluenceIcons.ChevronDown,
                                        contentDescription = "Select model",
                                        tint = colors.textSecondary
                                    )
                                }
                                DropdownMenu(
                                    expanded = showModelDropdown,
                                    onDismissRequest = { showModelDropdown = false },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 280.dp)
                                        .background(colors.dialog, FluenceShapes.Medium)
                                        .border(1.dp, colors.outlineSubtle, FluenceShapes.Medium)
                                ) {
                                    fetchedModels.forEach { m ->
                                        val isSelected = m == model
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = m,
                                                    color = colors.textPrimary,
                                                    style = FluenceTypography.bodyLarge
                                                )
                                            },
                                            colors = MenuDefaults.itemColors(
                                                textColor = colors.textPrimary,
                                                leadingIconColor = colors.textSecondary,
                                                trailingIconColor = colors.textSecondary
                                            ),
                                            modifier = if (isSelected) Modifier
                                                .background(colors.textPrimary.copy(alpha = 0.10f), FluenceShapes.Small)
                                            else Modifier,
                                            onClick = {
                                                model = m
                                                showModelDropdown = false
                                                testResult = null
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        modelFetchError?.let { err ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(text = err, color = colors.error, style = FluenceTypography.labelMedium)
                        }
                    }
                }
            }

            SettingsSectionHeader(
                title = "Authentication & Endpoint",
                description = "API keys and endpoint configuration"
            )

            SettingsJointCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(FluenceSpacing.Base)
                ) {
                    Text(
                        text = "API Key",
                        color = colors.textPrimary,
                        style = FluenceTypography.labelLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Stored securely on this device.",
                        color = colors.textSecondary,
                        style = FluenceTypography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                        },
                        placeholder = { Text("Enter your API key", color = colors.textSecondary) },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedBorderColor = if (colors.isLight) colors.brandCyan else colors.textPrimary,
                            unfocusedBorderColor = colors.inputBorder,
                            focusedContainerColor = colors.inputBg,
                            unfocusedContainerColor = colors.inputBg,
                            cursorColor = colors.textPrimary
                        ),
                        shape = FluenceShapes.Medium,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(
                                    text = if (showPassword) "Hide" else "Show",
                                    color = colors.textSecondary,
                                    style = FluenceTypography.labelMedium
                                )
                            }
                        }
                    )
                }

                HorizontalDivider(color = colors.divider, thickness = 1.dp)

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
                                coroutineScope.launch {
                                    val baseUrl = currentBaseUrl()
                                    val testUrl = "${baseUrl.trimEnd('/')}/v1/models"
                                    val result = withContext(Dispatchers.IO) {
                                        try {
                                            val client = OkHttpClient()
                                            val request = Request.Builder()
                                                .url(testUrl)
                                                .header("Authorization", "Bearer $apiKey")
                                                .build()
                                            client.newCall(request).execute().use { response ->
                                                if (response.isSuccessful) {
                                                    true to "Connection successful!"
                                                } else {
                                                    false to "Verification failed (HTTP ${response.code})."
                                                }
                                            }
                                        } catch (e: Exception) {
                                            false to "Connection error. Please check your network and settings."
                                        }
                                    }
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
                            color = if (result.first) colors.success else colors.error,
                            style = FluenceTypography.bodySmall
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
