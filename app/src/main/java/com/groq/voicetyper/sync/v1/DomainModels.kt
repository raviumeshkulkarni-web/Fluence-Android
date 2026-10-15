package com.groq.voicetyper.sync.v1

import java.text.Normalizer

/**
 * Unicode `White_Space` property (exactly 25 code points) — the trim set that
 * Rust's `str::trim` uses, and therefore the set every identity derivation in
 * this package MUST use.
 *
 * Kotlin's `String.trim()` removes every char `<= U+0020`, which DISAGREES with
 * Rust in both directions, and both halves are data corruption:
 *
 *  - Rust trims, Kotlin does not: NBSP U+00A0 (pasted from a web page/PDF),
 *    U+202F, U+3000 (CJK dictation), U+1680, U+205F, U+2028/9, and NEL
 *    U+0085. A word arriving with one of those on a single device derives a
 *    DIFFERENT business key than the bare word, so the two platforms see two
 *    unrelated records for one word and they never reconcile.
 *  - Kotlin trims, Rust does not: every C0 control below U+0020 that is not
 *    TAB/LF/VT/FF/CR — NUL, SOH, BEL, backspace... A binary blob or a
 *    control-char-prefixed key trimmed here but not on Windows is equally
 *    divergent.
 *
 * The JDK predicates are both wrong for this job and neither may be used:
 * `Character.isWhitespace` EXCLUDES NBSP U+00A0 (the web-paste case) and NEL
 * U+0085; `Character.isSpaceChar` covers the Zs/Zl/Zp separator categories but
 * MISSES NEL U+0085 (a Cc control, not a separator), so it is incomplete on its
 * own. Either one substituted for this table silently drops a code point Rust
 * trims, which re-keys a business account-wide.
 *
 * NOTE for future editors: U+2007 FIGURE SPACE *is* in Unicode `White_Space`
 * (it lies inside the U+2000..U+200A run below) and is therefore deliberately
 * included above. Do not "correct" it out — doing so re-opens a live
 * cross-platform business-key split.
 */
internal fun Char.isUnicodeWhiteSpace(): Boolean = when (code) {
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, // TAB, LF, VT, FF, CR
    0x20,                         // SPACE
    0x85,                         // NEL - NEXT LINE
    0xA0,                         // NO-BREAK SPACE
    0x1680,                       // OGHAM SPACE MARK
    0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200A,
    0x2028,                       // LINE SEPARATOR
    0x2029,                       // PARAGRAPH SEPARATOR
    0x202F,                       // NARROW NO-BREAK SPACE
    0x205F,                       // MEDIUM MATHEMATICAL SPACE
    0x3000                        // IDEOGRAPHIC SPACE
    -> true
    else -> false
}

/**
 * `String.trim()` over the Unicode `White_Space` property — the exact twin of
 * Rust's `str::trim`, so identity and validation agree on both platforms.
 * Only the two ENDS are touched; interior whitespace is payload and is never
 * altered (frozen contract: `spoken`/`corrected` are stored byte-exact).
 */
internal fun unicodeTrim(s: String): String = s.trim { it.isUnicodeWhiteSpace() }

/**
 * Frozen Sync v1.2 domain models — deterministic, cross-platform.
 * BusinessKey = NFC(lower(unicodeTrim(spoken|trigger))) — single winner per key,
 * always recomputed from content (never trusted from the wire). NFC symmetric on
 * both platforms; the trim set is Unicode `White_Space`, NOT Kotlin's
 * `String.trim()` (see [unicodeTrim]).
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
        fun businessKeyOf(spoken: String): String =
            Normalizer.normalize(unicodeTrim(spoken), Normalizer.Form.NFC).lowercase()
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
        fun businessKeyOf(trigger: String): String =
            Normalizer.normalize(unicodeTrim(trigger), Normalizer.Form.NFC).lowercase()
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

/**
 * Phase 6 — Agents/Styles as additive v1 domains.
 *
 * Unlike dictionary/snippets, the business key here is NOT derived from mutable
 * content: it IS the record's own stable id, minted at creation as
 * "agent:<uuid>" (AgentPreferences) / "custom:<uuid>" (AiCleanupPreferences) and
 * never rewritten. Two consequences, both deliberate:
 *
 *  - Renaming an agent must not fork it into a second record, so `name` is
 *    payload and never identity.
 *  - Two devices that independently create agents cannot collide, because the
 *    id is a UUID rather than a device-local counter.
 *
 * Because identity arrives from the wire, it is VALIDATED on ingest rather than
 * trusted — see [AgentRecord.isValidBusinessKey]. An unvalidated key would let a
 * hostile or corrupt file forge a merge identity and win an LWW against a
 * legitimate record.
 */
data class AgentRecord(
    val syncId: String,
    val businessKey: String,
    val name: String,
    val hint: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val deviceId: String
) {
    val tombstoneBit: Int get() = if (deletedAt != null) 1 else 0
    companion object {
        const val PREFIX = "agent:"
        fun isValidBusinessKey(k: String): Boolean =
            k.length > PREFIX.length && k.startsWith(PREFIX) &&
                isStrictUuid(k.substring(PREFIX.length))
    }
}

data class StyleRecord(
    val syncId: String,
    val businessKey: String,
    val name: String,
    val hint: String,
    val updatedAt: Long,
    val deletedAt: Long?,
    val deviceId: String
) {
    val tombstoneBit: Int get() = if (deletedAt != null) 1 else 0
    companion object {
        const val PREFIX = "custom:"
        fun isValidBusinessKey(k: String): Boolean =
            k.length > PREFIX.length && k.startsWith(PREFIX) &&
                isStrictUuid(k.substring(PREFIX.length))
    }
}

enum class DomainFile { DICTIONARY, SNIPPETS, STATS, SETTINGS, AGENTS, STYLES }

data class DictionaryDomain(val v: Int = 1, val entries: List<DictionaryRecord>)
data class SnippetDomain(val v: Int = 1, val entries: List<SnippetRecord>)
data class StatsDomain(val v: Int = 1, val entries: List<StatRecord>)
data class SettingsDomain(val v: Int = 1, val entries: List<SettingsRecord>)
data class AgentDomain(val v: Int = 1, val entries: List<AgentRecord>)
data class StyleDomain(val v: Int = 1, val entries: List<StyleRecord>)

/**
 * Strict UUID admission, equivalent to Rust `Uuid::parse_str` (the `uuid`
 * crate) — the function Windows uses to validate `sync_id` and business keys.
 *
 * That function dispatches on length, and the two shapes that actually occur in
 * sync data are:
 *  - 32 -> "simple": 32 hex digits, no hyphens
 *  - 36 -> "hyphenated": 8-4-4-4-12 hex
 * (the crate also accepts 38-char braced and 45-char URN forms; neither is
 * emitted here, so accepting them would widen the surface without cause — this
 * predicate is deliberately fail-closed on them.)
 *
 * `java.util.UUID.fromString` is not equivalent in BOTH directions:
 *  - it ACCEPTS arbitrary group lengths, so `1-1-1-1-1` parses on Android while
 *    Windows rejects it — a hand-edited or corrupt file is admitted asymmetrically;
 *  - it REJECTS the 32-digit simple form, which Windows ACCEPTS.
 *
 * Demanding only the canonical hyphenated shape would therefore have closed the
 * first asymmetry while opening the second, so both shapes Windows honours are
 * honoured here and nothing else is. Records are skipped individually at
 * ingest, so a stricter test can only drop a malformed record, never a file.
 */
internal fun isStrictUuid(s: String): Boolean {
    fun hex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
    return when (s.length) {
        32 -> s.all(::hex)
        36 -> s[8] == '-' && s[13] == '-' && s[18] == '-' && s[23] == '-' &&
            s.indices.all { i -> i == 8 || i == 13 || i == 18 || i == 23 || hex(s[i]) }
        else -> false
    }
}
