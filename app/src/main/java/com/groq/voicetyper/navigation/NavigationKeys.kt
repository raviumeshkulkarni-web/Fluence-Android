package com.groq.voicetyper.navigation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.snapshots.SnapshotStateList
 
sealed interface Screen {
    data object Home : Screen
    data object History : Screen
    data object SettingsHub : Screen
    data object SttConfig : Screen
    data object AgentConfig : Screen
    data object OfflineConfig : Screen
    data object Permissions : Screen
    data object PrivacyExclusions : Screen
    data object Formatting : Screen
    data object BubbleSettings : Screen
    data object CustomDictionary : Screen
    data object Snippets : Screen
    data object SyncConfig : Screen
    data object About : Screen
    data class TranscriptionDetail(val entryId: Long) : Screen
    data class BucketPicker(val bucket: String) : Screen
    data object AiCleanupStyles : Screen
    data class AiStylePicker(val styleId: String) : Screen
    data object Agents : Screen
}

// Screen isn't itself Saveable, so the navigation back stack is persisted
// as stable string codes per entry. This keeps the user's place across
// configuration changes (rotation) instead of resetting to Home.
// ─────────────────────────────────────────────────────────────────────
private fun encodeScreen(screen: Screen): String = when (screen) {
    Screen.Home -> "home"
    Screen.History -> "history"
    Screen.SettingsHub -> "settings"
    Screen.SttConfig -> "stt_config"
    Screen.AgentConfig -> "agent_config"
    Screen.OfflineConfig -> "offline_config"
    Screen.Permissions -> "permissions"
    Screen.PrivacyExclusions -> "privacy_exclusions"
    Screen.Formatting -> "formatting"
    Screen.BubbleSettings -> "bubble_settings"
    Screen.CustomDictionary -> "dictionary"
    Screen.Snippets -> "snippets"
    Screen.SyncConfig -> "sync"
    Screen.About -> "about"
    is Screen.TranscriptionDetail -> "detail:${screen.entryId}"
    is Screen.BucketPicker -> "bucket:${screen.bucket}"
    Screen.AiCleanupStyles -> "ai_styles"
    is Screen.AiStylePicker -> "ai_style:${screen.styleId}"
    Screen.Agents -> "agents"
}

private fun decodeScreen(code: String): Screen? = when {
    code.startsWith("detail:") ->
        code.removePrefix("detail:").toLongOrNull()?.let { Screen.TranscriptionDetail(it) }
    code == "ai_styles" -> Screen.AiCleanupStyles
    code.startsWith("ai_style:") ->
        code.removePrefix("ai_style:").takeIf { it.isNotBlank() }?.let { Screen.AiStylePicker(it) }
    code.startsWith("bucket:") ->
        code.removePrefix("bucket:").let { raw ->
            val category = com.groq.voicetyper.formatting.FormattingCategory.fromName(raw)
            if (category == com.groq.voicetyper.formatting.FormattingCategory.NEUTRAL) null
            else Screen.BucketPicker(category.name)
        }
    else -> when (code) {
        "home" -> Screen.Home
        "history" -> Screen.History
        "settings" -> Screen.SettingsHub
        "stt_config" -> Screen.SttConfig
        "agent_config" -> Screen.AgentConfig
        "offline_config" -> Screen.OfflineConfig
        "permissions" -> Screen.Permissions
        "privacy_exclusions" -> Screen.PrivacyExclusions
        "formatting" -> Screen.Formatting
        "agents" -> Screen.Agents
        "ai_styles" -> Screen.AiCleanupStyles
        "bubble_settings" -> Screen.BubbleSettings
        "dictionary" -> Screen.CustomDictionary
        "snippets" -> Screen.Snippets
        "sync" -> Screen.SyncConfig
        "about" -> Screen.About
        else -> null
    }
}

internal val ScreenStackSaver = Saver<SnapshotStateList<Screen>, ArrayList<String>>(
    save = { stack ->
        arrayListOf<String>().apply { for (s in stack) add(encodeScreen(s)) }
    },
    restore = { saved ->
        mutableStateListOf<Screen>().apply { for (code in saved) decodeScreen(code)?.let { add(it) } }
    }
)
