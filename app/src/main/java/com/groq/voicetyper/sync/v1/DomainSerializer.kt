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

    /**
     * JSON string encoding, byte-identical with serde_json on the Windows side.
     *
     * The short forms for backspace (0x08) and form feed (0x0C) exist for
     * exactly this reason: serde emits the short forms, so a hand-rolled long
     * hex escape here would serialize the same string to different bytes per
     * platform (plausible via pasted PDF text, where the form feed is the page
     * break). Every other character below 0x20 keeps the long hex form,
     * matching serde.
     *
     * NOTE: this comment deliberately spells out "long hex form" instead of
     * writing the escape literally. kapt generates Java stubs from this source,
     * and Java's pre-lexer validates every backslash-u sequence ANYWHERE —
     * including inside comments — so a literal escape here is a build error.
     */
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
                // 0x08 backspace and 0x0C form feed use serde's short forms
                // (written as code comparisons so the control bytes stay
                // visible in review rather than hiding inside literals).
                else -> if (c.code == 0x08) sb.append("\\b")
                else if (c.code == 0x0C) sb.append("\\f")
                else if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    // Strict, and identical to Windows: see [isStrictUuid]. `UUID.fromString`
    // would accept malformed group lengths here and reject the simple form
    // Windows accepts, admitting records asymmetrically.
    private fun validUuid(s: String): Boolean = isStrictUuid(s)

    // ---- Canonical record bodies (RULE A level 4 of `Merge`) -------------
    //
    // These build the frozen wire object of ONE record: compact separators,
    // frozen key order, `null` for an absent `deletedAt` — byte-for-byte what
    // `serialize*` writes into an envelope and byte-for-byte what Windows'
    // `serde_json::to_string` produces for the same record.
    //
    // They are the SINGLE writer for both jobs on purpose. `Merge` uses them as
    // the last-resort tiebreak between two records that tie on
    // (updatedAt, deviceId, syncId); if that comparison used a hand-built or
    // differently-configured writer, any field-order / whitespace / escaping /
    // number-format drift between the merge path and the emit path would
    // silently reintroduce the split-brain the tiebreak exists to prevent.
    // A drift between the two PLATFORMS' writers is equally fatal, so the key
    // order below is the frozen contract's order verbatim and must not be
    // reordered.

    internal fun canonicalDictionaryEntry(e: DictionaryRecord): String {
        val sb = StringBuilder(128)
        sb.append("{\"syncId\":").append(jsonString(e.syncId))
        sb.append(",\"businessKey\":").append(jsonString(e.businessKey))
        sb.append(",\"spoken\":").append(jsonString(e.spoken))
        sb.append(",\"corrected\":").append(jsonString(e.corrected))
        sb.append(",\"isEnabled\":").append(e.isEnabled)
        sb.append(",\"updatedAt\":").append(e.updatedAt)
        sb.append(",\"deletedAt\":").append(e.deletedAt?.toString() ?: "null")
        sb.append(",\"deviceId\":").append(jsonString(e.deviceId))
        sb.append("}")
        return sb.toString()
    }

    internal fun canonicalSnippetEntry(e: SnippetRecord): String {
        val sb = StringBuilder(128)
        sb.append("{\"syncId\":").append(jsonString(e.syncId))
        sb.append(",\"businessKey\":").append(jsonString(e.businessKey))
        sb.append(",\"trigger\":").append(jsonString(e.trigger))
        sb.append(",\"expansion\":").append(jsonString(e.expansion))
        sb.append(",\"isEnabled\":").append(e.isEnabled)
        sb.append(",\"updatedAt\":").append(e.updatedAt)
        sb.append(",\"deletedAt\":").append(e.deletedAt?.toString() ?: "null")
        sb.append(",\"deviceId\":").append(jsonString(e.deviceId))
        sb.append("}")
        return sb.toString()
    }

    internal fun canonicalAgentEntry(e: AgentRecord): String {
        val sb = StringBuilder(128)
        sb.append("{\"syncId\":").append(jsonString(e.syncId))
        sb.append(",\"businessKey\":").append(jsonString(e.businessKey))
        sb.append(",\"name\":").append(jsonString(e.name))
        sb.append(",\"hint\":").append(jsonString(e.hint))
        sb.append(",\"updatedAt\":").append(e.updatedAt)
        sb.append(",\"deletedAt\":").append(e.deletedAt?.toString() ?: "null")
        sb.append(",\"deviceId\":").append(jsonString(e.deviceId))
        sb.append("}")
        return sb.toString()
    }

    internal fun canonicalStyleEntry(e: StyleRecord): String {
        val sb = StringBuilder(128)
        sb.append("{\"syncId\":").append(jsonString(e.syncId))
        sb.append(",\"businessKey\":").append(jsonString(e.businessKey))
        sb.append(",\"name\":").append(jsonString(e.name))
        sb.append(",\"hint\":").append(jsonString(e.hint))
        sb.append(",\"updatedAt\":").append(e.updatedAt)
        sb.append(",\"deletedAt\":").append(e.deletedAt?.toString() ?: "null")
        sb.append(",\"deviceId\":").append(jsonString(e.deviceId))
        sb.append("}")
        return sb.toString()
    }

    /**
     * The envelope version `bytes` declares, or null when the payload is not a
     * readable JSON object with an integer `v`.
     *
     * This separates "a future client owns this file, do not touch it" from
     * "this file is corrupt, repair it": `parse*` returns null for either
     * cause, and repairing a future envelope would destroy the newer client's
     * data. Mirrors Windows `declared_envelope_version`.
     *
     * SATURATE, never truncate — the exact twin of `domain.rs`. The previous
     * `optInt("v", 0)` narrowed through `intValue()`, which silently truncated:
     * `{"v":4294967297}` became `1`, so a future envelope was misclassified as
     * v1 — i.e. "corrupt", which the sync pass IS allowed to overwrite, destroying
     * the newer client's data. Every value `>= 2^31` now saturates to
     * [Int.MAX_VALUE] and is correctly recognised as future.
     *
     * A JSON float is rejected (null), mirroring Rust's `as_i64()`, which yields
     * None for a non-integer Number. Note the resulting classification: null
     * means `isFutureEnvelope` is false, so an unreadable `v` is treated as
     * CORRUPT-and-repairable rather than as owned by a future client — the same
     * as Windows. That is deliberate parity, not an oversight: only an
     * unambiguous integer version can prove a newer client owns the file.
     */
    private fun declaredEnvelopeVersion(bytes: ByteArray): Int? = try {
        val raw = JSONObject(String(bytes, Charsets.UTF_8)).opt("v")
        val asLong: Long? = when (raw) {
            null -> null // absent or JSON null — Rust `.get("v")?` / `as_i64()` -> None
            is Double, is Float -> null // non-integer Number — Rust `as_i64()` -> None
            is Number -> raw.toLong()
            else -> null // String/Boolean/etc. — Rust `as_i64()` -> None
        }
        asLong?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()
    } catch (_: Exception) {
        null
    }

    /**
     * True when the payload declares an envelope version NEWER than this
     * client supports, i.e. another client owns the file.
     *
     * Only consulted after a typed parse already failed, so a payload that
     * parses is v1 by definition and the happy path never parses twice
     * (Windows parity: `is_future_envelope`).
     */
    fun isFutureEnvelope(bytes: ByteArray): Boolean {
        val declared = declaredEnvelopeVersion(bytes) ?: return false
        return declared > 1
    }

    // ---- Dictionary ----
    fun serializeDictionary(domain: DictionaryDomain): String {
        // Canonical order = businessKey then syncId (frozen contract examples/sync/v1/README.md:16).
        // Document ends with exactly one trailing \n (frozen contract, Windows parity frozen.rs:236).
        val sb = StringBuilder(512)
        sb.append("{\"v\":").append(domain.v).append(",\"entries\":[")
        domain.entries.sortedWith(utf8Comparator({ it.businessKey }, { it.syncId })).forEachIndexed { idx, e ->
            if (idx > 0) sb.append(",")
            sb.append(canonicalDictionaryEntry(e))
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
                        // `getBoolean`, NOT `optBoolean(…, true)`: Windows
                        // declares `is_enabled: bool` with NO `#[serde(default)]`,
                        // so serde treats a missing (or null) field as a hard
                        // error and the record is SKIPPED. `optBoolean(…, true)`
                        // silently admitted it as enabled, so one platform
                        // ingested a record the other always drops — and ingest
                        // is asymmetric on the same bytes by construction.
                        // Throwing here is skipped by the enclosing runCatching,
                        // which is exactly serde's per-record skip.
                        isEnabled = e.getBoolean("isEnabled"),
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
        domain.entries.sortedWith(utf8Comparator({ it.businessKey }, { it.syncId })).forEachIndexed { idx, e ->
            if (idx > 0) sb.append(",")
            sb.append(canonicalSnippetEntry(e))
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
                        // `getBoolean`, NOT `optBoolean(…, true)` — see the
                        // dictionary parser above. Windows `is_enabled: bool` has
                        // no serde default, so a missing/null field skips the
                        // record there; `optBoolean` admitted it here, making
                        // ingest asymmetric on the same bytes.
                        isEnabled = e.getBoolean("isEnabled"),
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

    // ---- Agents (Phase 6, additive) ----
    fun serializeAgents(domain: AgentDomain): String {
        val sb = StringBuilder(512)
        sb.append("{\"v\":").append(domain.v).append(",\"entries\":[")
        domain.entries.sortedWith(utf8Comparator({ it.businessKey }, { it.syncId })).forEachIndexed { idx, e ->
            if (idx > 0) sb.append(",")
            sb.append("{\"syncId\":").append(jsonString(e.syncId))
            sb.append(",\"businessKey\":").append(jsonString(e.businessKey))
            sb.append(",\"name\":").append(jsonString(e.name))
            sb.append(",\"hint\":").append(jsonString(e.hint))
            sb.append(",\"updatedAt\":").append(e.updatedAt)
            sb.append(",\"deletedAt\":").append(e.deletedAt?.toString() ?: "null")
            sb.append(",\"deviceId\":").append(jsonString(e.deviceId))
            sb.append("}")
        }
        sb.append("]}")
        sb.append("\n")
        return sb.toString()
    }

    fun parseAgents(bytes: ByteArray): AgentDomain? {
        if (bytes.size > AppDataDriveStore.MAX_DOMAIN_BYTES) return null
        return try {
            val json = String(bytes, Charsets.UTF_8)
            val o = JSONObject(json)
            if (o.optInt("v", -1) != 1) return null
            val arr = o.optJSONArray("entries") ?: JSONArray()
            if (arr.length() > MAX_ENVELOPE_ITEMS) return null
            val list = mutableListOf<AgentRecord>()
            for (i in 0 until arr.length()) {
                runCatching {
                    val e = arr.getJSONObject(i)
                    val rec = AgentRecord(
                        syncId = e.getString("syncId"),
                        businessKey = e.getString("businessKey"),
                        name = e.getString("name"),
                        hint = e.optString("hint", ""),
                        updatedAt = e.getLong("updatedAt"),
                        deletedAt = if (e.isNull("deletedAt")) null else e.getLong("deletedAt"),
                        deviceId = e.getString("deviceId")
                    )
                    if (rec.isValid()) list.add(rec)
                }
            }
            AgentDomain(v = 1, entries = list)
        } catch (_: Exception) { null }
    }

    // ---- Styles (Phase 6, additive) ----
    fun serializeStyles(domain: StyleDomain): String {
        val sb = StringBuilder(512)
        sb.append("{\"v\":").append(domain.v).append(",\"entries\":[")
        domain.entries.sortedWith(utf8Comparator({ it.businessKey }, { it.syncId })).forEachIndexed { idx, e ->
            if (idx > 0) sb.append(",")
            sb.append("{\"syncId\":").append(jsonString(e.syncId))
            sb.append(",\"businessKey\":").append(jsonString(e.businessKey))
            sb.append(",\"name\":").append(jsonString(e.name))
            sb.append(",\"hint\":").append(jsonString(e.hint))
            sb.append(",\"updatedAt\":").append(e.updatedAt)
            sb.append(",\"deletedAt\":").append(e.deletedAt?.toString() ?: "null")
            sb.append(",\"deviceId\":").append(jsonString(e.deviceId))
            sb.append("}")
        }
        sb.append("]}")
        sb.append("\n")
        return sb.toString()
    }

    fun parseStyles(bytes: ByteArray): StyleDomain? {
        if (bytes.size > AppDataDriveStore.MAX_DOMAIN_BYTES) return null
        return try {
            val json = String(bytes, Charsets.UTF_8)
            val o = JSONObject(json)
            if (o.optInt("v", -1) != 1) return null
            val arr = o.optJSONArray("entries") ?: JSONArray()
            if (arr.length() > MAX_ENVELOPE_ITEMS) return null
            val list = mutableListOf<StyleRecord>()
            for (i in 0 until arr.length()) {
                runCatching {
                    val e = arr.getJSONObject(i)
                    val rec = StyleRecord(
                        syncId = e.getString("syncId"),
                        businessKey = e.getString("businessKey"),
                        name = e.getString("name"),
                        hint = e.optString("hint", ""),
                        updatedAt = e.getLong("updatedAt"),
                        deletedAt = if (e.isNull("deletedAt")) null else e.getLong("deletedAt"),
                        deviceId = e.getString("deviceId")
                    )
                    if (rec.isValid()) list.add(rec)
                }
            }
            StyleDomain(v = 1, entries = list)
        } catch (_: Exception) { null }
    }

    // F3 — far-future clock cap: 24h tolerance, per-record skip, never whole-file
    const val CLOCK_SKEW_TOLERANCE_MS: Long = 24 * 60 * 60 * 1000L
    private fun isFuture(updatedAt: Long): Boolean = updatedAt > System.currentTimeMillis() + CLOCK_SKEW_TOLERANCE_MS

    /** Per-record validation — invalid records are skipped at ingest. */
    private fun DictionaryRecord.isValid(): Boolean =
        validUuid(syncId) && deviceId.isNotEmpty() && updatedAt > 0 && !isFuture(updatedAt) &&
            (deletedAt == null || deletedAt > 0) &&
            // Rust twin (domain.rs): `spoken.trim().is_empty() || corrected.trim().is_empty()`.
            // `unicodeTrim`, NOT `isNotBlank`: Java's `Character.isWhitespace` EXCLUDES
            // NBSP U+00A0 while Rust's `str::trim` (Unicode White_Space) INCLUDES it, so
            // `isNotBlank()` accepted an all-NBSP `spoken` that Windows rejects.
            unicodeTrim(spoken).isNotEmpty() && unicodeTrim(corrected).isNotEmpty() &&
            spoken.codePointCount(0, spoken.length) <= 4096 && corrected.codePointCount(0, corrected.length) <= 4096

    private fun SnippetRecord.isValid(): Boolean =
        validUuid(syncId) && deviceId.isNotEmpty() && updatedAt > 0 && !isFuture(updatedAt) &&
            (deletedAt == null || deletedAt > 0) &&
            // Rust twin (domain.rs): `trigger.trim().is_empty() || expansion.is_empty()`.
            // Note `trigger` is TRIMMED but `expansion` is NOT - that asymmetry is
            // deliberate upstream and mirroring it exactly is the point.
            unicodeTrim(trigger).isNotEmpty() && expansion.isNotEmpty() &&
            trigger.codePointCount(0, trigger.length) <= 4096 && expansion.codePointCount(0, expansion.length) <= 8192

    private fun StatRecord.isValid(): Boolean =
        validUuid(eventId) &&
            // Reuse the SHARD-ROUTING validator rather than a second copy. A length-10
            // check alone let "2026-99-99" and "2026-02-30" through; `monthOfDay` is the
            // same strict calendar parse (LocalDate/ISO) that decides shard membership,
            // and it already mirrors the Windows NaiveDate check. One predicate, so the
            // ingest rule and the routing rule cannot drift apart.
            StatsShards.monthOfDay(day) != null &&
            timestampMs >= 0 &&
            wordCount in 0..1_000_000 &&
            chars in 0..10_000_000 &&
            durationMs in 0..86_400_000L * 7 &&
            !isFuture(updatedAt)

    private fun SettingsRecord.isValid(): Boolean =
        key in SettingsRecord.ALLOWED_KEYS && deviceId.isNotEmpty() && updatedAt > 0 && !isFuture(updatedAt) &&
            value.codePointCount(0, value.length) <= 1024

    /**
     * Agents/Styles ingest validation. A tombstone (`deletedAt` present) is
     * still a valid record: dropping it at ingest would resurrect the deleted
     * record on the next merge, so these accept `deletedAt >= 0` while a
     * negative value is still rejected.
     */
    private fun AgentRecord.isValid(): Boolean =
        validUuid(syncId) && AgentRecord.isValidBusinessKey(businessKey) &&
            deviceId.isNotEmpty() && updatedAt > 0 && !isFuture(updatedAt) &&
            (deletedAt == null || deletedAt >= 0) &&
            // Rust twin (domain.rs): `self.name.trim().is_empty() { return false }`.
            // `unicodeTrim`, NOT `isNotBlank` — same divergence class the
            // dictionary/snippet validators above already fixed: `Char.isBlank`
            // uses Kotlin's whitespace set, which disagrees with Rust's Unicode
            // `White_Space` in BOTH directions (it accepts U+00A0/U+0085 that Rust
            // trims, and rejects U+001C..U+001F that Rust does not trim).
            unicodeTrim(name).isNotEmpty() &&
            name.codePointCount(0, name.length) <= 1024 &&
            hint.codePointCount(0, hint.length) <= 8192

    private fun StyleRecord.isValid(): Boolean =
        validUuid(syncId) && StyleRecord.isValidBusinessKey(businessKey) &&
            deviceId.isNotEmpty() && updatedAt > 0 && !isFuture(updatedAt) &&
            (deletedAt == null || deletedAt >= 0) &&
            unicodeTrim(name).isNotEmpty() &&
            name.codePointCount(0, name.length) <= 1024 &&
            hint.codePointCount(0, hint.length) <= 8192
}
