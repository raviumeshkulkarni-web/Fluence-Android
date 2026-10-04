package com.groq.voicetyper.sync.v1

import java.security.MessageDigest

/**
 * Phase 6 STAGE 3 — structural remote account partitioning.
 *
 * ## Why
 *
 * The Drive `appDataFolder` is keyed by the Google identity, not by the email.
 * Two different Google accounts therefore have *separate* appDataFolders, but a
 * single Google account can hold the data for several Fluence identities (e.g.
 * after an email rename, or if two emails map to one Google login). The Agent
 * and Style domains were POOLED at `fluence/v1/agents.json`, so that data had
 * no structural owner.
 *
 * STAGE 3 moves **only** Agents and Styles into a per-account subfolder:
 *
 * ```
 * appDataFolder/fluence/v1/acct-<accountHash>/agents.json
 * appDataFolder/fluence/v1/acct-<accountHash>/styles.json
 * ```
 *
 * This is the adjudicated architecture. It needs no `owner_id` field, no
 * envelope change, and no backend. Isolation becomes structural rather than
 * bookkeeping-driven: account A cannot even name B's location, so "A adopts
 * B's data" stops being a policy question.
 *
 * ## Why the path segment is a separately derived value
 *
 * The folder segment is NOT the account hash. Writing `acct-<accountHash>`
 * would embed a bare, unsalted `SHA-256(email)` in a Drive path: the email to
 * value mapping is unkeyed and computable by anyone holding the address, and
 * the same value also appears in `syncAccount` columns, `SyncMetadata` and
 * local account filenames — so one observed value would link a Drive folder to
 * an exported database or a file listing.
 *
 * Instead the segment is a second, tagged derivation:
 *
 * ```
 * pathSegment = SHA-256("fluence/acct-path/v1\0" + accountHash)
 * folder      = "acct-" + pathSegment
 * ```
 *
 * Tagging the hashed *input* is what makes this domain separation. A value seen
 * in a Drive path is then a value that exists nowhere else, so it cannot be
 * correlated with the account hash seen in local storage.
 *
 * ## What this does and does not defend against
 *
 * It DOES: unlink the Drive path segment from the account hash used everywhere
 * else, so observing one does not reveal the other.
 *
 * It does NOT, and cannot: stop an adversary who already knows the victim's
 * email. They can compute the account hash and then the path segment. Every
 * device belonging to an account must converge on one deterministic path, and
 * there is no shared secret available to the client, so determinism plus secrecy
 * is not achievable together. This is a deliberate, documented limit — not an
 * oversight — and the partition key still requires knowledge of the email, so
 * the structural isolation property is unaffected.
 *
 * ## Fail-closed
 *
 * The account hash is validated to be exactly 64 lowercase hex characters
 * before it is used, and the derived segment is a SHA-256 hex digest. Anything
 * else — null, empty, truncated, the legacy 16-hex form, uppercase, or any value
 * containing `/`, `\`, `.` or `..` — yields `null` from every function here,
 * and callers must not create a folder or read or write a file. Because a
 * validated hash cannot contain a path separator or a dot, path construction
 * cannot escape the intended `appDataFolder/fluence/v1/` location; that property
 * is pinned by tests.
 *
 * ## Not partitioned
 *
 * DICTIONARY, SNIPPETS, STATS and SETTINGS deliberately keep their existing
 * flat paths and are **not moved**. Their formats are frozen, and settings are
 * device-global by decision D1b. An old client still finds all four.
 *
 * ## Old-client compatibility
 *
 * An old client lists `fluence/v1` for a file of a given exact name. A
 * *subfolder* named `acct-<hash>` never matches such a name query, and the
 * Agents/Styles domain is additive, so an old client simply never sees this
 * data. It cannot misread it, and it cannot write into it.
 *
 * ## Cross-platform fixture contract
 *
 * The Windows implementation in `src-tauri/src/sync/account_partition.rs`
 * computes the same strings, and the same literals are asserted in both test
 * suites. Any divergence splits every account partition on every device, so it
 * must fail a test rather than surface at runtime.
 */
object AccountPartition {
    /** Domain separator for the per-account folder segment. */
    const val FOLDER_PREFIX = "acct-"

    /**
     * Tag mixed into the hashed input to derive the path segment.
     *
     * MUST stay byte-identical to the Windows constant
     * `account_partition.rs::PATH_DOMAIN_TAG`, including the NUL terminator.
     * A NUL separator is used because it cannot occur inside a hex hash, so the
     * tagged input is unambiguous.
     */
    const val PATH_DOMAIN_TAG = "fluence/acct-path/v1"

    /** Fixed path segments between `appDataFolder` and the partition folder. */
    private const val ROOT = "fluence/v1"

    /**
     * Domains stored inside a per-account partition.
     *
     * Only the additive Agent/Style domains are partitioned. Adding another
     * domain here would move an existing file and break old clients.
     */
    fun isPartitioned(domain: DomainFile): Boolean =
        domain == DomainFile.AGENTS || domain == DomainFile.STYLES

    /**
     * The value that actually appears in the Drive path.
     *
     * Deliberately a *second* derivation, distinct from the account hash, so the
     * path segment is not the same value used in `syncAccount`,
     * `SyncMetadata` and local account filenames.
     */
    fun pathSegment(accountHash: String?): String? {
        if (!AccountScope.validAccountHash(accountHash)) return null
        val tagged = "$PATH_DOMAIN_TAG\u0000$accountHash"
        val digest = MessageDigest.getInstance("SHA-256").digest(tagged.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Partition folder name for [accountHash], or `null` if the hash is not a
     * well-formed account hash.
     *
     * `null` is the fail-closed signal: the caller must not address any folder.
     */
    fun folderName(accountHash: String?): String? =
        pathSegment(accountHash)?.let { FOLDER_PREFIX + it }

    /**
     * Leaf file name, independent of partitioning.
     *
     * Wire names are frozen: this is the single definition both the flat
     * domains and the partitioned Agent/Style domains use.
     */
    fun plainFileName(domain: DomainFile): String = when (domain) {
        DomainFile.DICTIONARY -> "dictionary.json"
        DomainFile.SNIPPETS -> "snippets.json"
        DomainFile.STATS -> "stats.json"
        DomainFile.SETTINGS -> "settings.json"
        // Phase 6 additive files. A client that predates these simply never
        // requests these names, so it ignores the files entirely.
        DomainFile.AGENTS -> "agents.json"
        DomainFile.STYLES -> "styles.json"
    }

    /**
     * Leaf file name for [domain], or `null` when the domain is partitioned and
     * [accountHash] is unusable.
     */
    fun fileName(domain: DomainFile, accountHash: String?): String? {
        if (!isPartitioned(domain)) return plainFileName(domain)
        return if (AccountScope.validAccountHash(accountHash)) plainFileName(domain) else null
    }

    /**
     * Canonical path of the domain file relative to `appDataFolder`, or `null`
     * when a partitioned domain has no usable account hash.
     *
     * This is the cross-platform fixture vector. It is a pure string and is the
     * single source of truth both platforms are tested against.
     */
    fun relativePath(domain: DomainFile, accountHash: String?): String? {
        val name = fileName(domain, accountHash) ?: return null
        val folder = if (isPartitioned(domain)) folderName(accountHash) else null
        return if (folder == null) "$ROOT/$name" else "$ROOT/$folder/$name"
    }

    /**
     * The account partition a device may address, or `null` when signed out or
     * when the token-derived identity is not yet resolved.
     *
     * Failing closed here is what stops an unresolved device from writing into
     * a guessed, shared, or pooled location.
     */
    fun partitionFor(authenticatedAccountHash: String?): String? =
        folderName(authenticatedAccountHash)
}
