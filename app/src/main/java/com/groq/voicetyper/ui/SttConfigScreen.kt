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

@Composable
private fun ApiKeySection(
    label: String,
    placeholder: String,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    isTesting: Boolean,
    testResult: Pair<Boolean, String>?
) {
    Text(
        text = label,
        color = TextPrimary,
        style = FluenceTypography.labelLarge
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Stored securely on this device.",
        color = TextSecondary,
        style = FluenceTypography.labelMedium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        placeholder = { Text(placeholder, color = TextSecondary) },
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
            TextButton(onClick = onTogglePassword) {
                Text(
                    text = if (showPassword) "Hide" else "Show",
                    color = TextSecondary,
                    style = FluenceTypography.labelMedium
                )
            }
        }
    )

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onSave,
            colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
            shape = FluenceShapes.Medium,
            modifier = Modifier.weight(1f).pressScale(remember { MutableInteractionSource() })
        ) {
            Text(text = "Save", color = TextPrimary, style = FluenceTypography.labelLarge)
        }

        Button(
            onClick = onTest,
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
            style = FluenceTypography.bodySmall,
        )
    }
}

private suspend fun verifyApiKey(key: String, providerId: String = "groq", baseUrl: String? = null): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    val (url, headerName, headerValue) = when (providerId) {
        "mistral" -> Triple("https://api.mistral.ai/v1/models", "x-api-key", key)
        "custom" -> Triple("${baseUrl?.trimEnd('/') ?: ""}/models", "Authorization", "Bearer $key")
        else -> Triple("https://api.groq.com/openai/v1/models", "Authorization", "Bearer $key")
    }
    try {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .header(headerName, headerValue)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                true to if (providerId == "custom") "Connection successful!" else "Connection successful! Key is valid."
            } else {
                false to "Verification failed (HTTP ${response.code}). Check your key."
            }
        }
    } catch (e: Exception) {
        false to "Connection error. Please check your network and settings."
    }
}

@Composable
fun SttConfigScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedProvider by remember { mutableStateOf(SecurityUtils.getSttPreset(context)) }
    var apiKey by remember { mutableStateOf("") }
    var mistralApiKey by remember { mutableStateOf("") }
    var customApiKey by remember { mutableStateOf("") }
    var customBaseUrl by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf(SecurityUtils.getSttModel(context, selectedProvider)) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var selectedLanguage by remember { mutableStateOf(SecurityUtils.getSttLanguage(context).ifBlank { null }) }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var showPassword by remember { mutableStateOf(false) }

    fun fetchModelsForProvider() {
        val currentKey = when (selectedProvider) {
            "groq" -> apiKey
            "mistral" -> mistralApiKey
            "custom" -> customApiKey
            else -> ""
        }
        val currentBaseUrl = when (selectedProvider) {
            "groq" -> "https://api.groq.com/openai"
            "mistral" -> "https://api.mistral.ai"
            "custom" -> customBaseUrl
            else -> ""
        }
        if (currentKey.isBlank() || selectedProvider == "custom") {
            fetchedModels = emptyList()
            return
        }
        isFetchingModels = true
        coroutineScope.launch {
            val result = GroqClient.fetchModels(baseUrl = currentBaseUrl, apiKey = currentKey)
            result.fold(
                onSuccess = { models ->
                    fetchedModels = models
                    if (models.isNotEmpty() && selectedModel !in models) {
                        selectedModel = models.first()
                        SecurityUtils.saveSttModel(context, selectedProvider, selectedModel)
                    }
                },
                onFailure = {
                    fetchedModels = emptyList()
                }
            )
            isFetchingModels = false
        }
    }

    LaunchedEffect(selectedProvider) {
        withContext(Dispatchers.IO) {
            selectedProvider = SecurityUtils.getSttPreset(context)
            apiKey = SecurityUtils.getProviderApiKey(context, "stt", "groq") ?: ""
            mistralApiKey = SecurityUtils.getProviderApiKey(context, "stt", "mistral") ?: ""
            customApiKey = SecurityUtils.getProviderApiKey(context, "stt", "custom") ?: ""
            customBaseUrl = SecurityUtils.getSttBaseUrl(context, "custom")
            customModel = SecurityUtils.getSttModel(context, "custom")
            selectedModel = SecurityUtils.getSttModel(context, selectedProvider)
        }
        testResult = null
        fetchModelsForProvider()
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
            SettingsTopBar(title = "AI Transcription", onBack = onNavigateBack)

            Spacer(modifier = Modifier.height(16.dp))

            // Provider
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
                listOf("groq" to "Groq", "mistral" to "Mistral", "custom" to "Custom").forEach { (id, label) ->
                    FilterChip(
                        selected = selectedProvider == id,
                        onClick = {
                            selectedProvider = id
                            testResult = null
                            SecurityUtils.saveSttPreset(context, id)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = { Text(label, style = FluenceTypography.bodySmall) },
                        leadingIcon = {
                            if (id != "custom") {
                                ProviderLogo(providerId = id, size = 18.dp)
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
                            selectedBorderColor = TextPrimary.copy(alpha = 0.30f),
                            enabled = true,
                            selected = selectedProvider == id
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Language
            Text(
                text = "Language",
                color = TextPrimary,
    style = FluenceTypography.labelLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Leave as Auto-detect for automatic language detection.",
                color = TextSecondary,
                style = FluenceTypography.labelMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            var showLanguageDropdown by remember { mutableStateOf(false) }
            val currentLanguageLabel = languages.find { it.first == selectedLanguage }?.second ?: "Auto-detect"

            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLanguageDropdown = true }
                        .border(1.dp, OutlineSubtle, FluenceShapes.Medium)
                        .background(InputBg, FluenceShapes.Medium)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = currentLanguageLabel,
                        color = TextPrimary,
                        style = FluenceTypography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box {
                        Icon(
                            imageVector = FluenceIcons.ChevronDown,
                            contentDescription = "Select language",
                            tint = TextSecondary
                        )
                        DropdownMenu(
                            expanded = showLanguageDropdown,
                            onDismissRequest = { showLanguageDropdown = false },
                            modifier = Modifier
                                .width(220.dp)
                                .heightIn(max = 280.dp)
                                .background(DialogSurface, FluenceShapes.Medium)
                                .border(1.dp, OutlineSubtle, FluenceShapes.Medium)
                        ) {
                            languages.forEach { (code, name) ->
                                val isSelected = code == selectedLanguage
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = name,
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
                                        selectedLanguage = code
                                        SecurityUtils.saveSttLanguage(context, code ?: "")
                                        showLanguageDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Transcription Model
            Text(
                text = "Transcription Model",
                color = TextPrimary,
    style = FluenceTypography.labelLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Select speech recognition model for this provider.",
                color = TextSecondary,
                style = FluenceTypography.labelMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (isFetchingModels) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = TextSecondary, modifier = Modifier.size(20.dp), strokeWidth = 1.5.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Fetching models…", color = TextSecondary, style = FluenceTypography.bodySmall)
                }
            } else {
                var showModelDropdown by remember { mutableStateOf(false) }
                val availableModels = remember(fetchedModels, selectedProvider, selectedModel) {
                    if (fetchedModels.isNotEmpty()) fetchedModels
                    else if (selectedModel.isNotBlank()) listOf(selectedModel)
                    else listOf(SecurityUtils.getSttModel(context, selectedProvider))
                }

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
                        text = selectedModel.ifBlank { availableModels.firstOrNull() ?: "whisper-large-v3" },
                        color = TextPrimary,
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
                        availableModels.forEach { m ->
                            val isSelected = m == selectedModel
                            DropdownMenuItem(
                                text = { Text(text = m, color = TextPrimary) },
                                colors = MenuDefaults.itemColors(
                                    textColor = TextPrimary,
                                    leadingIconColor = TextSecondary,
                                    trailingIconColor = TextSecondary
                                ),
                                modifier = if (isSelected) Modifier
                                    .background(TextPrimary.copy(alpha = 0.10f), FluenceShapes.Small)
                                else Modifier,
                                onClick = {
                                    selectedModel = m
                                    SecurityUtils.saveSttModel(context, selectedProvider, m)
                                    showModelDropdown = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            var isStreamingEnabled by remember { mutableStateOf(SecurityUtils.isStreamingEnabled(context)) }
            val isStreamingSupported = selectedProvider == "mistral" || selectedProvider == "custom"

            // Transcription Mode
            Text(
                text = "Transcription Mode",
                color = TextPrimary,
    style = FluenceTypography.labelLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isStreamingSupported) {
                    "Choose between standard post-recording upload or live real-time streaming dictation. Agent Mode works with both — it is independent of the transcription mode."
                } else {
                    "Real-time streaming is not supported by ${selectedProvider.uppercase()}. Standard post-recording mode will be used."
                },
                color = TextSecondary,
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
                        selectedContainerColor = TextPrimary.copy(alpha = 0.10f),
                        selectedLabelColor = TextPrimary,
                        containerColor = ButtonSecondary,
                        labelColor = TextSecondary
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
                        selectedContainerColor = TextPrimary.copy(alpha = 0.10f),
                        selectedLabelColor = TextPrimary,
                        containerColor = ButtonSecondary,
                        labelColor = TextSecondary,
                        disabledContainerColor = ButtonSecondary.copy(alpha = 0.4f),
                        disabledLabelColor = TextDisabled
                    )
                )
            }

            if (isStreamingEnabled && isStreamingSupported) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Notice: Real-time streaming mode transmits encrypted audio continuously while speaking. Cancelling stops further transmission, but audio already transmitted is processed by the cloud provider.",
                    color = TextSecondary.copy(alpha = 0.8f),
                    style = FluenceTypography.labelSmall,
                )
                if (selectedProvider == "custom") {
                    Text(
                        text = "Custom streaming requires a Mistral-compatible realtime transcription endpoint (e.g. a server exposing /v1/audio/transcriptions/realtime).",
                        color = TextSecondary.copy(alpha = 0.8f),
                        style = FluenceTypography.labelSmall,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // API Key section
            if (selectedProvider == "groq") {

                ApiKeySection(
                    label = "Groq API Key",
                    placeholder = "gsk_...",
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it.trim() },
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword },
                    onSave = {
                        SecurityUtils.saveProviderApiKey(context, "stt", "groq", apiKey)
                        FeedbackBus.show("API Key saved")
                    },
                    onTest = {
                        if (apiKey.isBlank()) {
                            FeedbackBus.show("Please enter a key to test.")
                            return@ApiKeySection
                        }
                        isTesting = true
                        testResult = null
                        coroutineScope.launch {
                            val (success, message) = verifyApiKey(apiKey, "groq")
                            isTesting = false
                            testResult = success to message
                        }
                    },
                    isTesting = isTesting,
                    testResult = testResult
                )
            }

            if (selectedProvider == "mistral") {
                ApiKeySection(
                    label = "Mistral API Key",
                    placeholder = "9A...",
                    apiKey = mistralApiKey,
                    onApiKeyChange = { mistralApiKey = it.trim() },
                    showPassword = showPassword,
                    onTogglePassword = { showPassword = !showPassword },
                    onSave = {
                        SecurityUtils.saveProviderApiKey(context, "stt", "mistral", mistralApiKey)
                        FeedbackBus.show("Mistral API Key saved")
                    },
                    onTest = {
                        if (mistralApiKey.isBlank()) {
                            FeedbackBus.show("Please enter a key to test.")
                            return@ApiKeySection
                        }
                        isTesting = true
                        testResult = null
                        coroutineScope.launch {
                            val (success, message) = verifyApiKey(mistralApiKey, "mistral")
                            isTesting = false
                            testResult = success to message
                        }
                    },
                    isTesting = isTesting,
                    testResult = testResult
                )
            }

            if (selectedProvider == "custom") {
                Text(
                    text = "API Key",
                    color = TextPrimary,
                    style = FluenceTypography.labelLarge
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Stored securely on this device.",
                    color = TextSecondary,
                    style = FluenceTypography.labelMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = customApiKey,
                    onValueChange = { customApiKey = it.trim() },
                    placeholder = { Text("API Key", color = TextSecondary) },
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

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = customBaseUrl,
                    onValueChange = { customBaseUrl = it },
                    placeholder = { Text("https://api.example.com/v1", color = TextSecondary) },
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
                    label = { Text("Base URL", color = TextSecondary) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = customModel,
                    onValueChange = { customModel = it.trim() },
                    placeholder = { Text("whisper-large-v3", color = TextSecondary) },
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
                    label = { Text("Model", color = TextSecondary) }
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (!customBaseUrl.startsWith("https://", ignoreCase = true)) {
                                FeedbackBus.show("Base URL must use HTTPS.")
                                return@Button
                            }
                            try {
                                SecurityUtils.saveProviderApiKey(context, "stt", "custom", customApiKey)
                                SecurityUtils.saveSttBaseUrl(context, "custom", customBaseUrl)
                                SecurityUtils.saveSttModel(context, "custom", customModel)
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
                            if (customApiKey.isBlank() || customBaseUrl.isBlank()) {
                                FeedbackBus.show("Please enter API Key and Base URL.")
                                return@Button
                            }
                            if (!customBaseUrl.startsWith("https://", ignoreCase = true)) {
                                FeedbackBus.show("Base URL must use HTTPS.")
                                return@Button
                            }
                            isTesting = true
                            testResult = null
                            coroutineScope.launch {
                                val (success, message) = verifyApiKey(customApiKey, "custom", customBaseUrl)
                                isTesting = false
                                testResult = success to message
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
                            Text(text = "Test Connection", color = TextPrimary)
                        }
                    }
                }

                testResult?.let { result ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = result.second,
                        color = if (result.first) Success else Error,
                        style = FluenceTypography.bodySmall,
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
