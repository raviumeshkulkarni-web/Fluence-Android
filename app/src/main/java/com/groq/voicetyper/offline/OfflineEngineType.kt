package com.groq.voicetyper.offline

enum class OfflineEngineType(val displayName: String, val isExperimental: Boolean) {
    SENSEVOICE("SenseVoice (Default)", false),
    MOONSHINE_BASE("Moonshine Base (Experimental)", true)
}
