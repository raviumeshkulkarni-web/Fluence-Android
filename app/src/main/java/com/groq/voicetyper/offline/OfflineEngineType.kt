package com.groq.voicetyper.offline

enum class OfflineEngineType(val displayName: String) {
    SENSEVOICE("Fast (Multilingual)"),
    MOONSHINE_V2_SMALL_STREAMING("Fast (English)"),
    MOONSHINE_V2_MEDIUM_STREAMING("Pro (English)");

    val isStreaming: Boolean
        get() = this == MOONSHINE_V2_SMALL_STREAMING ||
            this == MOONSHINE_V2_MEDIUM_STREAMING

    val modelArch: Int
        get() = when (this) {
            MOONSHINE_V2_SMALL_STREAMING -> ai.moonshine.voice.JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
            MOONSHINE_V2_MEDIUM_STREAMING -> ai.moonshine.voice.JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING
            else -> 0
        }
}
