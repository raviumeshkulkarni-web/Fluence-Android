package com.groq.voicetyper.navigation

sealed interface Screen {
    data object Home : Screen
    data object SettingsHub : Screen
    data object SttConfig : Screen
    data object AgentConfig : Screen
    data object OfflineConfig : Screen
    data object Permissions : Screen
    data object CustomDictionary : Screen
    data object About : Screen
    data class TranscriptionDetail(val entryId: Long) : Screen
}
