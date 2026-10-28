package com.groq.voicetyper.dictionary

import android.content.Context
import com.groq.voicetyper.dictionary.data.CustomDictionaryDao
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.history.FluenceDatabase
import com.groq.voicetyper.sync.SyncAccounts
import com.groq.voicetyper.sync.auth.SyncAuthSession
import com.groq.voicetyper.sync.v1.AccountHash
import com.groq.voicetyper.sync.v1.MutationClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Matcher

data class CompiledDictionaryRule(
    val regex: Regex,
    val replacementText: String
)

object DictionaryRepository {
    internal enum class SaveAction { INSERT, UPDATE, PRESERVE }

    enum class SaveResult { INSERTED, UPDATED, PRESERVED }

    private var dao: CustomDictionaryDao? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val cachedRules = AtomicReference<List<CompiledDictionaryRule>>(emptyList())
    @Volatile private var isObserving = false

    internal fun resolveSaveAction(existing: CustomDictionaryEntry?, id: Long): SaveAction = when {
        existing != null && existing.id != id -> {
            if (existing.deletedAt != null && id == 0L) SaveAction.UPDATE else SaveAction.PRESERVE
        }
        id != 0L -> SaveAction.UPDATE
        else -> SaveAction.INSERT
    }

    fun init(context: Context) {
        if (dao == null) {
            val db = FluenceDatabase.getInstance(context.applicationContext)
            dao = db.customDictionaryDao()
            SyncAccounts.refresh(context.applicationContext)
            startObservingCache()
        }
    }

    private fun startObservingCache() {
        if (isObserving) return
        isObserving = true
        // Prime the cache on the background scope so a first call from the main
        // thread (e.g. DictionaryScreen composition) never blocks on a Room query.
        scope.launch {
            runCatching {
                dao?.getAllEnabledSync()
                    ?.filter { belongsToCurrentAccount(it, SyncAccounts.currentAccountHash.value) }
            }
                .getOrNull()
                ?.let { updateCompiledCache(it) }
        }
        scope.launch {
            try {
                val enabled = dao?.getAllEnabled() ?: kotlinx.coroutines.flow.flowOf(emptyList())
                SyncAccounts.currentAccountHash
                    .combine(enabled) { hash, entries -> entries.filter { belongsToCurrentAccount(it, hash) } }
                    .collectLatest { entries -> updateCompiledCache(entries) }
            } catch (e: Exception) {
                // Fail-safe: empty cache on error
                cachedRules.set(emptyList())
            }
        }
    }

    // Windows parity (dictionary.rs:84-85): bounded rule text keeps the
    // compiled-regex evaluation cheap and stops an arbitrary pasted blob
    // from degrading transcription post-processing. Consumed by the manual
    // entry dialog and by saveEntryResolved.
    const val MAX_SPOKEN_CHARS = 200
    const val MAX_CORRECTED_CHARS = 1_000

    fun updateCompiledCache(entries: List<CustomDictionaryEntry>) {
        val compiled = entries
            .filter { it.isEnabled && it.spokenText.isNotBlank() }
            .sortedByDescending { it.spokenText.trim().length }
            .map { rule ->
                val escaped = Regex.escape(rule.spokenText.trim())
                CompiledDictionaryRule(
                    regex = Regex("(?i)\\b$escaped\\b"),
                    replacementText = Matcher.quoteReplacement(rule.replacementText)
                )
            }
        cachedRules.set(compiled)
    }

    fun getCompiledRules(context: Context): List<CompiledDictionaryRule> {
        init(context)
        // If the background prime hasn't populated the cache yet, load synchronously
        // so the first transcription still applies dictionary rules. Callers that
        // reach here (transcription post-processing) run off the main thread.
        if (cachedRules.get().isEmpty()) {
            runCatching {
                getDao(context).getAllEnabledSync()
                    .filter { belongsToCurrentAccount(it, SyncAccounts.currentAccountHash.value) }
            }
                .getOrNull()
                ?.let { updateCompiledCache(it) }
        }
        return cachedRules.get()
    }

    internal fun getDao(context: Context): CustomDictionaryDao {
        init(context)
        return dao!!
    }

    fun getAll(context: Context): Flow<List<CustomDictionaryEntry>> {
        val dictionaryDao = getDao(context)
        // Storage seam enforces the same predicate; the in-memory filter stays
        // as a second layer. Both are `belongsToCurrentAccount` by construction.
        return SyncAccounts.currentAccountHash.flatMapLatest { hash ->
            dictionaryDao.getAllForAccount(hash).map { entries ->
                entries.filter { belongsToCurrentAccount(it, hash) }
            }
        }
    }

    fun getAllEnabled(context: Context): Flow<List<CustomDictionaryEntry>> {
        val dictionaryDao = getDao(context)
        return SyncAccounts.currentAccountHash.flatMapLatest { hash ->
            dictionaryDao.getAllEnabledForAccount(hash).map { entries ->
                entries.filter { belongsToCurrentAccount(it, hash) }
            }
        }
    }

    fun getAllEnabledSync(context: Context): List<CustomDictionaryEntry> {
        val hash = SyncAccounts.currentAccountHash.value
        return getDao(context).getAllEnabledSyncForAccount(hash)
            .filter { belongsToCurrentAccount(it, hash) }
    }

    suspend fun saveEntry(context: Context, spokenText: String, replacementText: String, isEnabled: Boolean = true, id: Long = 0): SaveResult =
        saveEntryResolved(getDao(context), context, spokenText, replacementText, isEnabled, id)

    /**
     * Canonical spoken-text lookup against a candidate list, resolved in Kotlin.
     *
     * The identity is `DictionaryRecord.businessKeyOf` (Unicode trim + NFC +
     * lowercase). This used to be `LOWER(spokenText) = LOWER(:spokenText)` in
     * SQL, and stock Android SQLite folds nothing outside A-Z: typing "über"
     * while "Über" existed missed the row, INSERTed a second one (the raw
     * values differ, so the unique `(spokenText, syncAccount)` index never
     * fired), and left TWO live rows for one word — both returned by
     * `getAllEnabledSyncForAccount`, so both corrections applied. ASCII `trim()`
     * diverges the same way, so case alone was not the whole defect.
     *
     * Mirrors [com.groq.voicetyper.sync.v1.findBusinessKeyCollision] on the
     * sync side. [candidates] must be the account's rows plus unowned ones and
     * must include tombstones, so a tombstoned row is still revived in place.
     */
    internal fun findBySpokenKey(
        candidates: List<CustomDictionaryEntry>,
        spokenText: String
    ): CustomDictionaryEntry? {
        val key = com.groq.voicetyper.sync.v1.DictionaryRecord.businessKeyOf(spokenText)
        return candidates.firstOrNull {
            com.groq.voicetyper.sync.v1.DictionaryRecord.businessKeyOf(it.spokenText) == key
        }
    }

    /**
     * Scheduler-free core of [saveEntry] behind an injectable DAO, mirroring
     * the `deleteEntryResolved` seam: identical lookup, resolve, insert,
     * update (including the H3 rename branch below), and persistence, but no
     * DAO acquisition. Unit tests drive this with a mocked DAO.
     */
    internal suspend fun saveEntryResolved(dao: CustomDictionaryDao, context: Context, spokenText: String, replacementText: String, isEnabled: Boolean = true, id: Long = 0): SaveResult {
        val trimmedSpoken = spokenText.trim()
        val trimmedReplacement = replacementText.trim()
        if (trimmedSpoken.isEmpty() || trimmedReplacement.isEmpty()) return SaveResult.PRESERVED
        // Windows add/update validation parity (dictionary.rs:309-321):
        // over-cap rule text is refused, never trimmed silently.
        if (trimmedSpoken.length > MAX_SPOKEN_CHARS || trimmedReplacement.length > MAX_CORRECTED_CHARS) {
            return SaveResult.PRESERVED
        }

        val currentHash = runCatching {
            AccountHash.of(SyncAuthSession(context.applicationContext).accountEmail)
        }.getOrNull()
        val existing = currentHash?.let { findBySpokenKey(dao.getRowsForAccountOrUnowned(it), trimmedSpoken) }
            ?: findBySpokenKey(dao.getSyncRowsUnstamped(), trimmedSpoken)
        val targetId = if (existing != null && existing.deletedAt != null && id == 0L) existing.id else id
        return when (resolveSaveAction(existing, id)) {
            SaveAction.INSERT -> {
                // Frozen v1.2: fresh wire identity + LWW metadata at creation.
                val now = MutationClock.next(context)
                val rowId = dao.insert(
                    CustomDictionaryEntry(
                        id = 0,
                        spokenText = trimmedSpoken,
                        replacementText = trimmedReplacement,
                        isEnabled = isEnabled,
                        syncId = java.util.UUID.randomUUID().toString(),
                        createdAt = now,
                        updatedAt = now,
                        deviceId = com.groq.voicetyper.sync.v1.DeviceIdProvider.getDeviceId(context),
                        dirty = true,
                        everPushed = false,
                        syncAccount = currentHash,
                        syncState = "local"
                    )
                )
                if (rowId != -1L) SaveResult.INSERTED
                else {
                    // A concurrent save of the same phrase won the race; the
                    // phrase is now present, so the user's intent is satisfied.
                    SaveResult.INSERTED
                }
            }
            SaveAction.UPDATE -> {
                try {
                    val base = if (targetId == existing?.id) existing else dao.getById(targetId)
                    // H3 rename: a spoken-text move changes the sync business
                    // key. The old row must NOT be edited in place (that keeps
                    // one syncId across two keys) — retire it and insert the
                    // renamed entry with a fresh wire identity, mirroring
                    // Windows `update_dictionary_entry_internal`. The toggle
                    // state rides with the row (a rename is not a toggle);
                    // the account partition rides with it too.
                    if (base != null &&
                        com.groq.voicetyper.sync.v1.DictionaryRecord.businessKeyOf(base.spokenText) !=
                        com.groq.voicetyper.sync.v1.DictionaryRecord.businessKeyOf(trimmedSpoken)
                    ) {
                        val retired = deleteEntryResolved(dao, base, context)
                        // A rename retires the old rule: the retired pair also
                        // earns deny memory (Windows dictionary.rs:689-690 —
                        // the rename path deletes through the helper there).
                        // Gated like the public deletes: an ownership-refused
                        // rename must not dismiss current-account pairs
                        // (reviewer A blocker B).
                        if (retired) {
                            dismissSuggestionsForDeletedEntry(context, base.spokenText, base.replacementText)
                        }
                        val now = com.groq.voicetyper.sync.v1.MutationClock.next(context)
                        dao.insert(
                            CustomDictionaryEntry(
                                id = 0,
                                spokenText = trimmedSpoken,
                                replacementText = trimmedReplacement,
                                isEnabled = base.isEnabled,
                                syncId = java.util.UUID.randomUUID().toString(),
                                createdAt = now,
                                updatedAt = now,
                                deviceId = com.groq.voicetyper.sync.v1.DeviceIdProvider.getDeviceId(context),
                                dirty = true,
                                everPushed = false,
                                syncAccount = base.syncAccount,
                                syncState = "local"
                            )
                        )
                        SaveResult.UPDATED
                    } else {
                        dao.update(
                            (base ?: CustomDictionaryEntry(id = targetId, spokenText = trimmedSpoken, replacementText = trimmedReplacement)).copy(
                                id = targetId,
                                spokenText = trimmedSpoken,
                                replacementText = trimmedReplacement,
                                isEnabled = isEnabled,
                                deletedAt = null,
                                // Re-create after delete is a NEWER state than the
                                // tombstone under pure LWW - bump updatedAt.
                                updatedAt = MutationClock.next(context),
                                deviceId = com.groq.voicetyper.sync.v1.DeviceIdProvider.getDeviceId(context),
                                dirty = true,
                                syncState = if (base?.serverFileId != null) "dirty" else base?.syncState ?: "local"
                            )
                        )
                        SaveResult.UPDATED
                    }
                } catch (e: android.database.sqlite.SQLiteConstraintException) {
                    // A concurrent save of the same spokenText won the race between the
                    // getBySpokenText check and this update; the phrase already exists,
                    // so preserve the user's intent instead of crashing.
                    SaveResult.PRESERVED
                }
            }
            SaveAction.PRESERVE -> SaveResult.PRESERVED
        }
    }

    suspend fun toggleEntryEnabled(context: Context, entry: CustomDictionaryEntry, isEnabled: Boolean) {
        // Ownership seam (D1a): refuse to mutate a row owned by another account.
        // Unowned rows stay actionable, matching the read filter.
        if (!belongsToCurrentAccount(entry, SyncAccounts.currentAccountHash.value)) return
        getDao(context).update(
            entry.copy(
                isEnabled = isEnabled,
                updatedAt = MutationClock.next(context),
                deviceId = entry.deviceId ?: com.groq.voicetyper.sync.v1.DeviceIdProvider.getDeviceId(context),
                dirty = true,
                syncState = if (entry.serverFileId != null) "dirty" else entry.syncState
            )
        )
    }

    /**
     * Applies an accepted autolearn correction onto the manual dictionary entry
     * that already owns the phrase, so accepting a suggestion never silently
     * discards the corrected text AND never leaves the confirmed rule
     * disabled (audit finding 1) - the accept is an explicit activation of
     * the pair.
     */
    suspend fun applyCorrectionToExisting(context: Context, spokenText: String, correctedText: String): ApplyOutcome {
        val dao = getDao(context)
        val currentHash = runCatching {
            AccountHash.of(SyncAuthSession(context.applicationContext).accountEmail)
        }.getOrNull()
        val existing = currentHash?.let { findBySpokenKey(dao.getRowsForAccountOrUnowned(it), spokenText.trim()) }
            ?: findBySpokenKey(dao.getSyncRowsUnstamped(), spokenText.trim())
            ?: return ApplyOutcome.NO_CHANGE
        return applyCorrectionToExistingResolved(dao, existing, context, correctedText)
    }

    /**
     * Outcome of applying an accepted correction onto an owning entry.
     * Reviewer A blocker A: a cap refusal must be distinguishable from
     * "already in that state" - otherwise acceptSuggestion reports success
     * for a correction it never wrote and silently deletes the suggestion.
     */
    enum class ApplyOutcome { APPLIED, NO_CHANGE, REFUSED_CAP }

    /**
     * Accept-resolution core (audit finding 1): re-enable the rule and apply
     * the accepted replacement whenever either differs from the stored
     * state. A tombstoned row is revived in place (same field-set as the
     * manual save path's re-create branch) - otherwise a deleted rule would
     * silently swallow a confirmed accept while the suggestion row already
     * vanished ("Added to Custom Dictionary" with the rule still gone).
     */
    internal suspend fun applyCorrectionToExistingResolved(
        dao: CustomDictionaryDao,
        existing: CustomDictionaryEntry,
        context: Context,
        correctedText: String
    ): ApplyOutcome {
        // Cap parity with the manual save path (audit finding 7): an accept
        // must never write an over-cap replacement through the apply seam.
        if (correctedText.trim().length > MAX_CORRECTED_CHARS || existing.spokenText.length > MAX_SPOKEN_CHARS) {
            return ApplyOutcome.REFUSED_CAP
        }
        if (existing.deletedAt == null && !existing.isEnabled) {
            dao.update(
                existing.copy(
                    replacementText = correctedText.trim(),
                    isEnabled = true,
                    updatedAt = MutationClock.next(context),
                    deviceId = existing.deviceId ?: com.groq.voicetyper.sync.v1.DeviceIdProvider.getDeviceId(context),
                    dirty = true,
                    syncState = if (existing.serverFileId != null) "dirty" else existing.syncState
                )
            )
            return ApplyOutcome.APPLIED
        }
        if (existing.deletedAt == null && existing.replacementText != correctedText.trim()) {
            dao.update(
                existing.copy(
                    replacementText = correctedText.trim(),
                    isEnabled = true,
                    updatedAt = MutationClock.next(context),
                    deviceId = existing.deviceId ?: com.groq.voicetyper.sync.v1.DeviceIdProvider.getDeviceId(context),
                    dirty = true,
                    syncState = if (existing.serverFileId != null) "dirty" else existing.syncState
                )
            )
            return ApplyOutcome.APPLIED
        }
        if (existing.deletedAt != null) {
            dao.update(
                existing.copy(
                    replacementText = correctedText.trim(),
                    isEnabled = true,
                    deletedAt = null,
                    updatedAt = MutationClock.next(context),
                    deviceId = existing.deviceId ?: com.groq.voicetyper.sync.v1.DeviceIdProvider.getDeviceId(context),
                    dirty = true,
                    everPushed = true,
                    syncState = "dirty"
                )
            )
            return ApplyOutcome.APPLIED
        }
        return ApplyOutcome.NO_CHANGE
    }

    internal suspend fun deleteEntryResolved(dao: CustomDictionaryDao, entry: CustomDictionaryEntry, context: android.content.Context? = null): Boolean {
        // Ownership seam (D1a): a row owned by a DIFFERENT account must never be
        // mutated, even when a stale id reaches this path — `getById` is a
        // primary-key lookup and is not account-filtered. Unowned rows stay
        // actionable, matching the read filter: visible implies deletable.
        if (!belongsToCurrentAccount(entry, SyncAccounts.currentAccountHash.value)) return false
        if (entry.everPushed || entry.serverFileId != null) {
            // Pushed at least once → tombstone propagates the deletion.
            val now = if (context != null) com.groq.voicetyper.sync.v1.MutationClock.next(context) else System.currentTimeMillis()
            dao.update(
                entry.copy(
                    deletedAt = now,
                    updatedAt = now,
                    deviceId = entry.deviceId
                        ?: context?.let { com.groq.voicetyper.sync.v1.DeviceIdProvider.getDeviceId(it) }
                        ?: "legacy",
                    dirty = true,
                    everPushed = true,
                    syncState = "dirty"
                )
            )
        } else {
            // Never uploaded → nothing to propagate; remove locally.
            dao.delete(entry)
        }
        return true
    }

    internal suspend fun deleteByIdResolved(dao: CustomDictionaryDao, id: Long, context: Context? = null): Boolean {
        val entry = dao.getById(id) ?: return false
        return deleteEntryResolved(dao, entry, context)
    }

    suspend fun deleteEntry(context: Context, entry: CustomDictionaryEntry) {
        // Dismissal only when the delete actually happened (reviewer A
        // cross-check 3-b): the ownership guard can refuse a foreign row, and
        // an un-deleted entry must never dismiss current-account pairs.
        val deleted = deleteEntryResolved(getDao(context), entry, context)
        if (deleted) {
            // Deny-memory parity (Windows delete -> suggestion.rs:690/756
            // dismiss_matching_suggestions): the user withdrew the rule for this
            // pair, so matching pending suggestions are dismissed and can never
            // auto-resurface (audit finding 3).
            dismissSuggestionsForDeletedEntry(context, entry.spokenText, entry.replacementText)
        }
    }

    suspend fun deleteById(context: Context, id: Long) {
        val dao = getDao(context)
        val row = dao.getById(id) ?: return
        val deleted = deleteByIdResolved(dao, id, context)
        if (deleted) {
            dismissSuggestionsForDeletedEntry(context, row.spokenText, row.replacementText)
        }
    }

    /**
     * Never fails the delete: a suggestion-side error logs and continues,
     * exactly like the Windows caller (dictionary.rs dismiss call sites).
     */
    private suspend fun dismissSuggestionsForDeletedEntry(context: Context, spokenText: String, replacementText: String) {
        runCatching {
            com.groq.voicetyper.autolearn.SuggestionRepository.dismissPairForDeletedEntry(
                context, spokenText, replacementText
            )
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
        }
    }

    internal fun belongsToCurrentAccount(entry: CustomDictionaryEntry, hash: String?): Boolean =
        entry.syncAccount == null || entry.syncAccount == hash
}
