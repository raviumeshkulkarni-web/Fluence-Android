package com.groq.voicetyper.sync.v1

/**
 * Frozen Sync v1.2 domain models — deterministic, cross-platform.
 * BusinessKey = lower(trim(spoken|trigger)) — single winner per key, always
 * recomputed from content (never trusted from the wire).
 * Clock: wall UTC ms + maxSeen floor; winner = max(updatedAt, deviceId).
 * Tombstones are ordinary records: they win exactly when they are newest.
 */

data class DictionaryRecord(
    val syncId: String,
    val businessKey: String,
    val spoken: String,
    val corrected: String,
    val isEnabled: Boolean,
    val updatedAt: Long,
    val deletedAt: Long?,
    val deviceId: String
) {
    val tombstoneBit: Int get() = if (deletedAt != null) 1 else 0
    companion object {
        fun businessKeyOf(spoken: String): String = spoken.trim().lowercase()
    }
}

data class SnippetRecord(
    val syncId: String,
    val businessKey: String,
    val trigger: String,
    val expansion: String,
    val isEnabled: Boolean,
    val updatedAt: Long,
    val deletedAt: Long?,
    val deviceId: String
) {
    val tombstoneBit: Int get() = if (deletedAt != null) 1 else 0
    companion object {
        fun businessKeyOf(trigger: String): String = trigger.trim().lowercase()
    }
}

data class StatRecord(
    val eventId: String,
    val day: String, // UTC yyyy-MM-dd
    val wordCount: Int,
    val durationMs: Long,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null,
    val timestampMs: Long = 0,
    val chars: Int = 0
)

data class SettingsRecord(
    val key: String,
    val value: String,
    val updatedAt: Long,
    val deviceId: String,
    val deletedAt: Long? = null
) {
    companion object {
        /** Frozen v1.1 scope — exactly these five keys sync (per-key LWW).
         * Android pref mapping: language→stt_language, dictionary_enabled→
         * custom_dictionary_enabled, snippets_enabled→snippets_enabled,
         * auto_learn_enabled→auto_learn_enabled, ai_polish_style→ai_polish_style. */
        val ALLOWED_KEYS = setOf(
            "language",
            "dictionary_enabled",
            "snippets_enabled",
            "auto_learn_enabled",
            "ai_polish_style"
        )
    }
}

enum class DomainFile { DICTIONARY, SNIPPETS, STATS, SETTINGS }

data class DictionaryDomain(val v: Int = 1, val entries: List<DictionaryRecord>)
data class SnippetDomain(val v: Int = 1, val entries: List<SnippetRecord>)
data class StatsDomain(val v: Int = 1, val entries: List<StatRecord>)
data class SettingsDomain(val v: Int = 1, val entries: List<SettingsRecord>)
