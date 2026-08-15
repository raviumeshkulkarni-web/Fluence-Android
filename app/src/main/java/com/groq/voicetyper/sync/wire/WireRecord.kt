package com.groq.voicetyper.sync.wire

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.json.JSONObject

/**
 * Record kind (§30.1). [History] is the default; the `type` field is additive,
 * so records without `type` parse as history and serialize without the field.
 */
enum class RecordType {
    History,
    Dictionary,
    Snippet,
    Settings,
}

/**
 * A schema-v1 wire record, mirroring `examples/sync/` fixture JSON.
 *
 * Serialization order is fixed (declaration order below): `toJson()` always
 * writes compactly in this exact order, which makes byte-level equality
 * deterministic. History records omit every §30 field (the `type` field and
 * all kind fields are skipped when empty/default), so their bytes are
 * unchanged from Phase 1–4.
 */
data class WireRecord(
    val v: Int,
    val id: String,
    val createdAt: Long,
    val deletedAt: Long?,
    val rtype: RecordType = RecordType.History,
    val text: String = "",
    val mode: String = "",
    val durationMs: Long = 0,
    val provider: String = "",
    val model: String? = null,
    val language: String? = null,
    // §30 content fields — history records never carry them.
    val spoken: String? = null,
    val corrected: String? = null,
    val kind: String? = null,
    val trigger: String? = null,
    val expansion: String? = null,
    val settingsKey: String? = null,
    val settingsValue: String? = null,
) {
    fun content(): RecordContent = when (rtype) {
        RecordType.History -> RecordContent.History(
            ContentTuple(
                createdAt = createdAt,
                text = text,
                mode = mode,
                durationMs = durationMs,
                provider = provider,
                model = model,
                language = language,
            )
        )
        RecordType.Dictionary -> RecordContent.Dictionary(
            DictionaryTuple(
                createdAt = createdAt,
                spoken = spoken ?: "",
                corrected = corrected ?: "",
                kind = kind ?: "",
            )
        )
        RecordType.Snippet -> RecordContent.Snippet(
            SnippetTuple(
                createdAt = createdAt,
                trigger = trigger ?: "",
                expansion = expansion ?: "",
            )
        )
        RecordType.Settings -> RecordContent.Settings(
            SettingsTuple(
                createdAt = createdAt,
                key = settingsKey ?: "",
                value = settingsValue ?: "",
            )
        )
    }

    fun toJson(): String {
        val sb = StringBuilder(256)
        sb.append("{\"v\":").append(v)
        sb.append(",\"id\":").append(jsonString(id))
        sb.append(",\"created_at\":").append(createdAt)
        sb.append(",\"deleted_at\":").append(deletedAt ?: "null")
        if (rtype != RecordType.History) {
            sb.append(",\"type\":").append(jsonString(when (rtype) {
                RecordType.History -> "history"
                RecordType.Dictionary -> "dictionary"
                RecordType.Snippet -> "snippet"
                RecordType.Settings -> "settings"
            }))
        }
        sb.append(",\"text\":").append(jsonString(text))
        sb.append(",\"mode\":").append(jsonString(mode))
        sb.append(",\"duration_ms\":").append(durationMs)
        sb.append(",\"provider\":").append(jsonString(provider))
        sb.append(",\"model\":").append(model?.let(::jsonString) ?: "null")
        sb.append(",\"language\":").append(language?.let(::jsonString) ?: "null")
        when (rtype) {
            RecordType.Dictionary -> {
                spoken?.let { sb.append(",\"spoken\":").append(jsonString(it)) }
                corrected?.let { sb.append(",\"corrected\":").append(jsonString(it)) }
                kind?.let { sb.append(",\"kind\":").append(jsonString(it)) }
            }
            RecordType.Snippet -> {
                trigger?.let { sb.append(",\"trigger\":").append(jsonString(it)) }
                expansion?.let { sb.append(",\"expansion\":").append(jsonString(it)) }
            }
            RecordType.Settings -> {
                settingsKey?.let { sb.append(",\"key\":").append(jsonString(it)) }
                settingsValue?.let { sb.append(",\"value\":").append(jsonString(it)) }
            }
            RecordType.History -> Unit
        }
        sb.append('}')
        return sb.toString()
    }
}

/** Content tuple `T = (created_at, text, mode, duration_ms, provider, model,
 * language)` for `history` records. `deleted_at` is deliberately NOT part of
 * `T` (spec §4). */
data class ContentTuple(
    val createdAt: Long,
    val text: String,
    val mode: String,
    val durationMs: Long,
    val provider: String,
    val model: String?,
    val language: String?,
)

/** Content tuple for `dictionary` records (§30.1). */
data class DictionaryTuple(
    val createdAt: Long,
    val spoken: String,
    val corrected: String,
    val kind: String,
)

/** Content tuple for `snippet` records (§30.1). */
data class SnippetTuple(
    val createdAt: Long,
    val trigger: String,
    val expansion: String,
)

/** Content tuple for `settings` records (§30.1, §30.3). */
data class SettingsTuple(
    val createdAt: Long,
    val key: String,
    val value: String,
)

/** Kind-aware content of a record — the equality domain for group
 * classification (§9). Two records of different kinds are never equal. */
sealed class RecordContent {
    data class History(val tuple: ContentTuple) : RecordContent()
    data class Dictionary(val tuple: DictionaryTuple) : RecordContent()
    data class Snippet(val tuple: SnippetTuple) : RecordContent()
    data class Settings(val tuple: SettingsTuple) : RecordContent()
}

/** Exact field equality on the record kind's tuple — no equivalence, no
 * canonicalization (R1). */
fun tuplesEqual(a: RecordContent, b: RecordContent): Boolean = a == b

/** Full record with the same `T` and `deleted_at` set (matches fixture ...003). */
fun tombstone(record: WireRecord, deletedAt: Long): WireRecord =
    record.copy(deletedAt = deletedAt)

/** Why a file failed validation. Every reason maps to a quarantine reason in
 * the engine (spec §4, §9, §30.1). */
enum class InvalidReason {
    MalformedJson,
    UnknownSchemaVersion,
    IdNameMismatch,
    BadTimestamp,
    BadMode,
    NonIntegral,
    UnknownType,
    MissingTypeField,
    BadKind,
}

sealed class ParseResult {
    data class Ok(val record: WireRecord) : ParseResult()
    data class Err(val reason: InvalidReason) : ParseResult()
}

/**
 * Validate `bytes` against the schema-v1 rules (spec §4, §30.1):
 * `v == 1`, lowercase UUID v4 `id` equal to `basename`, `created_at > 0`,
 * `deleted_at` null or positive, all ints are ints; then per type — history
 * requires `text`/`mode`/`duration_ms`/`provider` present and mode in the two
 * known values; `dictionary` requires `spoken`/`corrected` (non-blank) and
 * `kind ∈ {correction, expansion}`; `snippet` requires `trigger`/`expansion`;
 * `settings` requires `key`. Unknown `type` → [InvalidReason.UnknownType].
 * `basename` is the UUID stem of the file name (no `.json`).
 */
fun parse(bytes: ByteArray, basename: String): ParseResult {
    val json = decodeUtf8(bytes) ?: return ParseResult.Err(InvalidReason.MalformedJson)
    val value = try {
        JSONObject(json)
    } catch (e: Exception) {
        return ParseResult.Err(InvalidReason.MalformedJson)
    }

    val rtype = when (val t = value.opt("type")) {
        null, JSONObject.NULL -> RecordType.History
        is String -> when (t) {
            "history" -> RecordType.History
            "dictionary" -> RecordType.Dictionary
            "snippet" -> RecordType.Snippet
            "settings" -> RecordType.Settings
            else -> return ParseResult.Err(InvalidReason.UnknownType)
        }
        else -> return ParseResult.Err(InvalidReason.UnknownType)
    }

    if (!isIntegral(value, "v")) return ParseResult.Err(InvalidReason.NonIntegral)
    if (!isIntegral(value, "created_at")) return ParseResult.Err(InvalidReason.NonIntegral)
    if (!isOptionalIntegral(value, "deleted_at")) return ParseResult.Err(InvalidReason.NonIntegral)

    // Per-type presence/integrality checks on the raw JSON (kind-specific
    // fields use serde-style defaults, so non-history kinds may omit the
    // history-only fields).
    when (rtype) {
        RecordType.History -> {
            if (!isIntegral(value, "duration_ms")) return ParseResult.Err(InvalidReason.NonIntegral)
            if (!value.has("text") || !value.has("mode") || !value.has("provider")) {
                return ParseResult.Err(InvalidReason.MissingTypeField)
            }
        }
        RecordType.Dictionary -> {
            if (!fieldPresentAndNonblank(value, "spoken") ||
                !fieldPresentAndNonblank(value, "corrected")
            ) {
                return ParseResult.Err(InvalidReason.MissingTypeField)
            }
        }
        RecordType.Snippet -> {
            if (!fieldPresentAndNonblank(value, "trigger") ||
                !fieldPresentAndNonblank(value, "expansion")
            ) {
                return ParseResult.Err(InvalidReason.MissingTypeField)
            }
        }
        RecordType.Settings -> {
            if (!fieldPresentAndNonblank(value, "key")) {
                return ParseResult.Err(InvalidReason.MissingTypeField)
            }
        }
    }

    val v = integral(value, "v").toInt()
    val createdAt = integral(value, "created_at")
    val deletedAt = optionalIntegral(value, "deleted_at")

    val rawId = value.opt("id")
    if (rawId !is String) return ParseResult.Err(InvalidReason.MalformedJson)
    val id = rawId
    val text = when (val t = value.opt("text")) {
        null -> ""
        JSONObject.NULL -> return ParseResult.Err(InvalidReason.MalformedJson)
        is String -> t
        else -> return ParseResult.Err(InvalidReason.MalformedJson)
    }
    val mode = when (val m = value.opt("mode")) {
        null -> ""
        JSONObject.NULL -> return ParseResult.Err(InvalidReason.MalformedJson)
        is String -> m
        else -> return ParseResult.Err(InvalidReason.MalformedJson)
    }
    val durationMs = when (val d = value.opt("duration_ms")) {
        null -> 0L
        is Int -> d.toLong()
        is Long -> d
        else -> return ParseResult.Err(InvalidReason.MalformedJson)
    }
    val provider = when (val p = value.opt("provider")) {
        null -> ""
        JSONObject.NULL -> return ParseResult.Err(InvalidReason.MalformedJson)
        is String -> p
        else -> return ParseResult.Err(InvalidReason.MalformedJson)
    }
    val model = when (val m = value.opt("model")) {
        null, JSONObject.NULL -> null
        is String -> m
        else -> return ParseResult.Err(InvalidReason.MalformedJson)
    }
    val language = when (val l = value.opt("language")) {
        null, JSONObject.NULL -> null
        is String -> l
        else -> return ParseResult.Err(InvalidReason.MalformedJson)
    }

    fun optionalString(key: String): String? = when (val s = value.opt(key)) {
        null, JSONObject.NULL -> null
        is String -> s
        else -> throw MalformedJsonException()
    }

    val record = try {
        WireRecord(
            v = v,
            id = id,
            createdAt = createdAt,
            deletedAt = deletedAt,
            rtype = rtype,
            text = text,
            mode = mode,
            durationMs = durationMs,
            provider = provider,
            model = model,
            language = language,
            spoken = optionalString("spoken"),
            corrected = optionalString("corrected"),
            kind = optionalString("kind"),
            trigger = optionalString("trigger"),
            expansion = optionalString("expansion"),
            settingsKey = optionalString("key"),
            settingsValue = optionalString("value"),
        )
    } catch (e: MalformedJsonException) {
        return ParseResult.Err(InvalidReason.MalformedJson)
    }
    if (record.v != 1) return ParseResult.Err(InvalidReason.UnknownSchemaVersion)
    if (record.id != basename || !isLowercaseUuidV4(record.id)) {
        return ParseResult.Err(InvalidReason.IdNameMismatch)
    }
    if (record.createdAt <= 0) return ParseResult.Err(InvalidReason.BadTimestamp)
    if (record.deletedAt != null && record.deletedAt <= 0) return ParseResult.Err(InvalidReason.BadTimestamp)
    when (rtype) {
        RecordType.History -> {
            if (record.mode != "transcription" && record.mode != "agent") {
                return ParseResult.Err(InvalidReason.BadMode)
            }
        }
        RecordType.Dictionary -> {
            if (record.kind != "correction" && record.kind != "expansion") {
                return ParseResult.Err(InvalidReason.BadKind)
            }
        }
        RecordType.Snippet, RecordType.Settings -> {}
    }
    return ParseResult.Ok(record)
}

fun uuidBasename(name: String): String? {
    if (!name.endsWith(".json")) return null
    val stem = name.substring(0, name.length - 5)
    return if (isLowercaseUuidV4(stem)) stem else null
}

fun isLowercaseUuidV4(s: String): Boolean {
    if (s.length != 36) return false
    for (i in 0 until 36) {
        if (i == 8 || i == 13 || i == 18 || i == 23) {
            if (s[i] != '-') return false
        } else {
            val c = s[i]
            if (!((c in '0'..'9') || (c in 'a'..'f'))) return false
        }
    }
    val v = s[14]
    val variant = s[19]
    return v == '4' && (variant == '8' || variant == '9' || variant == 'a' || variant == 'b')
}

private fun fieldPresentAndNonblank(o: JSONObject, key: String): Boolean {
    val raw = o.opt(key)
    return raw is String && raw.trim().isNotEmpty()
}

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

private fun isIntegral(o: JSONObject, key: String): Boolean =
    o.opt(key) is Int || o.opt(key) is Long

private fun isOptionalIntegral(o: JSONObject, key: String): Boolean {
    val raw = o.opt(key)
    if (raw == null || raw === JSONObject.NULL) return true
    return raw is Int || raw is Long
}

private fun integral(o: JSONObject, key: String): Long =
    (o.opt(key) as Number).toLong()

private fun optionalIntegral(o: JSONObject, key: String): Long? {
    val raw = o.opt(key)
    if (raw == null || raw === JSONObject.NULL) return null
    return (raw as Number).toLong()
}

private fun decodeUtf8(bytes: ByteArray): String? = try {
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (e: Exception) {
    null
}

private class MalformedJsonException : Exception()