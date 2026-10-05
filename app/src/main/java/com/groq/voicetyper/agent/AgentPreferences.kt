package com.groq.voicetyper.agent

import android.content.Context
import android.content.SharedPreferences
import com.groq.voicetyper.sync.SyncAccounts
import com.groq.voicetyper.sync.v1.AccountScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Custom agents for AI Agent Mode. The built-in multipurpose agent is fixed
 * and untouched; customs are named system-prompt hints that run through the
 * same fixed JSON action contract in [com.groq.voicetyper.CommandProcessor].
 *
 * Storage mirrors the cleanup custom styles: one JSON document plus a default
 * id in fluence_prefs. Unknown or deleted ids always resolve to built-in, and
 * unknown ids are never written.
 */
object AgentPreferences {
    private const val PREFS_NAME = "fluence_prefs"
    private const val KEY_CUSTOM_AGENTS = "agent_custom_styles"
    private const val KEY_DEFAULT_AGENT = "agent_default_id"

    const val MAX_AGENT_NAME_LENGTH = 30
    const val MAX_AGENT_HINT_LENGTH = 1000

    const val ID_BUILT_IN = "builtin"
    const val NAME_BUILT_IN = "Fluence Agent"

    /**
     * User-facing explanation shown on a legacy agent/style that is listed but
     * not runnable.
     *
     * It has to say *why* it is unavailable and that nothing was lost, because
     * the alternative reading — an agent that vanished the moment the user signed
     * in — is indistinguishable from data loss.
     */
    const val LEGACY_UNAVAILABLE_REASON =
        "Created before you had an account, so it isn't linked to one. " +
            "Still here and not deleted, but it can't be used while you're signed in."

    fun sanitizeHint(hint: String): String {
        val trimmed = hint.trim()
        if (trimmed.isEmpty()) return ""
        return if (trimmed.length > MAX_AGENT_HINT_LENGTH) trimmed.substring(0, MAX_AGENT_HINT_LENGTH) else trimmed
    }

    fun sanitizeName(name: String): String {
        return name.replace("\r", "").replace("\n", " ").trim().take(MAX_AGENT_NAME_LENGTH).trim()
    }

    data class CustomAgent(
        val id: String,
        val name: String,
        val hint: String,
        /**
         * Whether this record may be selected and executed right now.
         *
         * False only for a preserved legacy record listed while an account is
         * signed in. It stays visible so a user's own custom prompts do not look
         * deleted, but its provenance is unknown, so it is never run and never
         * uploaded. True for every account-owned record.
         */
        val available: Boolean = true,
        /** Why [available] is false. Null when it is true. */
        val unavailableReason: String? = null
    )

    data class ResolvedAgent(
        val id: String,
        val isBuiltIn: Boolean,
        /** Null for built-in. Sanitized and capped hint for customs. */
        val hint: String?
    )

    fun isBuiltIn(agentId: String): Boolean = agentId == ID_BUILT_IN

    // ─────────────────────────────────────────────────────────────────
    // STAGE 6 — the single admission gate for the runtime read path
    // ─────────────────────────────────────────────────────────────────

    /**
     * The account whose namespace local reads, writes and deletes use.
     *
     * This is deliberately ONE predicate for all three, not a split between
     * "display identity" and "write identity": display, selection and execution
     * must agree (the STAGE 6 headline invariant), and routing writes somewhere
     * reads never look would recreate the invisible-record bug in reverse.
     *
     * The value is the best-known local identity: the verified email once a
     * sync pass has proven it against the access token, otherwise the persisted
     * sign-in email. `SyncAccounts.refresh` performs that fallback and never
     * downgrades an already-verified identity, so calling it here is safe at any
     * time — including on a cold start before any pass has run, and for a user
     * who never enables sync at all.
     *
     * Two deliberate boundaries this does NOT cross:
     *  - Uploads still demand live token verification independently (the sync
     *    engine loads only the verified hash's file). A stale persisted email
     *    can therefore only leave a record inert and invisible to other
     *    accounts — never uploaded into the wrong partition, never executed
     *    under the wrong identity, because admission uses this same hash
     *    consistently.
     *  - A positively absent sign-in (null) still yields the legacy store with
     *    DEVICE_LOCAL semantics. Nothing about the signed-out path changes.
     */
    private fun localAccountHash(context: Context): String? {
        if (SyncAccounts.cachedAccount == null) {
            SyncAccounts.refresh(context)
        }
        return com.groq.voicetyper.sync.v1.AccountHash.of(SyncAccounts.cachedAccount)
    }

    /**
     * Read the union of the active account's agents and the legacy device-local
     * ones, and return everything the user should SEE, each tagged with whether
     * it may actually run.
     *
     * STAGE 6 put the admission filter here so that no consumer could bypass it,
     * but it also made this function the *execution* list, which meant a
     * preserved legacy agent simply disappeared from the UI while an account was
     * signed in. The record was never deleted and the legacy store is never
     * written by the account module, so the user was shown data loss that did not
     * exist: their own custom system prompts vanished and signing out brought
     * them back. Windows lists unassigned records for the same reason.
     *
     * So display and execution are now separated explicitly rather than by
     * omission:
     *  - this returns the DISPLAY set — union, minus tombstones — and each
     *    record carries [CustomAgent.available];
     *  - [loadRunnableAgents] is the EXECUTION set, derived from this same read.
     *
     * Deriving one from the other is deliberate: a second, independently filtered
     * read is exactly how the two drifted apart in the first place. A legacy
     * record is never auto-migrated, never auto-assigned, and never silently
     * replaced by the Built-in Agent — it is listed, disabled, and explained.
     */
    fun loadCustomAgents(context: Context): List<CustomAgent> {
        return try {
            val hash = localAccountHash(context)
            val legacy = parseAgentsJson(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_CUSTOM_AGENTS, "") ?: ""
            ).map { AccountScope.VisibleAgent.Legacy(it.id, it.name, it.hint) }

            val snapshot = AccountScope.VisibleAgentsSnapshot.load(context, hash, legacy)
            // `displayRecords` already drops tombstones, so a deleted agent is
            // neither listed nor resolvable and deletion is not a lie until the
            // next pass propagates it. Sync never reads this function — it uses
            // the stores directly, where the tombstone survives — so this cannot
            // resurrect anything.
            snapshot.displayRecords().map { record ->
                val runnable = snapshot.isRunnable(record)
                CustomAgent(
                    id = record.id,
                    name = record.name,
                    hint = record.hint,
                    available = runnable,
                    unavailableReason = if (runnable) null else LEGACY_UNAVAILABLE_REASON
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * The EXECUTION set: the subset of [loadCustomAgents] that may run.
     *
     * Every path that resolves an agent to something executable goes through
     * here — [isKnownAgent], [getDefaultAgentId], [setDefaultAgentId] and
     * [resolveActiveAgent] — which is what keeps a visible-but-unavailable legacy
     * record from being run by a stale saved default or a stale per-app
     * selection.
     */
    private fun loadRunnableAgents(context: Context): List<CustomAgent> =
        loadCustomAgents(context).filter { it.available }

    /**
     * STAGE 6: is this an executable, known agent?
     *
     * A legacy record is displayable but not runnable, so a saved default or
     * selection pointing at one cannot cause it to run; the caller falls back to
     * the Built-in Agent instead.
     */
    fun isKnownAgent(context: Context, agentId: String): Boolean {
        if (agentId == ID_BUILT_IN) return true
        return loadRunnableAgents(context).any { it.id == agentId }
    }

    // ── Default agent ──

    fun getDefaultAgentId(context: Context): String {
        return try {
            val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DEFAULT_AGENT, ID_BUILT_IN) ?: ID_BUILT_IN
            if (isKnownAgent(context, raw)) raw else ID_BUILT_IN
        } catch (_: Exception) {
            ID_BUILT_IN
        }
    }

    fun setDefaultAgentId(context: Context, agentId: String) {
        try {
            if (!isKnownAgent(context, agentId)) return
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_DEFAULT_AGENT, agentId)
                .apply()
        } catch (_: Exception) {
        }
    }

    /**
     * Resolves the effective agent at deliver time. Null, blank, unknown,
     * unavailable or deleted ids fall back to built-in so callers never branch
     * on validity.
     *
     * A listed-but-unavailable legacy agent falls back to the Built-in Agent
     * rather than running: an agent carries an executable system prompt, so
     * running one of unknown provenance under a signed-in identity is exactly the
     * cross-account confusion this phase exists to prevent. The record itself is
     * left untouched.
     */
    fun resolveActiveAgent(context: Context, requestedId: String?): ResolvedAgent {
        if (!requestedId.isNullOrBlank() && !isBuiltIn(requestedId)) {
            val custom = try {
                loadRunnableAgents(context).firstOrNull { it.id == requestedId }
            } catch (_: Exception) {
                null
            }
            if (custom != null && custom.hint.isNotBlank()) {
                return ResolvedAgent(id = custom.id, isBuiltIn = false, hint = custom.hint)
            }
        }
        return ResolvedAgent(id = ID_BUILT_IN, isBuiltIn = true, hint = null)
    }

    // ── Custom agents ──

    fun validateAgentName(context: Context, name: String, id: String? = null): String? {
        val cleanName = sanitizeName(name)
        if (cleanName.isEmpty()) return "Give your agent a name"
        if (cleanName.equals(NAME_BUILT_IN, ignoreCase = true) || isBuiltIn(cleanName)) {
            return "\"$cleanName\" is reserved. Choose another name."
        }
        val current = loadCustomAgents(context)
        if (current.any { it.id != id && it.name.equals(cleanName, ignoreCase = true) }) {
            return "An agent named \"$cleanName\" already exists."
        }
        return null
    }

    fun saveCustomAgent(context: Context, name: String, hint: String, id: String? = null): CustomAgent? {
        val cleanName = sanitizeName(name)
        if (validateAgentName(context, cleanName, id) != null) return null
        val cleanHint = sanitizeHint(hint)
        if (cleanHint.isEmpty()) return null
        return try {
            val hash = localAccountHash(context)
            if (hash != null) {
                // STAGE 6: a signed-in user creates agents under their OWN
                // account. Writing to the legacy store instead would produce an
                // UNASSIGNED record that admission then withholds from execution
                // — the user would create an agent and immediately be unable to
                // run it.
                val targetId = if (id != null && !isBuiltIn(id)) id
                else "agent:" + UUID.randomUUID().toString()
                AccountScope.upsertAgent(context, hash, targetId, cleanName, cleanHint)
                CustomAgent(id = targetId, name = cleanName, hint = cleanHint)
            } else {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = loadCustomAgents(context).toMutableList()
            if (id != null && !isBuiltIn(id)) {
                val idx = current.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    current[idx] = CustomAgent(id = id, name = cleanName, hint = cleanHint)
                } else {
                    current.add(CustomAgent(id = id, name = cleanName, hint = cleanHint))
                }
                writeCustomAgents(prefs, current)
                return current.first { it.id == id }
            }
            if (id != null) return null
            val created = CustomAgent(id = "agent:" + UUID.randomUUID().toString(), name = cleanName, hint = cleanHint)
            current.add(created)
            writeCustomAgents(prefs, current)
            created
            }
        } catch (_: Exception) {
            null
        }
    }

    fun deleteCustomAgent(context: Context, id: String) {
        if (isBuiltIn(id)) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val hash = localAccountHash(context)
            if (hash != null) {
                // Remove from the account store. A legacy record of the same id is
                // deliberately left alone: deleting it here would destroy
                // device-local data that merely shadowed the account copy.
                AccountScope.deleteAgent(context, hash, id)
            } else {
                val remaining = loadCustomAgents(context).filter { it.id != id }
                prefs.edit().putString(KEY_CUSTOM_AGENTS, serializeAgentsJson(remaining)).apply()
            }
            // A deleted default falls back to built-in. The default id itself stays
            // device-local (D1c) even when the agent it names is account-owned.
            if (prefs.getString(KEY_DEFAULT_AGENT, ID_BUILT_IN) == id) {
                prefs.edit().putString(KEY_DEFAULT_AGENT, ID_BUILT_IN).apply()
            }
        } catch (_: Exception) {
        }
    }

    // ── Pure JSON helpers (unit-testable without Android) ──

    internal fun parseAgentsJson(raw: String): List<CustomAgent> {
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<CustomAgent>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").takeIf { it.isNotBlank() && it != "null" } ?: continue
                val name = sanitizeName(o.optString("name")).takeIf { it.isNotBlank() && it != "null" } ?: continue
                val hint = sanitizeHint(o.optString("hint")).takeIf { it.isNotBlank() && it != "null" } ?: continue
                if (isBuiltIn(id)) continue
                out.add(CustomAgent(id = id, name = name, hint = hint))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun serializeAgentsJson(agents: List<CustomAgent>): String {
        val arr = JSONArray()
        for (a in agents) {
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("name", a.name)
                put("hint", a.hint)
            })
        }
        return arr.toString()
    }

    private fun writeCustomAgents(prefs: SharedPreferences, agents: List<CustomAgent>) {
        prefs.edit().putString(KEY_CUSTOM_AGENTS, serializeAgentsJson(agents)).apply()
    }
}
