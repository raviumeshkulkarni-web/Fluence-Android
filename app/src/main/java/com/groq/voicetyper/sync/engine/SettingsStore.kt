package com.groq.voicetyper.sync.engine

import com.groq.voicetyper.sync.wire.RecordType
import com.groq.voicetyper.sync.wire.WireRecord
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

const val KEY_SNIPPETS_ENABLED: String = "snippets_enabled"

/**
 * Settings record store (spec §30.3, §30.5).
 *
 * Keyed settings (currently `snippets_enabled`) sync as `settings` records.
 * This store is the LocalStore seam for the settings kind: an in-memory
 * registry of rows, persisted as JSON at [path] when one is provided. The
 * §30.3 semantics live here: a [toggle] tombstones the live row and creates a
 * new UUID row; a key-collision (same key, different value, both live) latches
 * the incoming row with `collision` so the local value never silently loses.
 */
class SettingsStore(private val path: String? = null) : LocalStore {

    /** One settings row — the §6-table shadow for the settings kind. */
    data class SettingsRow(
        val uuid: String,
        val createdAt: Long,
        val key: String,
        val value: String,
        val deletedAt: Long?,
        val serverFileId: String?,
        val syncAccount: String?,
        val syncState: String,
        val quarantineReason: String?,
    )

    private val rows: MutableList<SettingsRow> = mutableListOf<SettingsRow>().apply {
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                runCatching { file.readText() }
                    .getOrNull()
                    ?.let { deserializeRows(it) }
                    ?.let { addAll(it) }
            }
        }
    }

    fun rows(): List<SettingsRow> = rows

    /**
     * §30.3 toggle: tombstone the live row for [key], then create a fresh
     * UUID row with [value] (unstamped, §13). Returns the new row's UUID.
     */
    fun toggle(key: String, value: String): String {
        val now = System.currentTimeMillis()
        val liveUuid = liveRow(key)?.uuid
        if (liveUuid != null) {
            rows.firstOrNull { it.uuid == liveUuid }?.let { row ->
                rows[rows.indexOf(row)] = row.copy(
                    deletedAt = now,
                    syncState = SYNC_STATE_DIRTY
                )
            }
        }
        val uuid = UUID.randomUUID().toString()
        // The fresh row is unstamped (§13): `syncAccount` is an import
        // marker, so local rows keep it NULL and match any account.
        rows.add(
            SettingsRow(
                uuid = uuid,
                createdAt = now,
                key = key,
                value = value,
                deletedAt = null,
                serverFileId = null,
                syncAccount = null,
                syncState = SYNC_STATE_LOCAL,
                quarantineReason = null,
            )
        )
        save()
        return uuid
    }

    /** The newest live (untombstoned, unlatched) row for [key]. */
    fun liveRow(key: String): SettingsRow? =
        rows
            .filter { it.key == key && it.deletedAt == null && it.quarantineReason == null }
            .maxByOrNull { it.createdAt }

    fun liveValue(key: String): String? = liveRow(key)?.value

    fun liveEnabled(): Boolean? = liveValue(KEY_SNIPPETS_ENABLED)?.let { it == "true" }

    /**
     * Apply the synced `snippets_enabled` value to the local feature toggle
     * through the caller-provided sink (Phase 7 wires this to
     * `SnippetPreferences.setSnippetsEnabled`). No-op when the key has no
     * live row.
     */
    fun mirrorEnabled(sink: (Boolean) -> Unit) {
        liveEnabled()?.let(sink)
    }

    private fun save() {
        if (path != null) {
            val file = File(path)
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(serializeRows(rows))
            }
        }
    }

    private fun serializeRows(rows: List<SettingsRow>): String {
        val array = JSONArray()
        for (r in rows) {
            array.put(
                JSONObject()
                    .put("uuid", r.uuid)
                    .put("created_at", r.createdAt)
                    .put("key", r.key)
                    .put("value", r.value)
                    .put("deleted_at", r.deletedAt ?: JSONObject.NULL)
                    .put("server_file_id", r.serverFileId ?: JSONObject.NULL)
                    .put("sync_account", r.syncAccount ?: JSONObject.NULL)
                    .put("sync_state", r.syncState)
                    .put("quarantine_reason", r.quarantineReason ?: JSONObject.NULL)
            )
        }
        return array.toString()
    }

    private fun deserializeRows(json: String): List<SettingsRow>? = runCatching {
        val array = JSONArray(json)
        val out = ArrayList<SettingsRow>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            out.add(
                SettingsRow(
                    uuid = o.optString("uuid"),
                    createdAt = o.optLong("created_at"),
                    key = o.optString("key"),
                    value = o.optString("value"),
                    deletedAt = o.optLongOrNull("deleted_at"),
                    serverFileId = o.optStringOrNull("server_file_id"),
                    syncAccount = o.optStringOrNull("sync_account"),
                    syncState = o.optString("sync_state", SYNC_STATE_LOCAL),
                    quarantineReason = o.optStringOrNull("quarantine_reason"),
                )
            )
        }
        out
    }.getOrNull()

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    /** Latch the incoming live row when another live row holds the same key
     * with a different value (§30.5 `settings_toggle_quarantines_on_...`). */
    private fun latchKeyCollision(row: SettingsRow): SettingsRow {
        if (row.deletedAt != null || row.quarantineReason != null) return row
        val collides = rows.any {
            it.uuid != row.uuid &&
                it.key == row.key &&
                it.deletedAt == null &&
                it.quarantineReason == null &&
                it.value != row.value
        }
        return if (collides) {
            row.copy(
                quarantineReason = QuarantineReason.Collision.asStr,
                syncState = SYNC_STATE_QUARANTINED
            )
        } else {
            row
        }
    }

    override fun listRows(account: String?): List<LocalRow> = rows
        .filter { r ->
            when {
                account == null -> r.syncAccount == null
                else -> r.syncAccount?.let { it == account } ?: true
            }
        }
        .map(::toLocal)
        .sortedBy { it.uuid }

    override fun findRow(uuid: String): LocalRow? =
        rows.firstOrNull { it.uuid == uuid }?.let(::toLocal)

    override fun import(row: LocalRow) {
        val incoming = fromLocal(row) ?: return // other kinds never reach this store
        val latched = latchKeyCollision(incoming)
        val index = rows.indexOfFirst { it.uuid == latched.uuid }
        if (index >= 0) {
            rows[index] = latched
        } else {
            rows.add(latched)
        }
        save()
    }

    override fun markTombstoned(uuid: String, deletedAt: Long) {
        rows.firstOrNull { it.uuid == uuid }?.let { row ->
            rows[rows.indexOf(row)] = row.copy(
                deletedAt = deletedAt,
                syncState = SYNC_STATE_DIRTY
            )
        }
        save()
    }

    override fun setServerFileId(uuid: String, fileId: String) {
        rows.firstOrNull { it.uuid == uuid }?.let { row ->
            rows[rows.indexOf(row)] = row.copy(serverFileId = fileId)
        }
        save()
    }

    override fun setSyncState(uuid: String, state: String) {
        rows.firstOrNull { it.uuid == uuid }?.let { row ->
            rows[rows.indexOf(row)] = row.copy(syncState = state)
        }
        save()
    }

    override fun quarantine(uuid: String, reason: QuarantineReason) {
        rows.firstOrNull { it.uuid == uuid }?.let { row ->
            rows[rows.indexOf(row)] = row.copy(
                quarantineReason = reason.asStr,
                syncState = SYNC_STATE_QUARANTINED
            )
        }
        save()
    }

    override fun clearQuarantine(uuid: String) {
        rows.firstOrNull { it.uuid == uuid }?.let { row ->
            rows[rows.indexOf(row)] = row.copy(
                quarantineReason = null,
                syncState = SYNC_STATE_LOCAL
            )
        }
        save()
    }

    override fun hardDelete(uuid: String) {
        rows.removeAll { it.uuid == uuid }
        save()
    }

    companion object {
        internal fun toLocal(row: SettingsRow): LocalRow = LocalRow(
            uuid = row.uuid,
            timestampMs = row.createdAt,
            text = "",
            mode = "",
            durationMs = 0,
            provider = "",
            model = null,
            language = null,
            deletedAt = row.deletedAt,
            serverFileId = row.serverFileId,
            syncAccount = row.syncAccount,
            syncState = row.syncState,
            quarantineReason = row.quarantineReason,
            rtype = RecordType.Settings,
            settingsKey = row.key,
            settingsValue = row.value,
        )

        private fun fromLocal(row: LocalRow): SettingsRow? {
            if (row.rtype != RecordType.Settings) return null
            return SettingsRow(
                uuid = row.uuid,
                createdAt = row.timestampMs,
                key = row.settingsKey ?: "",
                value = row.settingsValue ?: "",
                deletedAt = row.deletedAt,
                serverFileId = row.serverFileId,
                syncAccount = row.syncAccount,
                syncState = row.syncState,
                quarantineReason = row.quarantineReason,
            )
        }

        /** For tests: a settings row with a deterministic-ish timestamp. */
        internal fun settingsRow(uuid: String, key: String, value: String): LocalRow =
            LocalRow(
                uuid = uuid,
                timestampMs = 1713471000123L + (uuid.firstOrNull()?.code ?: 0),
                text = "",
                mode = "",
                durationMs = 0,
                provider = "",
                model = null,
                language = null,
                deletedAt = null,
                serverFileId = null,
                syncAccount = null,
                syncState = SYNC_STATE_LOCAL,
                quarantineReason = null,
                rtype = RecordType.Settings,
                settingsKey = key,
                settingsValue = value,
            )

        /** For tests: a settings wire record. */
        internal fun settingsWire(id: String, key: String, value: String): WireRecord =
            WireRecord(
                v = 1,
                id = id,
                createdAt = 1713471000123L,
                deletedAt = null,
                rtype = RecordType.Settings,
                settingsKey = key,
                settingsValue = value,
            )
    }
}