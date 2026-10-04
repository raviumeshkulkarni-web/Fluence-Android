package com.groq.voicetyper.sync.v1

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Account-scoped custom agents and styles — **local storage only**.
 *
 * Android counterpart of the Windows `account_scope` module. Same rules, same
 * reasons.
 *
 * # Why a separate preferences FILE, not a new key in `fluence_prefs`
 *
 * Legacy agents live under `fluence_prefs["agent_custom_styles"]` and legacy
 * styles under `fluence_prefs["ai_cleanup_custom_styles"]`. An old build only
 * ever opens `fluence_prefs` and only ever touches those keys, so a sibling key
 * would in practice survive — but a separate preferences *file* makes the
 * guarantee structural rather than conventional: a build that does not know this
 * module cannot open the file at all, cannot rewrite it, and cannot clear it.
 * That is the same reasoning as the Windows `agents.account-<hash>.json`
 * layout, chosen there because serde drops unknown fields on round-trip.
 *
 * # Ownership rules (D1)
 *
 * - Legacy agents/styles are **device-local** and stay exactly where they are.
 *   They are never adopted into an account, never re-stamped, and never
 *   attributed to a signed-in user. Their ids, names and hints are preserved
 *   byte-for-byte.
 * - Account records live only in that account's file, keyed by the existing
 *   64-lowercase-hex account-hash convention.
 * - Unowned legacy records stay visible to any signed-in account (D1a).
 * - `default_id` and `package_overrides` stay device-local in the legacy store
 *   (D1c). Nothing here reads or writes them.
 *
 * # Record shape
 *
 * Carries the same fields as a dictionary item (`syncId`, `updatedAt`,
 * `deviceId`, `deletedAt`) so a future Drive domain is additive rather than a
 * second format. `deletedAt` is the sync contract's tombstone.
 */
internal object AccountScope {

    /** Legacy preferences file. Never written by this module. */
    const val LEGACY_PREFS = "fluence_prefs"

    private const val AGENTS_LEGACY_KEY = "agent_custom_styles"
    private const val STYLES_LEGACY_KEY = "ai_cleanup_custom_styles"
    private const val ACCOUNT_FILE_PREFIX = "fluence_acct_"
    private const val AGENTS_KEY = "agents"
    private const val STYLES_KEY = "styles"
    private const val CORRUPT_PREFIX = "corrupt."

    /**
     * Strictly the documented convention: SHA-256 hex, so exactly 64 lowercase
     * hex characters. Being strict prevents a *truncated* hash from silently
     * creating a second store for one account, which surfaces much later as
     * apparent data loss, and keeps any path/account confusion unrepresentable.
     */
    fun validAccountHash(hash: String?): Boolean =
        hash != null &&
            hash.length == 64 &&
            hash.all { it in '0'..'9' || it in 'a'..'f' }

    /**
     * The account-scoped preferences file for [kind]. Never equals
     * [LEGACY_PREFS], so legacy data and account data are physically separate.
     */
    fun accountPrefsName(kind: String, accountHash: String): String {
        require(validAccountHash(accountHash)) {
            "account hash must be 64 lowercase hex characters"
        }
        return "$ACCOUNT_FILE_PREFIX${kind}_$accountHash"
    }

    fun agentsPrefsName(accountHash: String) = accountPrefsName(AGENTS_KEY, accountHash)

    fun stylesPrefsName(accountHash: String) = accountPrefsName(STYLES_KEY, accountHash)

    // ---------------------------------------------------------------- records

    data class AccountAgent(
        val id: String,
        val name: String,
        val hint: String,
        val syncId: String? = null,
        val updatedAt: Long? = null,
        val deviceId: String? = null,
        val deletedAt: Long? = null,
        /**
         * Local-only: this row has a mutation the ledger has not accepted yet.
         *
         * Mirrors the Windows [AccountAgent.dirty] and the older domains'
         * explicit `dirty` column. It must be a real flag rather than something
         * derived from [syncId]/[updatedAt]: once a record has synced once it
         * carries both forever, so "never stamped" cannot double as "edited
         * since last sync" — deriving it that way left every post-first-sync
         * edit permanently invisible to upload.
         *
         * NOT part of the wire: [DomainSerializer] owns the remote format.
         */
        val dirty: Boolean = false
    )

    data class AccountStyle(
        val id: String,
        val name: String,
        val hint: String,
        val syncId: String? = null,
        val updatedAt: Long? = null,
        val deviceId: String? = null,
        val deletedAt: Long? = null,
        /** Local-only pending-mutation flag. See [AccountAgent.dirty]. */
        val dirty: Boolean = false
    )

    // ------------------------------------------------------------ serialising

    private fun agentToJson(a: AccountAgent) = JSONObject().apply {
        put("id", a.id)
        put("name", a.name)
        put("hint", a.hint)
        a.syncId?.let { put("syncId", it) }
        a.updatedAt?.let { put("updatedAt", it) }
        a.deviceId?.let { put("deviceId", it) }
        a.deletedAt?.let { put("deletedAt", it) }
        if (a.dirty) put("dirty", true)
    }

    private fun agentFromJson(o: JSONObject) = AccountAgent(
        id = o.optString("id"),
        name = o.optString("name"),
        hint = o.optString("hint", ""),
        syncId = o.optStringOrNull("syncId"),
        updatedAt = o.optLongOrNull("updatedAt"),
        deviceId = o.optStringOrNull("deviceId"),
        deletedAt = o.optLongOrNull("deletedAt"),
        dirty = o.optBoolean("dirty", false)
    )

    private fun styleToJson(s: AccountStyle) = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("hint", s.hint)
        s.syncId?.let { put("syncId", it) }
        s.updatedAt?.let { put("updatedAt", it) }
        s.deviceId?.let { put("deviceId", it) }
        s.deletedAt?.let { put("deletedAt", it) }
        if (s.dirty) put("dirty", true)
    }

    private fun styleFromJson(o: JSONObject) = AccountStyle(
        id = o.optString("id"),
        name = o.optString("name"),
        hint = o.optString("hint", ""),
        syncId = o.optStringOrNull("syncId"),
        updatedAt = o.optLongOrNull("updatedAt"),
        deviceId = o.optStringOrNull("deviceId"),
        deletedAt = o.optLongOrNull("deletedAt"),
        dirty = o.optBoolean("dirty", false)
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (isNull(key)) null else optLong(key)

    internal fun serializeAgents(agents: List<AccountAgent>): String {
        val arr = JSONArray()
        agents.forEach { arr.put(agentToJson(it)) }
        return arr.toString()
    }

    internal fun serializeStyles(styles: List<AccountStyle>): String {
        val arr = JSONArray()
        styles.forEach { arr.put(styleToJson(it)) }
        return arr.toString()
    }

    /**
     * Per-record tolerance: a single malformed entry is skipped rather than
     * failing the whole payload, matching the dictionary path. A payload that is
     * not a JSON array at all is the corrupt case, handled by the caller.
     */
    internal fun parseAgents(raw: String?): List<AccountAgent>? {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<AccountAgent>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                out.add(agentFromJson(o))
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseStyles(raw: String?): List<AccountStyle>? {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<AccountStyle>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                out.add(styleFromJson(o))
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------- file access

    private fun readList(
        context: Context,
        prefsName: String,
        key: String
    ): String? {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val raw = prefs.getString(key, null) ?: return null
        return raw
    }

    /**
     * On a corrupt account payload the raw text is preserved under a `corrupt.N`
     * key **and the offending value is removed in the same atomic commit**.
     *
     * Clearing matters: SharedPreferences has no rename, so a key-based
     * "rotate aside" that leaves the bad value in place would re-detect and
     * re-quarantine the same payload on *every* read — unbounded growth of the
     * file, which Android loads entirely into memory. Removing it matches the
     * Windows `backup_corrupt_account` rotate-aside semantics, where the next
     * read finds nothing. One corruption event therefore costs one entry.
     *
     * The legacy `fluence_prefs` file is never opened for writing by this
     * module, so device-local data cannot be damaged here.
     */
    private fun quarantine(context: Context, prefsName: String, key: String, raw: String) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        var n = 1
        while (prefs.contains("$CORRUPT_PREFIX$n")) n++
        prefs.edit()
            .putString("$CORRUPT_PREFIX$n", raw)
            .remove(key)
            .apply()
    }

    fun loadAgents(context: Context, accountHash: String?): List<AccountAgent> {
        if (!validAccountHash(accountHash)) return emptyList()
        val prefsName = agentsPrefsName(accountHash!!)
        val raw = readList(context, prefsName, AGENTS_KEY) ?: return emptyList()
        return parseAgents(raw) ?: run {
            quarantine(context, prefsName, AGENTS_KEY, raw)
            emptyList()
        }
    }

    fun loadStyles(context: Context, accountHash: String?): List<AccountStyle> {
        if (!validAccountHash(accountHash)) return emptyList()
        val prefsName = stylesPrefsName(accountHash!!)
        val raw = readList(context, prefsName, STYLES_KEY) ?: return emptyList()
        return parseStyles(raw) ?: run {
            quarantine(context, prefsName, STYLES_KEY, raw)
            emptyList()
        }
    }

    fun saveAgents(context: Context, accountHash: String, agents: List<AccountAgent>) {
        val prefsName = agentsPrefsName(accountHash)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString(AGENTS_KEY, serializeAgents(agents)).apply()
    }

    fun saveStyles(context: Context, accountHash: String, styles: List<AccountStyle>) {
        val prefsName = stylesPrefsName(accountHash)
        context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            .edit().putString(STYLES_KEY, serializeStyles(styles)).apply()
    }

    // ------------------------------------------------------------ read union

    /**
     * A record visible to the user, tagged with where it came from.
     *
     * Mirrors Windows `account_scope::VisibleRecord`.
     */
    sealed interface VisibleAgent {
        val id: String
        val name: String
        val hint: String
        val deletedAt: Long?

        /** Device-local record from the legacy store; never adopted. */
        data class Legacy(
            override val id: String,
            override val name: String,
            override val hint: String
        ) : VisibleAgent {
            override val deletedAt: Long? = null
        }

        /** Record owned by the active account. */
        data class Owned(override val id: String, override val name: String, override val hint: String, val syncId: String?, val updatedAt: Long?, val deviceId: String?, override val deletedAt: Long?) : VisibleAgent
    }

    /** Style counterpart of [VisibleAgent], with identical precedence rules. */
    sealed interface VisibleStyle {
        val id: String
        val name: String
        val hint: String
        val deletedAt: Long?

        data class Legacy(
            override val id: String,
            override val name: String,
            override val hint: String
        ) : VisibleStyle {
            override val deletedAt: Long? = null
        }

        data class Owned(override val id: String, override val name: String, override val hint: String, val syncId: String?, val updatedAt: Long?, val deviceId: String?, override val deletedAt: Long?) : VisibleStyle
    }

    // ---------------------------------------------------------------
    // STAGE 5 — three-state local admission
    // ---------------------------------------------------------------

    /**
     * Provenance of a locally stored Agent/Style record.
     *
     * A record is classified once, at admission, and every runtime consumer sees
     * only admitted records. Classification is derived from **where the record
     * physically lives**, never from who is signed in:
     *
     *  - a record in the active account's own store is [OWNED] — STAGE 3
     *    partitioned both the local file and the Drive partition by the same
     *    token-derived hash, so "in this file" *is* "owned by this account";
     *  - a record in the legacy pre-account store is [UNASSIGNED] — its
     *    provenance is genuinely unknown, and it predates accounts entirely.
     *
     * [DEVICE_LOCAL] is part of the contract so the rules are pinned and pinned
     * by test, but **it deliberately has no producer today**: manufacturing one
     * would require an assignment/toggle UX that the product does not have, and
     * the brief forbids inventing one. It is the correct home for a future
     * explicit "keep on this device" choice.
     */
    enum class Admission {
        /** Visible, selectable, executable, and syncable. */
        OWNED,

        /** Visible and executable on this device. NEVER auto-uploaded, NEVER silently reassigned. */
        DEVICE_LOCAL,

        /**
         * Provenance unknown. While an account is signed in it is **not
         * executable and not uploaded**. The record is preserved intact and
         * becomes [OWNED] only through an explicit assignment, which does not
         * exist yet — so in practice it stays preserved and inert rather than
         * being silently adopted.
         */
        UNASSIGNED,
    }

    /**
     * Classify one record against the active account.
     *
     * Three inputs, not two, and the distinction is load-bearing:
     *
     *  - **genuinely signed out** ([signedOut]) — no account is configured. There
     *    is no identity to be confused with, so nothing is withheld and legacy
     *    records stay usable offline.
     *  - **valid account hash** — normal signed-in case.
     *  - **non-blank but INVALID hash** — an identity we cannot verify. This is
     *    an *error* state, not a signed-out state, so it must NOT be allowed to
     *    unlock unknown-provenance records. It resolves exactly like the valid
     *    case: owned records stay owned, unassigned records stay withheld.
     *
     * Collapsing the third case into "signed out" would mean a corrupt or
     * truncated hash silently made every legacy agent executable under an
     * identity nobody can name — the precise confusion this phase prevents.
     */
    internal fun admitAgent(record: VisibleAgent, activeAccountHash: String?): Admission {
        if (signedOut(activeAccountHash)) return Admission.DEVICE_LOCAL
        return when (record) {
            // Already inside an account's own store: structurally owned.
            is VisibleAgent.Owned -> Admission.OWNED
            // Pre-account store: unknown provenance, never auto-adopted.
            is VisibleAgent.Legacy -> Admission.UNASSIGNED
        }
    }

    /** Style counterpart of [admitAgent], with identical rules. */
    internal fun admitStyle(record: VisibleStyle, activeAccountHash: String?): Admission {
        if (signedOut(activeAccountHash)) return Admission.DEVICE_LOCAL
        return when (record) {
            is VisibleStyle.Owned -> Admission.OWNED
            is VisibleStyle.Legacy -> Admission.UNASSIGNED
        }
    }

    /**
     * True only when there is positively no account.
     *
     * A non-blank value that is not a valid hash is NOT signed out: it is an
     * unverifiable identity and must be handled fail-closed.
     */
    private fun signedOut(activeAccountHash: String?): Boolean =
        activeAccountHash == null || activeAccountHash.isBlank()

    /**
     * Whether a record in this admission state may be shown and run.
     *
     * [Admission.UNASSIGNED] is withheld **only while an account is signed in**:
     * an agent carries an executable system prompt, so running one of unknown
     * provenance under a signed-in identity is exactly the cross-account
     * confusion this phase exists to prevent. Offline there is no identity to
     * be confused with, so withholding would only brick the app.
     */
    fun Admission.isAdmissible(): Boolean = this != Admission.UNASSIGNED

    /**
     * Whether a record in this state may be SHOWN in a list.
     *
     * Deliberately broader than [isAdmissible]. Hiding an
     * [Admission.UNASSIGNED] record outright makes an existing user's legacy
     * agents appear to have been deleted — they are not deleted, and the legacy
     * store is never written by this module — and a user who believes their
     * custom system prompts were destroyed does not come back.
     *
     * So the record stays VISIBLE and is rendered non-runnable, while
     * [isAdmissible] continues to gate execution. This is not a security
     * relaxation: an unassigned record is still never executed and still never
     * uploaded, it just stops looking like data loss.
     */
    fun Admission.isDisplayable(): Boolean = true

    /** Only [OWNED] records are ever uploaded. Never DEVICE_LOCAL, never UNASSIGNED. */
    fun Admission.isSyncable(): Boolean = this == Admission.OWNED

    /**
     * The single admission gate for agents: raw union in, admitted records out.
     *
     * Every runtime consumer must read through this (or a snapshot built from
     * it) rather than walking [loadVisibleAgents] directly. See STAGE 6.
     */
    fun admittedAgents(
        union: List<VisibleAgent>,
        activeAccountHash: String?
    ): List<VisibleAgent> = union.filter { admitAgent(it, activeAccountHash).isAdmissible() }

    /** Style counterpart of [admittedAgents]. */
    fun admittedStyles(
        union: List<VisibleStyle>,
        activeAccountHash: String?
    ): List<VisibleStyle> = union.filter { admitStyle(it, activeAccountHash).isAdmissible() }

    /**
     * Read-union of device-local legacy agents and the active account's agents.
     *
     * **Precedence: account wins on id collision.** A signed-in user sees the
     * record they own for a given id, not a shadowed device-local one. D1a keeps
     * unowned legacy data *visible*; it does not make unowned data outrank
     * account-owned data.
     *
     * A shadowed legacy record is **not** deleted, mutated, or adopted — this is
     * a pure function, so shadowing cannot touch the input. It becomes visible
     * again when no account is signed in, or a different account is signed in.
     *
     * Order is deterministic: account records in stored order, then legacy
     * records, deduplicated by id in favour of the account copy.
     */
    internal fun unionAgents(
        legacy: List<VisibleAgent.Legacy>,
        account: List<AccountAgent>
    ): List<VisibleAgent> {
        val out = mutableListOf<VisibleAgent>()
        val seen = HashSet<String>()
        account.forEach { a ->
            if (seen.add(a.id)) {
                out.add(
                    VisibleAgent.Owned(
                        id = a.id, name = a.name, hint = a.hint,
                        syncId = a.syncId, updatedAt = a.updatedAt,
                        deviceId = a.deviceId, deletedAt = a.deletedAt
                    )
                )
            }
        }
        legacy.forEach { l ->
            if (seen.add(l.id)) out.add(l)
        }
        return out
    }

    /** Style counterpart of [unionAgents], with identical precedence rules. */
    internal fun unionStyles(
        legacy: List<VisibleStyle.Legacy>,
        account: List<AccountStyle>
    ): List<VisibleStyle> {
        val out = mutableListOf<VisibleStyle>()
        val seen = HashSet<String>()
        account.forEach { s ->
            if (seen.add(s.id)) {
                out.add(
                    VisibleStyle.Owned(
                        id = s.id, name = s.name, hint = s.hint,
                        syncId = s.syncId, updatedAt = s.updatedAt,
                        deviceId = s.deviceId, deletedAt = s.deletedAt
                    )
                )
            }
        }
        legacy.forEach { l ->
            if (seen.add(l.id)) out.add(l)
        }
        return out
    }

    /**
     * Load the union of device-local legacy agents and the active account's
     * agents, for the real read path.
     *
     * [legacy] is supplied by the caller from its own store, so this module
     * never imports the agents/styles packages (no dependency cycle) and never
     * becomes a second source of truth for legacy data.
     *
     * [accountHash] is null when signed out, which yields the legacy records
     * only — local-first behaviour with no account involvement. A rejected or
     * corrupt account store contributes nothing and never touches legacy data.
     *
     * Mirrors Windows `load_visible_agents`.
     */
    fun loadVisibleAgents(
        context: Context,
        accountHash: String?,
        legacy: List<VisibleAgent.Legacy>
    ): List<VisibleAgent> {
        val account = if (validAccountHash(accountHash)) {
            loadAgents(context, accountHash)
        } else {
            emptyList()
        }
        return unionAgents(legacy, account)
    }

    /** Style counterpart of [loadVisibleAgents]. */
    fun loadVisibleStyles(
        context: Context,
        accountHash: String?,
        legacy: List<VisibleStyle.Legacy>
    ): List<VisibleStyle> {
        val account = if (validAccountHash(accountHash)) {
            loadStyles(context, accountHash)
        } else {
            emptyList()
        }
        return unionStyles(legacy, account)
    }

    /**
     * `isKnownAgent` evaluated over the union, preserving the existing rule:
     * the built-in id is always known, plus any id visible in the union.
     *
     * Operates on the SAME union the UI lists, so resolution and display can
     * never disagree. No second account-specific mechanism is introduced.
     */
    fun isKnownAgentIn(union: List<VisibleAgent>, agentId: String): Boolean =
        agentId == LEGACY_BUILT_IN_ID || union.any { it.id == agentId }

    /**
     * Resolve an agent id against the union, mirroring the existing
     * `resolveActiveAgent` contract: built-in for an unknown or absent id,
     * otherwise the visible record's hint.
     *
     * A tombstoned account record still resolves by id (it wins the collision),
     * which is what lets a delete propagate instead of silently falling back to
     * a shadowed legacy record.
     */
    fun resolveAgentIn(union: List<VisibleAgent>, agentId: String?): VisibleAgent? {
        val requested = agentId ?: return null
        if (requested == LEGACY_BUILT_IN_ID) return null
        return union.firstOrNull { it.id == requested }
    }

    /** The built-in agent id, kept here so this module needs no agent import. */
    const val LEGACY_BUILT_IN_ID = "builtin"

    /**
     * STAGE 6 — the union the runtime read path actually uses.
     *
     * Unlike [loadVisibleAgents], this reads the account store itself so callers
     * cannot forget to supply the legacy half, which is exactly the mistake that
     * let the whole UI read the legacy store alone for its entire life.
     *
     * [accountHash] null (signed out, or identity not yet token-verified)
     * yields the legacy records only, and they are then [Admission.DEVICE_LOCAL]
     * and therefore runnable.
     */
    fun unionAgentsFor(
        legacy: List<VisibleAgent.Legacy>,
        accountHash: String?,
        context: Context,
    ): List<VisibleAgent> = unionAgents(legacy, loadAgents(context, accountHash))

    /** Style counterpart of [unionAgentsFor]. */
    fun unionStylesFor(
        legacy: List<VisibleStyle.Legacy>,
        accountHash: String?,
        context: Context,
    ): List<VisibleStyle> = unionStyles(legacy, loadStyles(context, accountHash))

    /**
     * STAGE 6 — create or update an agent in the signed-in account's own store.
     *
     * Writing here is what makes a newly created agent [Admission.OWNED] and
     * therefore immediately runnable. [syncId]/[updatedAt] are left null so the
     * next sync pass stamps them, which is also what marks the record dirty and
     * gets it uploaded.
     *
     * Refuses an invalid hash: a record must never be written somewhere that is
     * not partitioned by a verified identity.
     */
    fun upsertAgent(
        context: Context,
        accountHash: String,
        id: String,
        name: String,
        hint: String,
    ) {
        if (!validAccountHash(accountHash)) return
        val current = loadAgents(context, accountHash).toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        // dirty=true on BOTH paths: an edit to an already-synced row must be
        // re-stamped with a fresh revision and uploaded on the next pass.
        if (idx >= 0) current[idx] = current[idx].copy(name = name, hint = hint, dirty = true)
        else current.add(AccountAgent(id = id, name = name, hint = hint, dirty = true))
        saveAgents(context, accountHash, current)
    }

    /**
     * STAGE 6 — soft-delete an agent in the account's own store.
     *
     * Writes a tombstone rather than removing the row, so the delete propagates
     * to the account's other devices instead of being resurrected by their next
     * upload. A no-op for an invalid hash.
     */
    fun deleteAgent(context: Context, accountHash: String, id: String) {
        if (!validAccountHash(accountHash)) return
        val current = loadAgents(context, accountHash)
        if (current.none { it.id == id }) return
        saveAgents(
            context, accountHash,
            current.map {
                if (it.id == id) it.copy(deletedAt = System.currentTimeMillis(), dirty = true) else it
            }
        )
    }

    /**
     * Style counterpart of [upsertAgent]: create or update a style in the
     * signed-in account's own store, leaving sync metadata null for the next
     * pass to stamp.
     */
    fun upsertStyle(
        context: Context,
        accountHash: String,
        id: String,
        name: String,
        hint: String,
    ) {
        if (!validAccountHash(accountHash)) return
        val current = loadStyles(context, accountHash).toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx >= 0) current[idx] = current[idx].copy(name = name, hint = hint, dirty = true)
        else current.add(AccountStyle(id = id, name = name, hint = hint, dirty = true))
        saveStyles(context, accountHash, current)
    }

    /** Style counterpart of [deleteAgent]: tombstone, never row-removal. */
    fun deleteStyle(context: Context, accountHash: String, id: String) {
        if (!validAccountHash(accountHash)) return
        val current = loadStyles(context, accountHash)
        if (current.none { it.id == id }) return
        saveStyles(
            context, accountHash,
            current.map { if (it.id == id) it.copy(deletedAt = System.currentTimeMillis(), dirty = true) else it }
        )
    }

    /**
     * A single read snapshot of visible agents.
     *
     * Mirrors Windows `VisibleAgents`. Wiring condition 1: the union is read
     * **once** and the same list serves both listing and resolution, so a
     * re-read between them — the only route to display/resolution divergence —
     * is unrepresentable rather than a rule callers must remember.
     */
    class VisibleAgentsSnapshot internal constructor(
        private val union: List<VisibleAgent>,
        private val accountHash: String?
    ) {
        /**
         * An account record only means something with a valid account hash.
         *
         * Production already guarantees this ([unionAgentsFor] yields no account
         * records without one, because [loadAgents] returns empty for an invalid
         * hash). Enforcing it here as well means a hand-assembled union, or a
         * snapshot reused across a sign-out, still cannot leak account records
         * into a session that has no verified identity.
         */
        private fun hasVerifiedAccount(): Boolean = validAccountHash(accountHash)
        /** Read the union once. A null/invalid hash yields legacy only. */
        fun load(context: Context, accountHash: String?, legacy: List<VisibleAgent.Legacy>) =
            VisibleAgentsSnapshot(loadVisibleAgents(context, accountHash, legacy), accountHash)

        /**
         * The full union, tombstones INCLUDED.
         *
         * This is what sync must read: a tombstone is a real record that
         * prevents resurrection, so it must survive into the merge. Do not
         * filter here.
         */
        fun records(): List<VisibleAgent> = union

        /**
         * Records a runtime consumer may execute.
         *
         * STAGE 6: the admission gate. Display, selection, execution, previews
         * and the LLM/cleanup paths all resolve through this snapshot, so no
         * consumer can reach an unadmitted record by going around it.
         *
         * Defence in depth: an [VisibleAgent.Owned] record is only meaningful
         * when the snapshot has a VALID account hash. In production
         * [unionAgentsFor] already yields no account records without one
         * ([loadAgents] returns empty for an invalid hash), so this is
         * belt-and-braces — but it means a caller that assembles a union by hand,
         * or reuses a snapshot across a sign-out, cannot leak account records
         * into a signed-out session.
         */
        fun admitted(): List<VisibleAgent> = admittedAgents(
            union.filter { hasVerifiedAccount() || it is VisibleAgent.Legacy },
            accountHash
        )

        /**
         * STAGE 6: is this id an executable agent?
         *
         * An unassigned (legacy) record is displayable but NOT known-executable,
         * so a default or a saved selection pointing at one cannot run it.
         */
        fun isKnown(agentId: String) =
            agentId == LEGACY_BUILT_IN_ID || admitted().any { it.id == agentId }

        /**
         * Resolve an id for EXECUTION, preserving existing builtin behaviour
         * (never an error): built-in for the built-in id, an absent id, an
         * unknown id, **or a tombstoned record**.
         *
         * A tombstone wins its id in the union, so treating it as unresolvable
         * is what stops a deleted agent producing a usable hint — and stops it
         * falling through to a shadowed device-local record.
         *
         * STAGE 6: an [Admission.UNASSIGNED] record resolves to null here, so it
         * can never supply a hint to the LLM or the cleanup path.
         */
        fun resolve(agentId: String?): VisibleAgent? {
            val found = resolveAgentIn(admitted(), agentId) ?: return null
            if (found is VisibleAgent.Owned && found.deletedAt != null) return null
            return found
        }

        /** Hint for the execution path; null means "use the built-in". */
        fun hint(agentId: String?): String? = resolve(agentId)?.hint

        /**
         * Display projection: **tombstones are omitted**, unassigned records are
         * **kept but not runnable**.
         *
         * A deleted agent must never render as a listed, selectable agent, and
         * the marker must not leak into the UI. `records()` above keeps the
         * tombstone for sync; only this projection hides it.
         *
         * STAGE 6: hiding an unassigned record would make a legacy agent look
         * deleted to a user whose data is in fact intact, so it stays listed and
         * the UI renders it non-runnable via [admissionOf].
         */
        fun displayRecords(): List<VisibleAgent> {
            val verified = hasVerifiedAccount()
            return union.filter { record ->
                when (record) {
                    is VisibleAgent.Legacy -> true
                    // An account record with no verified account has no owner to
                    // be attributed to, so it is not displayed either.
                    is VisibleAgent.Owned -> verified && record.deletedAt == null
                }
            }
        }

        /** Admission state of a listed record, so the UI can render it honestly. */
        fun admissionOf(record: VisibleAgent): Admission {
            // An account record without a verified account has no owner to be
            // attributed to. Reporting it OWNED (or DEVICE_LOCAL-runnable) here
            // would let a future consumer execute it on the strength of this
            // answer alone, bypassing the `admitted()`/`displayRecords()` gates.
            if (!hasVerifiedAccount() && record is VisibleAgent.Owned) return Admission.UNASSIGNED
            return admitAgent(record, accountHash)
        }

        /** True when this listed record may actually be run. */
        fun isRunnable(record: VisibleAgent): Boolean =
            admissionOf(record).isAdmissible() &&
                !(record is VisibleAgent.Owned && record.deletedAt != null)

        companion object {
            /** Build a snapshot from an already-computed union. */
            internal fun forTest(
                union: List<VisibleAgent>,
                accountHash: String? = null,
            ) = VisibleAgentsSnapshot(union, accountHash)
        }
    }

    /** Exposed for tests: the legacy keys this module must never write. */
    internal fun legacyKeys() = listOf(AGENTS_LEGACY_KEY, STYLES_LEGACY_KEY)

    internal fun agentsKey() = AGENTS_KEY
    internal fun stylesKey() = STYLES_KEY
    internal fun corruptPrefix() = CORRUPT_PREFIX
}
