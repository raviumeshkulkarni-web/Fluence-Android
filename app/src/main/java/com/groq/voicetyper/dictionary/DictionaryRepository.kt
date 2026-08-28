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

    private fun getDao(context: Context): CustomDictionaryDao {
        init(context)
        return dao!!
    }

    fun getAll(context: Context): Flow<List<CustomDictionaryEntry>> {
        val dictionaryDao = getDao(context)
        return SyncAccounts.currentAccountHash.combine(dictionaryDao.getAll()) { hash, entries ->
            entries.filter { belongsToCurrentAccount(it, hash) }
        }
    }

    fun getAllEnabled(context: Context): Flow<List<CustomDictionaryEntry>> {
        val dictionaryDao = getDao(context)
        return SyncAccounts.currentAccountHash.combine(dictionaryDao.getAllEnabled()) { hash, entries ->
            entries.filter { belongsToCurrentAccount(it, hash) }
        }
    }

    fun getAllEnabledSync(context: Context): List<CustomDictionaryEntry> {
        return getDao(context).getAllEnabledSync()
            .filter { belongsToCurrentAccount(it, SyncAccounts.currentAccountHash.value) }
    }

    suspend fun saveEntry(context: Context, spokenText: String, replacementText: String, isEnabled: Boolean = true, id: Long = 0): SaveResult {
        val trimmedSpoken = spokenText.trim()
        val trimmedReplacement = replacementText.trim()
        if (trimmedSpoken.isEmpty() || trimmedReplacement.isEmpty()) return SaveResult.PRESERVED

        val dao = getDao(context)
        val currentHash = runCatching {
            AccountHash.of(SyncAuthSession(context.applicationContext).accountEmail)
        }.getOrNull()
        val existing = currentHash?.let { dao.getBySpokenTextForAccount(trimmedSpoken, it) }
            ?: dao.getBySpokenTextUnstamped(trimmedSpoken)
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
                            dirty = true,
                            syncState = if (base?.serverFileId != null) "dirty" else base?.syncState ?: "local"
                        )
                    )
                    SaveResult.UPDATED
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
        getDao(context).update(
            entry.copy(
                isEnabled = isEnabled,
                updatedAt = MutationClock.next(context),
                dirty = true,
                syncState = if (entry.serverFileId != null) "dirty" else entry.syncState
            )
        )
    }

    /**
     * Applies an accepted autolearn correction onto the manual dictionary entry
     * that already owns the phrase, so accepting a suggestion never silently
     * discards the corrected text.
     */
    suspend fun applyCorrectionToExisting(context: Context, spokenText: String, correctedText: String) {
        val dao = getDao(context)
        val currentHash = runCatching {
            AccountHash.of(SyncAuthSession(context.applicationContext).accountEmail)
        }.getOrNull()
        val existing = currentHash?.let { dao.getBySpokenTextForAccount(spokenText.trim(), it) }
            ?: dao.getBySpokenTextUnstamped(spokenText.trim())
            ?: return
        if (existing.deletedAt == null && existing.replacementText != correctedText.trim()) {
            dao.update(
                existing.copy(
                    replacementText = correctedText.trim(),
                    updatedAt = MutationClock.next(context),
                    dirty = true,
                    syncState = if (existing.serverFileId != null) "dirty" else existing.syncState
                )
            )
        }
    }

    internal suspend fun deleteEntryResolved(dao: CustomDictionaryDao, entry: CustomDictionaryEntry, context: android.content.Context? = null) {
        if (entry.everPushed || entry.serverFileId != null) {
            // Pushed at least once → tombstone propagates the deletion.
            val now = if (context != null) com.groq.voicetyper.sync.v1.MutationClock.next(context) else System.currentTimeMillis()
            dao.update(
                entry.copy(
                    deletedAt = now,
                    updatedAt = now,
                    dirty = true,
                    everPushed = true,
                    syncState = "dirty"
                )
            )
        } else {
            // Never uploaded → nothing to propagate; remove locally.
            dao.delete(entry)
        }
    }

    internal suspend fun deleteByIdResolved(dao: CustomDictionaryDao, id: Long, context: Context? = null) {
        val entry = dao.getById(id) ?: return
        deleteEntryResolved(dao, entry, context)
    }

    suspend fun deleteEntry(context: Context, entry: CustomDictionaryEntry) {
        deleteEntryResolved(getDao(context), entry, context)
    }

    suspend fun deleteById(context: Context, id: Long) {
        deleteByIdResolved(getDao(context), id, context)
    }

    private fun belongsToCurrentAccount(entry: CustomDictionaryEntry, hash: String?): Boolean =
        entry.syncAccount == null || entry.syncAccount == hash
}
