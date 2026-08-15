package com.groq.voicetyper.snippets

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID
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
 *
 * v2 (spec §30.4): every entry gains per-entry sync metadata (uuid,
 * created_at, deleted_at, sync_state, server_file_id, sync_account,
 * quarantine_reason) via serde-style defaults — a v1 document loads unchanged
 * with all metadata null. Edits follow §30.2: tombstone the old UUID + create
 * a new UUID in ONE write (no live-content PATCH). [deleteSnippet] tombstones
 * uploaded entries (§30.2) and hard-removes never-uploaded ones (§14).
 */
object SnippetPreferences {
    private const val PREFS_NAME = "fluence_prefs"
    private const val KEY_SNIPPETS_ENABLED = "snippets_enabled"
    private const val KEY_SNIPPETS_JSON = "snippets_json"
    private const val JSON_VERSION = 2

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

    /** User-facing reads: only live (untombstoned) snippets (spec §30.4). */
    fun loadSnippets(context: Context): List<Snippet> {
        val json = getPrefs(context).getString(KEY_SNIPPETS_JSON, null) ?: return emptyList()
        return deserialize(json).filter { it.deletedAt == null }
    }

    /** Every entry — live, tombstoned, latched — for the sync seam. */
    fun allEntries(context: Context): List<Snippet> {
        val json = getPrefs(context).getString(KEY_SNIPPETS_JSON, null) ?: return emptyList()
        return deserialize(json)
    }

    /** Sync seam: atomically replace the whole collection (§30.2 bulk import). */
    fun saveAll(context: Context, snippets: List<Snippet>) {
        write(context, snippets)
    }

    /**
     * Inserts a new snippet or updates the existing one with [id].
     *
     * Returns [SaveResult.PRESERVED] (and takes no action) when the trigger is
     * invalid, out of bounds, already owned by a live snippet
     * (case-insensitive), or [id] does not exist.
     *
     * An update is an edit (spec §30.2): the old entry is tombstoned and a
     * fresh entry with a NEW sync UUID carries the updated content — one
     * write, no live-content PATCH.
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

        val all = allEntries(context).toMutableList()
        val duplicate = all.firstOrNull {
            it.id != id && it.deletedAt == null && it.trigger.equals(trimmedTrigger, ignoreCase = true)
        }
        if (duplicate != null) return SaveResult.PRESERVED

        if (id == 0L) {
            val nextId = (all.maxOfOrNull { it.id } ?: 0L) + 1L
            all.add(newSnippet(nextId, trimmedTrigger, trimmedExpansion))
            write(context, all)
            return SaveResult.INSERTED
        }

        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return SaveResult.PRESERVED
        val old = all[index]
        // §30.2 step 1: tombstone the old UUID (dirty only when uploaded).
        all[index] = old.copy(
            deletedAt = nowMs(),
            syncState = if (old.serverFileId != null) SYNC_STATE_DIRTY else null
        )
        // §30.2 step 2: a new UUID carries the updated content (one write).
        val nextId = (all.maxOfOrNull { it.id } ?: 0L) + 1L
        all.add(newSnippet(nextId, trimmedTrigger, trimmedExpansion))
        write(context, all)
        return SaveResult.UPDATED
    }

    /**
     * §30.2/§14: an uploaded snippet is tombstoned so other devices delete it
     * too; a never-uploaded one is provably safe to hard-remove.
     */
    fun deleteSnippet(context: Context, id: Long) {
        val all = allEntries(context).toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return
        val entry = all[index]
        if (entry.serverFileId != null) {
            all[index] = entry.copy(
                deletedAt = nowMs(),
                syncState = SYNC_STATE_DIRTY
            )
        } else {
            all.removeAt(index)
        }
        write(context, all)
    }

    private fun newSnippet(id: Long, trigger: String, expansion: String): Snippet =
        Snippet(
            id = id,
            trigger = trigger,
            expansion = expansion,
            uuid = UUID.randomUUID().toString(),
            createdAt = nowMs(),
            deletedAt = null,
            syncState = null,
            serverFileId = null,
            syncAccount = null,
            quarantineReason = null
        )

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun write(context: Context, snippets: List<Snippet>) {
        getPrefs(context).edit().putString(KEY_SNIPPETS_JSON, serialize(snippets)).apply()
    }

    internal fun serialize(snippets: List<Snippet>): String {
        val array = JSONArray()
        for (snippet in snippets) {
            val entry = JSONObject()
                .put("id", snippet.id)
                .put("t", snippet.trigger)
                .put("e", snippet.expansion)
            // §30 metadata — serde-style: only non-null fields are written,
            // legacy v1 documents (and pre-sync entries) load with nulls.
            snippet.uuid?.let { entry.put("uuid", it) }
            snippet.createdAt?.let { entry.put("created_at", it) }
            snippet.deletedAt?.let { entry.put("deleted_at", it) }
            snippet.syncState?.let { entry.put("sync_state", it) }
            snippet.serverFileId?.let { entry.put("server_file_id", it) }
            snippet.syncAccount?.let { entry.put("sync_account", it) }
            snippet.quarantineReason?.let { entry.put("quarantine_reason", it) }
            array.put(entry)
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
     * rest of the collection. v1 documents load unchanged — every metadata
     * field defaults to null.
     */
    internal fun deserialize(json: String): List<Snippet> {
        return try {
            val root = JSONObject(json)
            val version = root.optInt("v", 0)
            if (version != 1 && version != JSON_VERSION) return emptyList()
            val array = root.getJSONArray("snippets")
            val result = ArrayList<Snippet>(array.length())
            for (i in 0 until array.length()) {
                try {
                    val entry = array.getJSONObject(i)
                    result.add(
                        Snippet(
                            id = entry.optLong("id", 0L),
                            trigger = entry.optString("t", ""),
                            expansion = entry.optString("e", ""),
                            uuid = entry.optStringOrNull("uuid"),
                            createdAt = entry.optLongOrNull("created_at"),
                            deletedAt = entry.optLongOrNull("deleted_at"),
                            syncState = entry.optStringOrNull("sync_state"),
                            serverFileId = entry.optStringOrNull("server_file_id"),
                            syncAccount = entry.optStringOrNull("sync_account"),
                            quarantineReason = entry.optStringOrNull("quarantine_reason")
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

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private const val SYNC_STATE_DIRTY = "dirty"
}