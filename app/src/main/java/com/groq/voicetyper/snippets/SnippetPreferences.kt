package com.groq.voicetyper.snippets

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Persistence for the small user-defined snippet collection.
 *
 * SharedPreferences ("fluence_prefs", the store every other settings flag
 * uses) with a single versioned JSON document. No Room, schema, or migration
 * involvement. The collection is expected to stay small (single-digit to
 * low-dozens of entries), so one JSON parse per dictation on the background
 * transcription thread is negligible and cache-free by design.
 *
 * Default OFF: [isSnippetsEnabled] is false until the user enables the
 * feature, so with no user action the transcript path is byte-identical to
 * the production build.
 */
object SnippetPreferences {
    private const val PREFS_NAME = "fluence_prefs"
    private const val KEY_SNIPPETS_ENABLED = "snippets_enabled"
    private const val KEY_SNIPPETS_JSON = "snippets_json"
    private const val JSON_VERSION = 1

    const val MAX_TRIGGER_LENGTH = 100
    const val MAX_EXPANSION_LENGTH = 500

    enum class SaveResult { INSERTED, UPDATED, PRESERVED }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isSnippetsEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SNIPPETS_ENABLED, false)
    }

    fun setSnippetsEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SNIPPETS_ENABLED, enabled).apply()
    }

    fun loadSnippets(context: Context): List<Snippet> {
        val json = getPrefs(context).getString(KEY_SNIPPETS_JSON, null) ?: return emptyList()
        return deserialize(json)
    }

    /**
     * Inserts a new snippet or updates the existing one with [id].
     *
     * Returns [SaveResult.PRESERVED] (and takes no action) when the trigger is
     * invalid, out of bounds, already owned by another snippet
     * (case-insensitive), or [id] does not exist.
     */
    fun saveSnippet(context: Context, trigger: String, expansion: String, id: Long = 0L): SaveResult {
        val trimmedTrigger = trigger.trim()
        val trimmedExpansion = expansion.trim()
        if (trimmedTrigger.isEmpty() || trimmedExpansion.isEmpty()) return SaveResult.PRESERVED
        if (trimmedTrigger.length > MAX_TRIGGER_LENGTH ||
            trimmedExpansion.length > MAX_EXPANSION_LENGTH
        ) {
            return SaveResult.PRESERVED
        }

        val current = loadSnippets(context).toMutableList()
        val duplicate = current.firstOrNull {
            it.id != id && it.trigger.equals(trimmedTrigger, ignoreCase = true)
        }
        if (duplicate != null) return SaveResult.PRESERVED

        if (id == 0L) {
            val nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1L
            current.add(Snippet(id = nextId, trigger = trimmedTrigger, expansion = trimmedExpansion))
            write(context, current)
            return SaveResult.INSERTED
        }

        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return SaveResult.PRESERVED
        current[index] = current[index].copy(trigger = trimmedTrigger, expansion = trimmedExpansion)
        write(context, current)
        return SaveResult.UPDATED
    }

    fun deleteSnippet(context: Context, id: Long) {
        val current = loadSnippets(context).filterNot { it.id == id }
        write(context, current)
    }

    private fun write(context: Context, snippets: List<Snippet>) {
        getPrefs(context).edit().putString(KEY_SNIPPETS_JSON, serialize(snippets)).apply()
    }

    internal fun serialize(snippets: List<Snippet>): String {
        val array = JSONArray()
        for (snippet in snippets) {
            array.put(
                JSONObject()
                    .put("id", snippet.id)
                    .put("t", snippet.trigger)
                    .put("e", snippet.expansion)
            )
        }
        return JSONObject()
            .put("v", JSON_VERSION)
            .put("snippets", array)
            .toString()
    }

    /**
     * Fail-soft decode: an unreadable document (corruption, future schema,
     * wrong version) yields an empty list so the transcript passes through
     * unchanged; a single malformed entry is skipped without discarding the
     * rest of the collection.
     */
    internal fun deserialize(json: String): List<Snippet> {
        return try {
            val root = JSONObject(json)
            if (root.optInt("v", 0) != JSON_VERSION) return emptyList()
            val array = root.getJSONArray("snippets")
            val result = ArrayList<Snippet>(array.length())
            for (i in 0 until array.length()) {
                try {
                    val entry = array.getJSONObject(i)
                    result.add(
                        Snippet(
                            id = entry.optLong("id", 0L),
                            trigger = entry.optString("t", ""),
                            expansion = entry.optString("e", "")
                        )
                    )
                } catch (_: JSONException) {
                    // Skip a malformed entry; keep the rest of the collection.
                }
            }
            result
        } catch (e: JSONException) {
            emptyList()
        }
    }
}