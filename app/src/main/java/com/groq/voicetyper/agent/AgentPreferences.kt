package com.groq.voicetyper.agent

import android.content.Context
import android.content.SharedPreferences
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

    fun loadCustomAgents(context: Context): List<CustomAgent> {
        return try {
            parseAgentsJson(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_CUSTOM_AGENTS, "") ?: ""
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

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
        } catch (_: Exception) {
            null
        }
    }

    fun deleteCustomAgent(context: Context, id: String) {
        if (isBuiltIn(id)) return
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val remaining = loadCustomAgents(context).filter { it.id != id }
            val editor = prefs.edit().putString(KEY_CUSTOM_AGENTS, serializeAgentsJson(remaining))
            // A deleted default falls back to built-in.
            if (prefs.getString(KEY_DEFAULT_AGENT, ID_BUILT_IN) == id) {
                editor.putString(KEY_DEFAULT_AGENT, ID_BUILT_IN)
            }
            editor.apply()
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
