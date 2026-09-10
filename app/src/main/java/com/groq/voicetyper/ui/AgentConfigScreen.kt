package com.groq.voicetyper.ui

import com.groq.voicetyper.FeedbackBus
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.groq.voicetyper.GroqClient
import com.groq.voicetyper.ProviderLogo
import com.groq.voicetyper.SecurityUtils
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
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedProvider by remember { mutableStateOf(SecurityUtils.getLlmPreset(context)) }
    var model by remember { mutableStateOf(SecurityUtils.getLlmModel(context, SecurityUtils.getLlmPreset(context))) }
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
            .background(Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsTopBar(title = "AI Agent Mode", onBack = onNavigateBack)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Configure an AI provider for agent transcription mode.",
                color = TextSecondary,
            style = FluenceTypography.labelLarge
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Provider",
                color = TextPrimary,
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
                            selectedContainerColor = TextPrimary.copy(alpha = 0.10f),
                            selectedLabelColor = TextPrimary,
                            containerColor = ButtonSecondary,
                            labelColor = TextSecondary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = OutlineSubtle,
                            selectedBorderColor = TextPrimary.copy(alpha = 0.25f),
                            enabled = true,
                            selected = selectedProvider == value
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "API Key",
                color = TextPrimary,
            style = FluenceTypography.labelLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Stored securely on this device.",
                color = TextSecondary,
                style = FluenceTypography.labelMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = apiKey,
                onValueChange = {
                    apiKey = it
                },
                placeholder = { Text("Enter your API key", color = TextSecondary) },
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = TextSecondary,
                    unfocusedBorderColor = OutlineSubtle,
                    focusedContainerColor = InputBg,
                    unfocusedContainerColor = InputBg,
                    cursorColor = TextPrimary
                ),
                shape = FluenceShapes.Medium,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(
                            text = if (showPassword) "Hide" else "Show",
                            color = TextSecondary,
                            style = FluenceTypography.labelMedium
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Model",
                color = TextPrimary,
            style = FluenceTypography.labelLarge
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (selectedProvider == "custom") {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    placeholder = { Text("e.g. llama-3.3-70b-versatile", color = TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = TextSecondary,
                        unfocusedBorderColor = OutlineSubtle,
                        focusedContainerColor = InputBg,
                        unfocusedContainerColor = InputBg,
                        cursorColor = TextPrimary
                    ),
                    shape = FluenceShapes.Medium,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Base URL",
                    color = TextPrimary,
                    style = FluenceTypography.labelLarge
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = customBaseUrl,
                    onValueChange = { customBaseUrl = it },
                    placeholder = { Text("e.g. https://api.example.com", color = TextSecondary) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = TextSecondary,
                        unfocusedBorderColor = OutlineSubtle,
                        focusedContainerColor = InputBg,
                        unfocusedContainerColor = InputBg,
                        cursorColor = TextPrimary
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
                        CircularProgressIndicator(color = TextSecondary, modifier = Modifier.size(20.dp), strokeWidth = 1.5.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Fetching models…", color = TextSecondary, style = FluenceTypography.bodySmall)
                    }
                } else {
                    var showModelDropdown by remember { mutableStateOf(false) }

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                        .clickable { showModelDropdown = true }
                                .border(1.dp, OutlineSubtle, FluenceShapes.Medium)
                                .background(InputBg, FluenceShapes.Medium)
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = model.ifBlank { "Select a model" },
                                color = if (model.isBlank()) TextDisabled else TextPrimary,
                                style = FluenceTypography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(
                                imageVector = FluenceIcons.ChevronDown,
                                contentDescription = "Select model",
                                tint = TextSecondary
                            )
                        }
                        DropdownMenu(
                            expanded = showModelDropdown,
                            onDismissRequest = { showModelDropdown = false },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .background(DialogSurface, FluenceShapes.Medium)
                                .border(1.dp, OutlineSubtle, FluenceShapes.Medium)
                        ) {
                            fetchedModels.forEach { m ->
                                val isSelected = m == model
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = m,
                                            color = TextPrimary,
                                            style = FluenceTypography.bodyLarge
                                        )
                                    },
                                    colors = MenuDefaults.itemColors(
                                        textColor = TextPrimary,
                                        leadingIconColor = TextSecondary,
                                        trailingIconColor = TextSecondary
                                    ),
                                    modifier = if (isSelected) Modifier
                                        .background(TextPrimary.copy(alpha = 0.10f), FluenceShapes.Small)
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
                    Text(text = err, color = Error, style = FluenceTypography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                    shape = FluenceShapes.Medium,
                    modifier = Modifier.weight(1f).pressScale(remember { MutableInteractionSource() })
                ) {
                    Text(text = "Save", color = TextPrimary, style = FluenceTypography.labelLarge)
                }

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
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                    shape = FluenceShapes.Medium,
                    modifier = Modifier.weight(1f).pressScale(remember { MutableInteractionSource() }),
                    enabled = !isTesting
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(color = TextPrimary, modifier = Modifier.size(16.dp), strokeWidth = 1.5.dp)
                    } else {
                        Text(text = "Test Connection", color = TextPrimary, style = FluenceTypography.labelLarge)
                    }
                }
            }

            testResult?.let { result ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = result.second,
                    color = if (result.first) Success else Error,
                    style = FluenceTypography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
