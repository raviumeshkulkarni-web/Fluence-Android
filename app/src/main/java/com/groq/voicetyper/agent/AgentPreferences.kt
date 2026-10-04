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
        val hint: String
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
     * ones, and return only what runtime consumers may execute.
     *
     * STAGE 6: every consumer — [AgentsScreen], [FloatingBubbleService],
     * [TranscriptionSessionManager], the formatting and AI-cleanup paths, the
     * bubble dropdown — reaches agents through this function, so putting the
     * admission filter here is what stops any of them from bypassing it.
     *
     * Unassigned (legacy) records are deliberately excluded: they carry an
     * executable system prompt of unknown provenance, so they must not run
     * under a signed-in identity. They are NOT deleted, and the legacy store is
     * never written by the account module, so signing out brings them straight
     * back. See [AccountScope.VisibleAgentsSnapshot.displayRecords] for the
     * list-and-disable projection the UI should use instead of hiding them.
     */
    fun loadCustomAgents(context: Context): List<CustomAgent> {
        return try {
            val hash = localAccountHash(context)
            val legacy = parseAgentsJson(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_CUSTOM_AGENTS, "") ?: ""
            ).map { CustomAgent(id = it.id, name = it.name, hint = it.hint) }

            val union = AccountScope.unionAgentsFor(
                legacy = legacy.map {
                    AccountScope.VisibleAgent.Legacy(it.id, it.name, it.hint)
                },
                accountHash = hash,
                context = context,
            )
            // `admittedAgents` has already applied the gate, so every record
            // reaching here is executable. Both variants are returned: a signed
            // -out device admits legacy records as DEVICE_LOCAL, and dropping
            // them here would brick agents for users who are not signed in.
            //
            // Tombstones are excluded: a deleted agent must be neither listed
            // nor resolvable, or deletion would be a lie until the next pass
            // propagates it. Sync never reads this function — it uses the
            // stores directly, where the tombstone survives — so excluding here
            // cannot resurrect anything.
            AccountScope.admittedAgents(union, hash).filterNot { record ->
                record is AccountScope.VisibleAgent.Owned && record.deletedAt != null
            }.map { record ->
                CustomAgent(record.id, record.name, record.hint)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * STAGE 6: is this an executable, known agent?
     *
     * An unassigned legacy record is displayable but not known, so a saved
     * default or selection pointing at one cannot cause it to run.
     */
    fun isKnownAgent(context: Context, agentId: String): Boolean {
        if (agentId == ID_BUILT_IN) return true
        return loadCustomAgents(context).any { it.id == agentId }
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
     * Resolves the effective agent at deliver time. Null, blank, unknown, or
     * deleted ids fall back to built-in so callers never branch on validity.
     */
    fun resolveActiveAgent(context: Context, requestedId: String?): ResolvedAgent {
        if (!requestedId.isNullOrBlank() && !isBuiltIn(requestedId)) {
            val custom = try {
                loadCustomAgents(context).firstOrNull { it.id == requestedId }
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
