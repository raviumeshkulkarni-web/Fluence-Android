package com.groq.voicetyper.sync.v1

import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic serialization for v1.2 domain files.
 * - Compact (no whitespace), keys in fixed order, entries sorted for determinism.
 * - Byte-exact: expansion/corrected preserved verbatim, no trimming beyond businessKey.
 *
 * Ingest hardening (v1.2):
 * - Envelope version must be exactly 1; anything else is rejected wholesale.
 * - Individual records that fail validation are SKIPPED, never applied — one
 *   malformed record must not discard an otherwise-valid domain.
 * - businessKey is ALWAYS recomputed from record content; the wire value is
 *   ignored, so a hostile/corrupt file cannot forge group identity.
 * - Payload size caps and per-record magnitude bounds reject abuse safely.
 */
object DomainSerializer {

    /** Maximum records accepted in one envelope (corruption/abuse guard). */
    const val MAX_ENVELOPE_ITEMS = AppDataDriveStore.MAX_ENVELOPE_ITEMS

    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun validUuid(s: String): Boolean = runCatching {
        java.util.UUID.fromString(s)
    }.isSuccess

    // ---- Dictionary ----
    fun serializeDictionary(domain: DictionaryDomain): String {
        // Canonical order = businessKey then syncId (frozen contract examples/sync/v1/README.md:16).
        // Document ends with exactly one trailing \n (frozen contract, Windows parity frozen.rs:236).
        val sb = StringBuilder(512)
        sb.append("{\"v\":").append(domain.v).append(",\"entries\":[")
        domain.entries.sortedWith(compareBy({ it.businessKey }, { it.syncId })).forEachIndexed { idx, e ->
            if (idx > 0) sb.append(",")
            sb.append("{\"syncId\":").append(jsonString(e.syncId))
            sb.append(",\"businessKey\":").append(jsonString(e.businessKey))
            sb.append(",\"spoken\":").append(jsonString(e.spoken))
            sb.append(",\"corrected\":").append(jsonString(e.corrected))
            sb.append(",\"isEnabled\":").append(e.isEnabled)
            sb.append(",\"updatedAt\":").append(e.updatedAt)
            sb.append(",\"deletedAt\":").append(e.deletedAt?.toString() ?: "null")
            sb.append(",\"deviceId\":").append(jsonString(e.deviceId))
            sb.append("}")
        }
        sb.append("]}")
        sb.append("\n")
        return sb.toString()
    }

    fun parseDictionary(bytes: ByteArray): DictionaryDomain? {
        if (bytes.size > AppDataDriveStore.MAX_DOMAIN_BYTES) return null
        return try {
            val json = String(bytes, Charsets.UTF_8)
            val o = JSONObject(json)
            if (o.optInt("v", -1) != 1) return null
            val arr = o.optJSONArray("entries") ?: JSONArray()
            if (arr.length() > MAX_ENVELOPE_ITEMS) return null
            val list = mutableListOf<DictionaryRecord>()
            for (i in 0 until arr.length()) {
                runCatching {
                    val e = arr.getJSONObject(i)
                    val spoken = e.getString("spoken")
                    val corrected = e.getString("corrected")
                    val rec = DictionaryRecord(
                        syncId = e.getString("syncId"),
                        // Recomputed from content — wire value never trusted.
                        businessKey = DictionaryRecord.businessKeyOf(spoken),
                        spoken = spoken,
                        corrected = corrected,
                        isEnabled = e.optBoolean("isEnabled", true),
                        updatedAt = e.getLong("updatedAt"),
                        deletedAt = if (e.isNull("deletedAt")) null else e.getLong("deletedAt"),
                        deviceId = e.getString("deviceId")
                    )
                    if (rec.isValid()) list.add(rec)
                }
            }
            DictionaryDomain(v = 1, entries = list)
        } catch (_: Exception) { null }
    }

    // ---- Snippets ----
    fun serializeSnippets(domain: SnippetDomain): String {
        // Canonical order = businessKey then syncId (frozen contract examples/sync/v1/README.md:20).
        // Document ends with exactly one trailing \n (frozen contract, Windows parity frozen.rs:268).
        val sb = StringBuilder(512)
        sb.append("{\"v\":").append(domain.v).append(",\"entries\":[")
        domain.entries.sortedWith(compareBy({ it.businessKey }, { it.syncId })).forEachIndexed { idx, e ->
            if (idx > 0) sb.append(",")
            sb.append("{\"syncId\":").append(jsonString(e.syncId))
            sb.append(",\"businessKey\":").append(jsonString(e.businessKey))
            sb.append(",\"trigger\":").append(jsonString(e.trigger))
            sb.append(",\"expansion\":").append(jsonString(e.expansion))
            sb.append(",\"isEnabled\":").append(e.isEnabled)
            sb.append(",\"updatedAt\":").append(e.updatedAt)
            sb.append(",\"deletedAt\":").append(e.deletedAt?.toString() ?: "null")
            sb.append(",\"deviceId\":").append(jsonString(e.deviceId))
            sb.append("}")
        }
        sb.append("]}")
        sb.append("\n")
        return sb.toString()
    }

    fun parseSnippets(bytes: ByteArray): SnippetDomain? {
        if (bytes.size > AppDataDriveStore.MAX_DOMAIN_BYTES) return null
        return try {
            val json = String(bytes, Charsets.UTF_8)
            val o = JSONObject(json)
            if (o.optInt("v", -1) != 1) return null
            val arr = o.optJSONArray("entries") ?: JSONArray()
            if (arr.length() > MAX_ENVELOPE_ITEMS) return null
            val list = mutableListOf<SnippetRecord>()
            for (i in 0 until arr.length()) {
                runCatching {
                    val e = arr.getJSONObject(i)
                    val trigger = e.getString("trigger")
                    val rec = SnippetRecord(
                        syncId = e.getString("syncId"),
                        // Recomputed from content — wire value never trusted.
                        businessKey = SnippetRecord.businessKeyOf(trigger),
                        trigger = trigger,
                        expansion = e.getString("expansion"),
                        isEnabled = e.optBoolean("isEnabled", true),
                        updatedAt = e.getLong("updatedAt"),
                        deletedAt = if (e.isNull("deletedAt")) null else e.getLong("deletedAt"),
                        deviceId = e.getString("deviceId")
                    )
                    if (rec.isValid()) list.add(rec)
                }
            }
            SnippetDomain(v = 1, entries = list)
        } catch (_: Exception) { null }
    }

    // ---- Stats ----
    fun serializeStats(domain: StatsDomain): String {
        val sb = StringBuilder(512)
        sb.append("{\"v\":").append(domain.v).append(",\"entries\":[")
        domain.entries.sortedWith(compareBy({ it.day }, { it.eventId })).forEachIndexed { idx, e ->
            if (idx > 0) sb.append(",")
            sb.append("{\"eventId\":").append(jsonString(e.eventId))
            sb.append(",\"day\":").append(jsonString(e.day))
            sb.append(",\"timestampMs\":").append(e.timestampMs)
            sb.append(",\"words\":").append(e.wordCount)
            sb.append(",\"chars\":").append(e.chars)
            sb.append(",\"durationMs\":").append(e.durationMs)
            if (e.updatedAt > 0) sb.append(",\"updatedAt\":").append(e.updatedAt)
            if (e.deviceId.isNotEmpty()) sb.append(",\"deviceId\":").append(jsonString(e.deviceId))
            if (e.deletedAt != null) sb.append(",\"deletedAt\":").append(e.deletedAt)
            sb.append("}")
        }
        sb.append("]}")
        sb.append("\n")
        return sb.toString()
    }

    fun parseStats(bytes: ByteArray): StatsDomain? {
        if (bytes.size > AppDataDriveStore.MAX_DOMAIN_BYTES) return null
        return try {
            val json = String(bytes, Charsets.UTF_8)
            val o = JSONObject(json)
            if (o.optInt("v", -1) != 1) return null
            val arr = o.optJSONArray("entries") ?: JSONArray()
            if (arr.length() > MAX_ENVELOPE_ITEMS) return null
            val list = mutableListOf<StatRecord>()
            for (i in 0 until arr.length()) {
                runCatching {
                    val e = arr.getJSONObject(i)
                    val rec = StatRecord(
                        eventId = e.getString("eventId"),
                        day = e.getString("day"),
                        wordCount = when {
                            e.has("wordCount") && !e.isNull("wordCount") -> e.optInt("wordCount", 0)
                            e.has("words") && !e.isNull("words") -> e.optInt("words", 0)
                            else -> e.optInt("wordCount", 0)
                        },
                        durationMs = e.optLong("durationMs", 0L),
                        updatedAt = e.optLong("updatedAt", 0L),
                        deviceId = e.optString("deviceId", ""),
                        deletedAt = if (e.has("deletedAt") && !e.isNull("deletedAt")) e.getLong("deletedAt") else null,
                        timestampMs = e.optLong("timestampMs", 0L),
                        chars = e.optInt("chars", 0)
                    )
                    if (rec.isValid()) list.add(rec)
                }
            }
            StatsDomain(v = 1, entries = list)
        } catch (_: Exception) { null }
    }

    // ---- Settings ----
    fun serializeSettings(domain: SettingsDomain): String {
        val sb = StringBuilder(256)
        sb.append("{\"v\":").append(domain.v).append(",\"entries\":[")
        domain.entries.sortedBy { it.key }.forEachIndexed { idx, e ->
            if (idx > 0) sb.append(",")
            sb.append("{\"key\":").append(jsonString(e.key))
            sb.append(",\"value\":").append(jsonString(e.value))
            sb.append(",\"updatedAt\":").append(e.updatedAt)
            sb.append(",\"deviceId\":").append(jsonString(e.deviceId))
            if (e.deletedAt != null) sb.append(",\"deletedAt\":").append(e.deletedAt)
            sb.append("}")
        }
        sb.append("]}")
        sb.append("\n")
        return sb.toString()
    }

    fun parseSettings(bytes: ByteArray): SettingsDomain? {
        if (bytes.size > AppDataDriveStore.MAX_DOMAIN_BYTES) return null
        return try {
            val json = String(bytes, Charsets.UTF_8)
            val o = JSONObject(json)
            if (o.optInt("v", -1) != 1) return null
            val arr = o.optJSONArray("entries") ?: JSONArray()
            if (arr.length() > SettingsRecord.ALLOWED_KEYS.size * 4) return null
            val list = mutableListOf<SettingsRecord>()
            for (i in 0 until arr.length()) {
                runCatching {
                    val e = arr.getJSONObject(i)
                    val key = e.getString("key")
                    if (key in SettingsRecord.ALLOWED_KEYS) {
                        val rec = SettingsRecord(
                            key = key,
                            value = e.getString("value"),
                            updatedAt = e.getLong("updatedAt"),
                            deviceId = e.getString("deviceId"),
                            deletedAt = if (e.has("deletedAt") && !e.isNull("deletedAt")) e.getLong("deletedAt") else null
                        )
                        if (rec.isValid()) list.add(rec)
                    }
                }
            }
            SettingsDomain(v = 1, entries = list)
        } catch (_: Exception) { null }
    }

    // F3 — far-future clock cap: 24h tolerance, per-record skip, never whole-file
    const val CLOCK_SKEW_TOLERANCE_MS: Long = 24 * 60 * 60 * 1000L
    private fun isFuture(updatedAt: Long): Boolean = updatedAt > System.currentTimeMillis() + CLOCK_SKEW_TOLERANCE_MS

    /** Per-record validation — invalid records are skipped at ingest. */
    private fun DictionaryRecord.isValid(): Boolean =
        validUuid(syncId) && deviceId.isNotEmpty() && updatedAt > 0 && !isFuture(updatedAt) &&
            (deletedAt == null || deletedAt > 0) &&
            spoken.isNotBlank() && corrected.isNotEmpty() &&
            spoken.codePointCount(0, spoken.length) <= 4096 && corrected.codePointCount(0, corrected.length) <= 4096

    private fun SnippetRecord.isValid(): Boolean =
        validUuid(syncId) && deviceId.isNotEmpty() && updatedAt > 0 && !isFuture(updatedAt) &&
            (deletedAt == null || deletedAt > 0) &&
            trigger.isNotBlank() && expansion.isNotEmpty() &&
            trigger.codePointCount(0, trigger.length) <= 4096 && expansion.codePointCount(0, expansion.length) <= 8192

    private fun StatRecord.isValid(): Boolean =
        validUuid(eventId) &&
            day.length == 10 &&
            timestampMs >= 0 &&
            wordCount in 0..1_000_000 &&
            chars in 0..10_000_000 &&
            durationMs in 0..86_400_000L * 7 &&
            !isFuture(updatedAt)

    private fun SettingsRecord.isValid(): Boolean =
        key in SettingsRecord.ALLOWED_KEYS && deviceId.isNotEmpty() && updatedAt > 0 && !isFuture(updatedAt) &&
            value.codePointCount(0, value.length) <= 1024
}
