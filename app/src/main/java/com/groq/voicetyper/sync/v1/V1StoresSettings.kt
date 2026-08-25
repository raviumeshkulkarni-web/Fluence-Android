package com.groq.voicetyper.sync.v1

import android.content.Context
import com.groq.voicetyper.SecurityUtils
import com.groq.voicetyper.autolearn.AutoLearnPreferences
import com.groq.voicetyper.dictionary.DictionaryPreferences
import com.groq.voicetyper.snippets.SnippetPreferences
import org.json.JSONObject

/**
 * Mid-pass dirty guard for settings (decideDictionaryApply parity).
 * [snapshotValue] is the value recorded before the pass fetched remote state;
 * [liveValue] is the value now. When they diverge the user edited the key
 * during the GET→PUT window — applying the merged winner would clobber that
 * edit, so it must be deferred and ride the next PUT instead.
 */
fun decideSettingsApply(snapshotValue: String?, liveValue: String?): Boolean =
    snapshotValue == null || liveValue == null || snapshotValue == liveValue

/**
 * Settings v1.2 store — frozen five keys mapped onto the app's real prefs:
 *   language           → stt_language            (secure prefs)
 *   dictionary_enabled → custom_dictionary_enabled
 *   snippets_enabled   → snippets_enabled
 *   auto_learn_enabled → auto_learn_enabled
 *   ai_polish_style    → ai_polish_style         (plain fluence_prefs)
 *
 * Per-key LWW bookkeeping lives in a per-account prefs document
 * ("fluence_sync_settings_<accountHash>"): {key: {"v": value, "t": updatedAt}}.
 * Partitioning by account hash means signing into another account can never
 * read or overwrite this account's preference timestamps. A key whose live
 * value differs from the recorded one is dirty with updatedAt = now (wall
 * clock). applyMergedAndClearDirty writes winners back and records their cloud
 * updatedAt — one atomic prefs write per apply.
 */
class PrefsSettingsV1Store(private val context: Context) : V1SyncEngine.SettingsV1Store {

    private data class Mapping(
        val syncKey: String,
        val read: () -> String,
        val write: (String) -> Unit
    )

    private fun mappings(): List<Mapping> = listOf(
        Mapping("language", { SecurityUtils.getSttLanguage(context).ifEmpty { "en" } }, { v ->
            SecurityUtils.saveSttLanguage(context, v)
        }),
        Mapping("dictionary_enabled", { DictionaryPreferences.isDictionaryEnabled(context).toString() }, { v ->
            DictionaryPreferences.setDictionaryEnabled(context, v == "true")
        }),
        Mapping("snippets_enabled", { SnippetPreferences.isSnippetsEnabled(context).toString() }, { v ->
            SnippetPreferences.setSnippetsEnabled(context, v == "true")
        }),
        Mapping("auto_learn_enabled", { AutoLearnPreferences.isAutoLearnEnabled(context).toString() }, { v ->
            AutoLearnPreferences.setAutoLearnEnabled(context, v == "true")
        }),
        Mapping("ai_polish_style", {
            context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
                .getString(KEY_AI_POLISH_STYLE, "default") ?: "default"
        }, { v ->
            context.getSharedPreferences("fluence_prefs", Context.MODE_PRIVATE)
                .edit().putString(KEY_AI_POLISH_STYLE, v).apply()
        })
    )

    private fun metaPrefs(accountHash: String) =
        context.getSharedPreferences("$META_PREFS$accountHash", Context.MODE_PRIVATE)

    private fun loadMeta(accountHash: String): JSONObject = try {
        JSONObject(metaPrefs(accountHash).getString(META_KEY, "{}") ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }

    private fun saveMeta(accountHash: String, meta: JSONObject) {
        metaPrefs(accountHash).edit().putString(META_KEY, meta.toString()).apply()
    }

    private fun getMeta(meta: JSONObject, key: String): Pair<String, Long>? {
        val o = meta.optJSONObject(key) ?: return null
        val v = o.optString("v")
        val t = o.optLong("t", 0L)
        if (v.isEmpty()) return null
        return v to t
    }

    private fun setMeta(accountHash: String, key: String, value: String, at: Long = System.currentTimeMillis()) {
        val meta = loadMeta(accountHash)
        meta.put(key, JSONObject().put("v", value).put("t", at))
        saveMeta(accountHash, meta)
    }

    override suspend fun loadByAccount(hash: String): List<V1SyncEngine.SettingsLocal> {
        val meta = loadMeta(hash)
        val out = mutableListOf<V1SyncEngine.SettingsLocal>()
        for (m in mappings()) {
            val current = runCatching { m.read() }.getOrNull() ?: continue
            val known = getMeta(meta, m.syncKey)
            if (known == null) {
                if (current.isNotEmpty()) {
                    out.add(local(m.syncKey, current, 0L, dirty = false))
                }
            } else if (known.first != current) {
                // Local edit since last sync → dirty with fresh wall clock.
                setMeta(hash, m.syncKey, current)
                out.add(local(m.syncKey, current, System.currentTimeMillis(), dirty = true))
            } else {
                out.add(local(m.syncKey, current, known.second, dirty = false))
            }
        }
        return out
    }

    override suspend fun stampUnstamped(hash: String) {
        // Settings carry no account-stamped rows; dirty detection is value-diff based.
    }

    override suspend fun hasDirty(hash: String): Boolean {
        val meta = loadMeta(hash)
        return mappings().any { m ->
            val current = runCatching { m.read() }.getOrNull()
            val known = getMeta(meta, m.syncKey)
            current != null && (known == null || known.first != current)
        }
    }

    override suspend fun applyMergedAndClearDirty(
        hash: String,
        deviceId: String,
        merged: List<SettingsRecord>
    ) {
        val meta = loadMeta(hash)
        val byKey = merged.associateBy { it.key }
        for (m in mappings()) {
            val rec = byKey[m.syncKey] ?: continue
            // Mid-pass guard (decideSettingsApply): a key edited after the
            // pre-GET snapshot is deferred, keeping its dirty state so the
            // local change rides the next PUT instead of being clobbered.
            val snapshot = getMeta(meta, m.syncKey)?.first
            val live = runCatching { m.read() }.getOrNull()
            if (!decideSettingsApply(snapshot, live)) continue
            runCatching { m.write(rec.value) }
            setMeta(hash, m.syncKey, rec.value, rec.updatedAt)
        }
    }

    private fun local(key: String, value: String, updatedAt: Long, dirty: Boolean) =
        V1SyncEngine.SettingsLocal(
            key = key,
            value = value,
            updatedAt = updatedAt,
            deviceId = DeviceIdProvider.getDeviceId(context),
            deletedAt = null,
            accountHash = null,
            dirty = dirty,
            everPushed = true
        )

    companion object {
        const val META_PREFS = "fluence_sync_settings"
        const val META_KEY = "lww_meta"
        const val KEY_AI_POLISH_STYLE = "ai_polish_style"
    }
}
