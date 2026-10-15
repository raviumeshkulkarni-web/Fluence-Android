package com.groq.voicetyper.sync.v1

import android.content.Context
import com.groq.voicetyper.SecurityUtils
import com.groq.voicetyper.autolearn.AutoLearnPreferences
import com.groq.voicetyper.dictionary.DictionaryPreferences
import com.groq.voicetyper.snippets.SnippetPreferences
import com.groq.voicetyper.sync.auth.SyncAuthSession
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
 * value differs from the recorded one is dirty with updatedAt = a fresh clock
 * floored by the persisted maxSeen (MutationClock — a backwards wall-clock
 * jump must not let an edit lose on LWW). applyMergedAndClearDirty writes
 * winners back and records their cloud updatedAt — one atomic prefs write per
 * apply.
 */
class PrefsSettingsV1Store(private val context: Context) : V1SyncEngine.SettingsV1Store {

    /** Do not let a pass started for account A mutate global prefs after the
     * active session has moved to account B (or signed out). */
    private fun isActiveAccount(hash: String): Boolean =
        runCatching {
            AccountHash.of(SyncAuthSession(context.applicationContext).accountEmail) == hash
        }.getOrDefault(false)

    /** Account-switch baseline stored only in that account's metadata. It
     * prevents the machine-global values from the previous account being
     * emitted as a new account edit without overwriting those values. */
    fun activateAccount(hash: String) {
        val baseline = JSONObject()
        for (m in mappings()) {
            val current = runCatching { m.read() }.getOrNull() ?: continue
            baseline.put(m.syncKey, current)
        }
        val meta = loadMeta(hash)
        meta.put(ACTIVATION_BASELINE_KEY, baseline)
        saveMeta(hash, meta)
    }

    private fun activationBaseline(meta: JSONObject): JSONObject? =
        meta.optJSONObject(ACTIVATION_BASELINE_KEY)

    private fun activationBaselineUnchanged(hash: String, meta: JSONObject = loadMeta(hash)): Boolean {
        val baseline = activationBaseline(meta) ?: return false
        // Fail CLOSED on an unreadable value. `getOrNull() ?: return@all true`
        // treated a failed read as "matches the baseline", so an unreadable
        // setting made the whole account look freshly-activated: `hasDirty`
        // returned false, `loadByAccount` returned nothing, and the domain was
        // silently skipped forever. Windows logs at error and reports dirty
        // (`stores.rs:771-774`); this is the missing twin.
        return mappings().all { m ->
            val current = runCatching { m.read() }.getOrElse {
                android.util.Log.e("FluenceSync", "settings key '${m.syncKey}' unreadable; failing closed (treating baseline as changed)")
                return@all false
            }
            baseline.optString(m.syncKey, "") == current
        }
    }

    private fun clearActivationBaseline(hash: String) {
        val meta = loadMeta(hash)
        meta.remove(ACTIVATION_BASELINE_KEY)
        saveMeta(hash, meta)
    }

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

    private fun loadMeta(accountHash: String): JSONObject {
        val raw = metaPrefs(accountHash).getString(META_KEY, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            // Derived bookkeeping only, but its bytes are still evidence:
            // preserve aside under a backup key and reset to fresh baselines
            // instead of overwriting the corrupt document (Phase E).
            quarantineMeta(accountHash, raw)
            JSONObject()
        }
    }

    private fun quarantineMeta(accountHash: String, raw: String) {
        val prefs = metaPrefs(accountHash)
        var n = 1
        while (prefs.contains("$META_CORRUPT_PREFIX$n")) n++
        prefs.edit()
            .putString("$META_CORRUPT_PREFIX$n", raw)
            .remove(META_KEY)
            .apply()
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
        val baseline = activationBaseline(meta)
        if (baseline != null) {
            if (activationBaselineUnchanged(hash, meta)) return emptyList()
            meta.remove(ACTIVATION_BASELINE_KEY)
            saveMeta(hash, meta)
        }
        // Global preferences may still contain the previous account's values
        // immediately after sign-in. Establish a per-account baseline without
        // uploading those values; a later user edit will differ from this
        // baseline and become dirty normally.
        if (meta.length() == 0) {
            for (m in mappings()) {
                val current = runCatching { m.read() }.getOrNull() ?: continue
                setMeta(hash, m.syncKey, current, 0L)
            }
            return emptyList()
        }
        val out = mutableListOf<V1SyncEngine.SettingsLocal>()
        for (m in mappings()) {
            val current = runCatching { m.read() }.getOrNull() ?: continue
            val known = getMeta(meta, m.syncKey)
            if (known == null) {
                if (current.isNotEmpty()) {
                    out.add(local(m.syncKey, current, 0L, dirty = false))
                }
            } else if (known.second == 0L && known.first == current) {
                // Unchanged first-session baseline: it is not a user edit and
                // must not be uploaded to an account whose remote file is
                // still absent.
            } else if (known.first != current) {
                // Local edit since last sync → dirty with a fresh clock that
                // still respects the persisted maxSeen floor (MutationClock):
                // a backwards wall-clock jump must not let this edit lose on
                // LWW against rows this device already pushed.
                //
                // Intentional: setMeta here persists the current value as the
                // pre-pass snapshot so that applyMergedAndClearDirty can detect
                // any FURTHER change the user makes during the GET→PUT window
                // (snapshot ≠ live ⇒ defer). This is the equivalent of the
                // bySyncId pre-capture in RoomDictionaryV1Store.
                val at = MutationClock.next(context)
                setMeta(hash, m.syncKey, current, at)
                out.add(local(m.syncKey, current, at, dirty = true))
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
        if (activationBaseline(meta) != null && activationBaselineUnchanged(hash, meta)) return false
        return mappings().any { m ->
            val current = runCatching { m.read() }.getOrNull()
            // Fail CLOSED: an unreadable value means "not observed", never
            // "unchanged". `current != null && …` silently dropped the key, so a
            // settings read failure reported the account CLEAN and the pass
            // concluded there was nothing to do. Windows returns true here
            // (`stores.rs:1100-1106`).
            if (current == null) {
                android.util.Log.e("FluenceSync", "settings key '${m.syncKey}' unreadable; reporting dirty (failing closed)")
                return@any true
            }
            val known = getMeta(meta, m.syncKey)
            known == null || known.first != current
        }
    }

    override suspend fun applyMergedAndClearDirty(
        hash: String,
        deviceId: String,
        merged: List<SettingsRecord>
    ) {
        if (!isActiveAccount(hash)) return
        val activationPending = activationBaseline(loadMeta(hash)) != null
        // "at least one winner did not land" — either a mid-pass concurrent edit
        // (deferred) or a failed write. Gates clearing the activation baseline.
        var deferredForConcurrentEdit = false
        val meta = loadMeta(hash)
        val byKey = merged.associateBy { it.key }
        for (m in mappings()) {
            if (!isActiveAccount(hash)) return
            val rec = byKey[m.syncKey] ?: continue
            // Mid-pass guard (decideSettingsApply): a key edited after the
            // pre-GET snapshot is deferred, keeping its dirty state so the
            // local change rides the next PUT instead of being clobbered.
            val snapshot = getMeta(meta, m.syncKey)?.first
            val live = runCatching { m.read() }.getOrNull()
            if (!decideSettingsApply(snapshot, live)) {
                deferredForConcurrentEdit = true
                continue
            }
            if (!isActiveAccount(hash)) return
            // Audit #19 parity (Windows stores.rs:908-911, 949-965). A failed
            // value write must NOT advance the baseline. The previous code did
            // `runCatching { m.write(...) }` and then `setMeta` unconditionally,
            // so on failure the LIVE store kept the old local value while meta
            // recorded the REMOTE winner. The next pass then read
            // `known.first != current`, misclassified the untouched local value
            // as a user edit, stamped it with `MutationClock.next()` (above every
            // honest timestamp), and uploaded it — where it WON LWW on every peer
            // in the account and the setting reverted fleet-wide, permanently
            // and silently.
            //
            // Leaving the baseline un-advanced is self-healing: the next pass
            // re-merges the same remote winner (which still beats the old
            // baseline) and retries the write, without ever re-uploading the
            // stale local value at a higher timestamp.
            val wrote = runCatching { m.write(rec.value) }.isSuccess
            if (!wrote) {
                android.util.Log.e(
                    "FluenceSync",
                    "settings key '${m.syncKey}' could not be persisted; winner NOT recorded — " +
                        "the next pass will re-apply it. Refusing to advance the meta baseline, " +
                        "which would re-upload the stale local value above every peer timestamp."
                )
                // Windows parity: `apply_merged` returns Err before
                // `meta.activation_baseline = None` (stores.rs:949-968), so a
                // failed write also leaves the activation baseline armed.
                deferredForConcurrentEdit = true
                continue
            }
            setMeta(hash, m.syncKey, rec.value, rec.updatedAt)
        }
        if (activationPending && !deferredForConcurrentEdit) clearActivationBaseline(hash)
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
        const val META_CORRUPT_PREFIX = "lww_meta_corrupt_"
        const val KEY_AI_POLISH_STYLE = "ai_polish_style"
        private const val ACTIVATION_BASELINE_KEY = "__activation_baseline"
    }
}
