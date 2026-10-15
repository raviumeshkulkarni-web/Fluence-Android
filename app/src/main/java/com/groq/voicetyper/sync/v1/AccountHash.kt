package com.groq.voicetyper.sync.v1

import java.security.MessageDigest

/**
 * Account identity hash — MUST match the Windows implementation exactly:
 * full 64-hex lowercase SHA-256 of [normalizeAccountKey]. Both platforms derive
 * the same value so per-account local state partitions identically.
 *
 * F10 — the fold rule lives here, in ONE place, because the platforms had
 * drifted: Windows compared account keys with `key.trim().eq_ignore_ascii_case(…)`
 * (ASCII-only) while Android compared with `trim().lowercase()` (full Unicode).
 * For a non-ASCII local part — `ÜSER@example.com` vs `üser@example.com` —
 * Windows called that two identities and REPAIRED/re-bound, while Android called
 * it one identity. One account, two behaviors, and a silent split-brain on any
 * non-ASCII address.
 *
 * The rule is now full-Unicode case folding on BOTH sides, which is also what
 * the hash already did — so the comparison and the partition key can no longer
 * disagree with each other:
 *
 *   Android  : email.trim().lowercase()                 (Locale.ROOT)
 *   Windows  : email.trim().to_lowercase()              (Rust, full Unicode)
 *   Windows helper: metadata::normalize_account_key
 *
 * Deliberately NOT `equals(ignoreCase = true)` on the Kotlin side of that pair:
 * it is an ASCII comparison with no trimming and no guarantee of matching
 * Rust's mapping table.
 */
object AccountHash {

    /**
     * The single account-key fold rule shared by the hash, the sign-in identity
     * gate ([com.groq.voicetyper.sync.identityDivergenceOutcome]) and the
     * mid-pass refreshed-token check.
     *
     * `String.lowercase()` is `Locale.ROOT`-scoped in Kotlin, so this is stable
     * regardless of the device locale — a Turkish locale must not fold `I` to a
     * dotless `ı` and re-key a partition.
     *
     * The TRIM is [unicodeTrim] (the Unicode `White_Space` set), not Kotlin's
     * `String.trim()`. This is the exact trim-divergence class that `businessKey`
     * already suffered, and the two sets differ in BOTH directions: Kotlin's
     * `Char.isWhitespace()` EXCLUDES NEL U+0085 (so a NEL-padded address would
     * hash differently here than on Windows) and INCLUDES the C0 separators
     * U+001C-U+001F (so those would be trimmed here but not there). Either
     * direction yields a different SHA-256, hence a different `account_hash`,
     * hence a permanently divergent local partition — total, not per-key.
     * Windows `metadata.rs::normalize_account_key` uses Rust `str::trim`, i.e.
     * the same `White_Space` set.
     */
    fun normalizeAccountKey(email: String): String = unicodeTrim(email).lowercase()

    fun of(email: String?): String? {
        if (email.isNullOrBlank()) return null
        val normalized = normalizeAccountKey(email)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}