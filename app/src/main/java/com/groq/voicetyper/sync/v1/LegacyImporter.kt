package com.groq.voicetyper.sync.v1

/**
 * One-time legacy Drive import (frozen v1.1): reads the old per-record
 * `Fluence Transcribe/` wire files and converts them to v1.1 domain records
 * via LWW, leaving the legacy folder untouched.
 *
 * Determinism: updatedAt comes ONLY from the record's `created_at` (validated
 * > 0 by the legacy wire format) — never from wall clock — so two devices
 * importing the same legacy corpus derive byte-identical domains and the
 * first PUT converges instead of fighting an LWW coin-flip.
 */
object LegacyImporter {

    data class LegacyFile(val fileId: String, val name: String, val bytes: ByteArray)

    fun importDictionary(legacyFiles: List<LegacyFile>): List<DictionaryRecord> {
        val parsed = legacyFiles.mapNotNull { f ->
            try {
                val o = org.json.JSONObject(String(f.bytes, Charsets.UTF_8))
                val spoken = o.optString("spoken", "")
                val corrected = o.optString("corrected", "")
                val createdAt = o.optLong("created_at", 0L)
                if (spoken.isBlank() || createdAt <= 0) return@mapNotNull null
                DictionaryRecord(
                    syncId = o.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() },
                    businessKey = DictionaryRecord.businessKeyOf(spoken),
                    spoken = spoken,
                    corrected = corrected,
                    isEnabled = true,
                    updatedAt = createdAt,
                    deletedAt = if (o.isNull("deleted_at")) null else o.optLong("deleted_at"),
                    deviceId = "legacy"
                )
            } catch (_: Exception) { null }
        }
        return Merge.mergeDictionaries(parsed, emptyList())
    }

    fun importSnippets(legacyFiles: List<LegacyFile>): List<SnippetRecord> {
        val parsed = legacyFiles.mapNotNull { f ->
            try {
                val o = org.json.JSONObject(String(f.bytes, Charsets.UTF_8))
                val trigger = o.optString("trigger", "")
                val expansion = o.optString("expansion", "")
                val createdAt = o.optLong("created_at", 0L)
                if (trigger.isBlank() || createdAt <= 0) return@mapNotNull null
                SnippetRecord(
                    syncId = o.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() },
                    businessKey = SnippetRecord.businessKeyOf(trigger),
                    trigger = trigger,
                    expansion = expansion,
                    isEnabled = true,
                    updatedAt = createdAt,
                    deletedAt = if (o.isNull("deleted_at")) null else o.optLong("deleted_at"),
                    deviceId = "legacy"
                )
            } catch (_: Exception) { null }
        }
        return Merge.mergeSnippets(parsed, emptyList())
    }
}
