package com.groq.voicetyper.dictionary

import android.content.Context
import com.groq.voicetyper.dictionary.data.CustomDictionaryDao
import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import com.groq.voicetyper.history.FluenceDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
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
            startObservingCache()
        }
    }

    private fun startObservingCache() {
        if (isObserving) return
        isObserving = true
        // Prime the cache on the background scope so a first call from the main
        // thread (e.g. DictionaryScreen composition) never blocks on a Room query.
        scope.launch {
            runCatching { dao?.getAllEnabledSync() }
                .getOrNull()
                ?.let { updateCompiledCache(it) }
        }
        scope.launch {
            try {
                dao?.getAllEnabled()?.collectLatest { entries ->
                    updateCompiledCache(entries)
                }
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
            runCatching { getDao(context).getAllEnabledSync() }
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
        return getDao(context).getAll()
    }

    fun getAllEnabled(context: Context): Flow<List<CustomDictionaryEntry>> {
        return getDao(context).getAllEnabled()
    }

    fun getAllEnabledSync(context: Context): List<CustomDictionaryEntry> {
        return getDao(context).getAllEnabledSync()
    }

    suspend fun saveEntry(context: Context, spokenText: String, replacementText: String, isEnabled: Boolean = true, id: Long = 0): SaveResult {
        val trimmedSpoken = spokenText.trim()
        val trimmedReplacement = replacementText.trim()
        if (trimmedSpoken.isEmpty() || trimmedReplacement.isEmpty()) return SaveResult.PRESERVED

        val dao = getDao(context)
        val existing = dao.getBySpokenText(trimmedSpoken)
        val targetId = if (existing != null && existing.deletedAt != null && id == 0L) existing.id else id
        return when (resolveSaveAction(existing, id)) {
            SaveAction.INSERT -> {
                val rowId = dao.insert(
                    CustomDictionaryEntry(
                        id = 0,
                        spokenText = trimmedSpoken,
                        replacementText = trimmedReplacement,
                        isEnabled = isEnabled,
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
        val existing = dao.getBySpokenText(spokenText.trim()) ?: return
        if (existing.deletedAt == null && existing.replacementText != correctedText.trim()) {
            dao.update(
                existing.copy(
                    replacementText = correctedText.trim(),
                    syncState = if (existing.serverFileId != null) "dirty" else existing.syncState
                )
            )
        }
    }

    internal suspend fun deleteEntryResolved(dao: CustomDictionaryDao, entry: CustomDictionaryEntry) {
        if (entry.serverFileId != null) {
            dao.update(
                entry.copy(
                    deletedAt = System.currentTimeMillis(),
                    syncState = "dirty"
                )
            )
        } else {
            dao.delete(entry)
        }
    }

    internal suspend fun deleteByIdResolved(dao: CustomDictionaryDao, id: Long) {
        val entry = dao.getById(id) ?: return
        deleteEntryResolved(dao, entry)
    }

    suspend fun deleteEntry(context: Context, entry: CustomDictionaryEntry) {
        deleteEntryResolved(getDao(context), entry)
    }

    suspend fun deleteById(context: Context, id: Long) {
        deleteByIdResolved(getDao(context), id)
    }
}
