package com.groq.voicetyper.sync.v1

import java.security.MessageDigest

/**
 * Account identity hash — MUST match the Windows implementation exactly:
 * full 64-hex lowercase SHA-256 of lower(trim(email)). Both platforms derive
 * the same value so per-account local state partitions identically.
 */
object AccountHash {
    fun of(email: String?): String? {
        if (email.isNullOrBlank()) return null
        val normalized = email.trim().lowercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
