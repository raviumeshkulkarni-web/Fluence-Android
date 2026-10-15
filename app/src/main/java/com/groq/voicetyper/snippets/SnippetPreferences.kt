package com.groq.voicetyper.snippets

import android.content.Context
import android.content.SharedPreferences
import com.groq.voicetyper.sync.SyncAccounts
import com.groq.voicetyper.sync.auth.SyncAuthSession
import com.groq.voicetyper.sync.v1.AccountHash
import com.groq.voicetyper.sync.v1.MutationClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /** Backup-key prefix for quarantined documents; visible for tests. */
    internal const val CORRUPT_PREFIX = "snippets_json_corrupt_"
    /**
     * High-water mark for local row ids. Merge healing can drop the row
     * holding the max id (a healed duplicate); without a memory of it, the
     * next insert would recycle that id and a stale UI/delete reference could
     * alias the new row. Every write funnels through [write], which only ever
     * raises this mark; inserts allocate above both the live max and the mark.
     * Wire identity is the uuid, so skipping a dropped id costs nothing.
     */
    private const val KEY_MAX_ID = "snippets_max_id"

    const val MAX_TRIGGER_LENGTH = 4096
    const val MAX_EXPANSION_LENGTH = 8192

    enum class SaveResult { INSERTED, UPDATED, PRESERVED }

    /**
     * F3: every writer in this object is a read-modify-write on ONE JSON
     * document, and the sync apply pass races the UI writers on different
     * threads. Without serialization a mutation landing between another
     * writer's read and write is silently lost (parallel inserts proved it:
     * 64 racing inserts kept 2 rows). This leaf lock serializes every such
     * span, including the sync store's apply pass (via [withWriteLock]).
     * `synchronized` is reentrant so nested writers are safe; the lock is a
     * leaf — never held while acquiring `accountStoreLock` or a Room
     * transaction — so no lock ordering exists to invert.
     */
    private val writeLock = Any()

    /** Run [block] with the snippet write lock held (see [writeLock]). */
    internal inline fun <T> withWriteLock(block: () -> T): T = synchronized(writeLock, block)

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
        val hash = currentAccountHash(context)
        return readUsable(context, json).filter { it.deletedAt == null && belongsToCurrentAccount(it, hash) }
    }

    /** Every entry — live, tombstoned, latched — for the sync seam. */
    fun allEntries(context: Context): List<Snippet> {
        val json = getPrefs(context).getString(KEY_SNIPPETS_JSON, null) ?: return emptyList()
        return readUsable(context, json)
    }

    /**
     * Parse gate shared by every read. [deserialize] returning `null` means
     * the document is unparseable, so its bytes are preserved aside under a
     * backup key and the live key is removed — mirroring
     * `AccountScope.quarantine`. Every writer below reads through
     * [allEntries], so after quarantine they operate on a genuinely fresh
     * store instead of persisting an empty read over corrupt bytes (Phase E).
     *
     * A parseable document with an unsupported schema version is NOT corrupt:
     * a newer client owns it (cf. frozen.rs `is_future_envelope` — "it is NOT
     * corrupt and must never be repaired over"). The live key is left
     * completely untouched and the read yields empty; quarantining it would
     * delete a newer build's data and propagate a downgrade to Drive.
     */
    /**
     * The schema version `json` declares, or 0 when it declares none we can read.
     *
     * SATURATE, never truncate — the exact rule of
     * `DomainSerializer.declaredEnvelopeVersion`, which this twin missed.
     * `optInt("v", 0)` narrows through `intValue()`, so `{"v":4294967297}`
     * became `1`, which the gate below ACCEPTS. A document owned by a newer
     * build therefore sailed through the version check, failed to deserialize
     * against this build's schema, and was QUARANTINED — deleting the live key
     * and stashing a newer build's document under a backup key. That is exactly
     * the outcome this function's own contract says must never happen.
     *
     * 0 is returned for absent / non-integer / non-numeric `v`, preserving the
     * previous `optInt(…, 0)` accept/reject behaviour for every other input.
     */
    private fun declaredVersion(json: String): Long {
        val raw = JSONObject(json).opt("v")
        return when (raw) {
            null -> 0L
            // Non-integer Numbers are unreadable, as on Windows (`as_i64()` -> None).
            // `BigDecimal` is listed explicitly because org.json can surface a
            // decimal as either `Double` or `BigDecimal` depending on the
            // implementation; without this arm a `1.5` would fall through to
            // `toLong()` and truncate to `1`, which is the accepted value — the
            // very truncation this function exists to prevent.
            is Double, is Float, is java.math.BigDecimal -> 0L
            is Number -> raw.toLong()
            else -> 0L
        }
    }

    private fun readUsable(context: Context, json: String): List<Snippet> {
        val version = try {
            declaredVersion(json)
        } catch (_: Exception) {
            quarantineCorrupt(context, json)
            return emptyList()
        }
        // A version strictly newer than this build owns the document: leave it
        // completely untouched and read as empty. Never quarantine it.
        if (version != 1L && version != JSON_VERSION.toLong()) return emptyList()
        // Supported version but unreadable body (missing/not-an-array
        // "snippets", malformed entries section): unambiguously corruption,
        // since the version gate above already passed. Quarantine like the
        // unparseable case — returning empty here would let the next write
        // persist a fresh document straight over the live key.
        return deserialize(json) ?: run { quarantineCorrupt(context, json); emptyList() }
    }

    /**
     * Preserve an unusable document under a `snippets_json_corrupt.N` key and
     * remove the live key in the same edit, so the next read finds nothing
     * instead of re-quarantining forever. Same shape as the Windows
     * `backup_corrupt_account` rotate-aside: one corruption event costs one
     * entry, and bytes are never silently converted into an empty store.
     */
    private fun quarantineCorrupt(context: Context, raw: String) = withWriteLock {
        // No logcat here: android.util.Log throws in JVM unit tests, and this
        // path must stay testable. The backup key itself is the record.
        val prefs = getPrefs(context)
        var n = 1
        while (prefs.contains("$CORRUPT_PREFIX$n")) n++
        prefs.edit()
            .putString("$CORRUPT_PREFIX$n", raw)
            .remove(KEY_SNIPPETS_JSON)
            .apply()
    }

    /** Observable user-facing view; background sync imports become visible immediately. */
    fun observeSnippets(context: Context): Flow<List<Snippet>> {
        val appContext = context.applicationContext
        SyncAccounts.refresh(appContext)
        val preferenceFlow = callbackFlow {
            val prefs = getPrefs(appContext)
            fun emitSnapshot() { trySend(allEntries(appContext)) }
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_SNIPPETS_JSON) emitSnapshot()
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            emitSnapshot()
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return preferenceFlow.combine(SyncAccounts.currentAccountHash) { all, hash ->
            all.filter { it.deletedAt == null && belongsToCurrentAccount(it, hash) }
        }.distinctUntilChanged()
    }

    /** Sync seam: atomically replace the whole collection (§30.2 bulk import). */
    fun saveAll(context: Context, snippets: List<Snippet>) = withWriteLock {
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
    fun saveSnippet(context: Context, trigger: String, expansion: String, id: Long = 0L): SaveResult = withWriteLock {
        val trimmedTrigger = trigger.trim()
        val trimmedExpansion = expansion.trim()
        if (trimmedTrigger.isEmpty() || trimmedExpansion.isEmpty()) return SaveResult.PRESERVED
        if (trimmedTrigger.codePointCount(0, trimmedTrigger.length) > MAX_TRIGGER_LENGTH ||
            trimmedExpansion.codePointCount(0, trimmedExpansion.length) > MAX_EXPANSION_LENGTH
        ) {
            android.util.Log.w("SnippetPreferences", "Snippet trigger or expansion exceeds wire caps (trigger=${trimmedTrigger.codePointCount(0, trimmedTrigger.length)} > 4096 or expansion=${trimmedExpansion.codePointCount(0, trimmedExpansion.length)} > 8192)")
            return SaveResult.PRESERVED
        }

        val all = allEntries(context).toMutableList()
        val hash = currentAccountHash(context)
        val duplicate = all.firstOrNull {
            belongsToCurrentAccount(it, hash) &&
                it.id != id && it.deletedAt == null && it.trigger.equals(trimmedTrigger, ignoreCase = true)
        }
        if (duplicate != null) return SaveResult.PRESERVED

        if (id == 0L) {
            // Allocate above both the live max and the high-water mark: a
            // healed duplicate may have held a higher id than any live row.
            val liveMax = all.maxOfOrNull { it.id } ?: 0L
            val highWater = getPrefs(context).getLong(KEY_MAX_ID, 0L)
            val nextId = maxOf(liveMax, highWater) + 1L
            all.add(newSnippet(nextId, trimmedTrigger, trimmedExpansion, context, hash))
            write(context, all)
            return SaveResult.INSERTED
        }

        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return SaveResult.PRESERVED
        val old = all[index]
        if (!belongsToCurrentAccount(old, hash)) return SaveResult.PRESERVED
        // Frozen v1.2: an edit is a NEWER state than any tombstone (pure LWW),
        // so the edited row keeps its identity and bumps updatedAt. The legacy
        // tombstone-and-fresh-UUID dance is no longer needed.
        all[index] = old.copy(
            trigger = trimmedTrigger,
            expansion = trimmedExpansion,
            uuid = old.uuid ?: UUID.randomUUID().toString(),
            // #30: zero-I/O stamp. These writers are called straight from a
            // Compose onClick on Main.immediate and cannot suspend, so they
            // reuse the floor cached by MutationClock's suspending read
            // instead of bridging a metadata read with runBlocking.
            updatedAt = MutationClock.nextCached(),
            deviceId = com.groq.voicetyper.sync.v1.DeviceIdProvider.getDeviceId(context),
            dirty = true
        )
        write(context, all)
        return SaveResult.UPDATED
    }

    /**
     * Frozen v1.2 delete: a pushed snippet is tombstoned so other devices
     * delete it too; a never-pushed one is provably safe to hard-remove.
     */
    fun deleteSnippet(context: Context, id: Long) = withWriteLock {
        val all = allEntries(context).toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return
        val entry = all[index]
        if (!belongsToCurrentAccount(entry, currentAccountHash(context))) return
        if (entry.everPushed || entry.serverFileId != null) {
            all[index] = entry.copy(
                deletedAt = MutationClock.nextCached(),
                updatedAt = MutationClock.nextCached(),
                deviceId = entry.deviceId ?: com.groq.voicetyper.sync.v1.DeviceIdProvider.getDeviceId(context),
                dirty = true,
                everPushed = true
            )
        } else {
            all.removeAt(index)
        }
        write(context, all)
    }

    private fun newSnippet(id: Long, trigger: String, expansion: String, context: Context, accountHash: String?): Snippet =
        Snippet(
            id = id,
            trigger = trigger,
            expansion = expansion,
            uuid = UUID.randomUUID().toString(),
            createdAt = MutationClock.nextCached(),
            updatedAt = MutationClock.nextCached(),
            deletedAt = null,
            deviceId = com.groq.voicetyper.sync.v1.DeviceIdProvider.getDeviceId(context),
            isEnabled = true,
            dirty = true,
            everPushed = false,
            syncAccount = accountHash
        )

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun currentAccountHash(context: Context): String? =
        runCatching {
            AccountHash.of(SyncAuthSession(context.applicationContext).accountEmail)
        }.getOrNull()

    internal fun belongsToCurrentAccount(snippet: Snippet, hash: String?): Boolean =
        snippet.syncAccount == null || snippet.syncAccount == hash

    private fun write(context: Context, snippets: List<Snippet>) {
        // Single choke point for every writer (always under writeLock):
        // raise the id high-water mark past any id ever stored, then write.
        val prefs = getPrefs(context)
        val highWater = prefs.getLong(KEY_MAX_ID, 0L)
        val liveMax = snippets.maxOfOrNull { it.id } ?: 0L
        val editor = prefs.edit().putString(KEY_SNIPPETS_JSON, serialize(snippets))
        if (liveMax > highWater) editor.putLong(KEY_MAX_ID, liveMax)
        editor.apply()
    }

    internal fun serialize(snippets: List<Snippet>): String {
        val array = JSONArray()
        for (snippet in snippets) {
            val entry = JSONObject()
                .put("id", snippet.id)
                .put("t", snippet.trigger)
                .put("e", snippet.expansion)
            // Sync metadata — serde-style: only non-null/non-default fields are
            // written, legacy documents load with defaults.
            snippet.uuid?.let { entry.put("uuid", it) }
            snippet.createdAt?.let { entry.put("created_at", it) }
            snippet.deletedAt?.let { entry.put("deleted_at", it) }
            if (snippet.updatedAt != null) entry.put("updated_at", snippet.updatedAt)
            snippet.deviceId?.let { entry.put("device_id", it) }
            if (!snippet.isEnabled) entry.put("is_enabled", false)
            if (snippet.dirty) entry.put("dirty", true)
            if (snippet.everPushed) entry.put("ever_pushed", true)
            // Dormant legacy columns (document compatibility only).
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
     * Decode, distinguishing unusable documents from empty ones. Returns
     * `null` when the document cannot be safely used at all — unparseable
     * JSON or an unsupported schema version — so the caller preserves it
     * aside instead of treating it as empty (Phase E). A single malformed
     * entry is still skipped without discarding the rest of the collection;
     * v1 documents load unchanged — every metadata field defaults to null.
     */
    internal fun deserialize(json: String): List<Snippet>? {
        return try {
            val root = JSONObject(json)
            val version = declaredVersion(json)
            if (version != 1L && version != JSON_VERSION.toLong()) return null
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
                            updatedAt = entry.optLongOrNull("updated_at"),
                            deviceId = entry.optStringOrNull("device_id"),
                            isEnabled = entry.optBoolean("is_enabled", true),
                            dirty = entry.optBoolean("dirty", false),
                            everPushed = entry.optBoolean("ever_pushed", false),
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
            null
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    private const val SYNC_STATE_DIRTY = "dirty"
}
