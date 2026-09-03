package com.groq.voicetyper.offline

enum class OfflineEngineType(val displayName: String, val isExperimental: Boolean) {
    SENSEVOICE("SenseVoice (Default)", false),
    MOONSHINE_BASE("Moonshine Base (Experimental)", true),
    MOONSHINE_V2_SMALL_STREAMING("Moonshine v2 Small Streaming (Experimental)", true),
    MOONSHINE_V2_MEDIUM_STREAMING("Moonshine v2 Medium Streaming (Experimental)", true);

    val isStreaming: Boolean
        get() = this == MOONSHINE_V2_SMALL_STREAMING || this == MOONSHINE_V2_MEDIUM_STREAMING

    val modelArch: Int
        get() = when (this) {
            MOONSHINE_V2_SMALL_STREAMING -> ai.moonshine.voice.JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
            MOONSHINE_V2_MEDIUM_STREAMING -> ai.moonshine.voice.JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING
            else -> 0
        }
}
