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

    private var dao: CustomDictionaryDao? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val cachedRules = AtomicReference<List<CompiledDictionaryRule>>(emptyList())
    @Volatile private var isObserving = false

    internal fun resolveSaveAction(existing: CustomDictionaryEntry?, id: Long): SaveAction = when {
        existing != null && existing.id != id -> SaveAction.PRESERVE
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
        runCatching { dao?.getAllEnabledSync() }
            .getOrNull()
            ?.let { updateCompiledCache(it) }
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

    suspend fun saveEntry(context: Context, spokenText: String, replacementText: String, isEnabled: Boolean = true, id: Long = 0) {
        val trimmedSpoken = spokenText.trim()
        val trimmedReplacement = replacementText.trim()
        if (trimmedSpoken.isEmpty() || trimmedReplacement.isEmpty()) return

        val dao = getDao(context)
        val existing = dao.getBySpokenText(trimmedSpoken)
        when (resolveSaveAction(existing, id)) {
            SaveAction.INSERT -> dao.insert(
                CustomDictionaryEntry(
                    id = 0,
                    spokenText = trimmedSpoken,
                    replacementText = trimmedReplacement,
                    isEnabled = isEnabled
                )
            )
            SaveAction.UPDATE -> dao.update(
                CustomDictionaryEntry(
                    id = id,
                    spokenText = trimmedSpoken,
                    replacementText = trimmedReplacement,
                    isEnabled = isEnabled
                )
            )
            SaveAction.PRESERVE -> Unit
        }
    }

    suspend fun toggleEntryEnabled(context: Context, entry: CustomDictionaryEntry, isEnabled: Boolean) {
        getDao(context).update(entry.copy(isEnabled = isEnabled))
    }

    suspend fun deleteEntry(context: Context, entry: CustomDictionaryEntry) {
        getDao(context).delete(entry)
    }

    suspend fun deleteById(context: Context, id: Long) {
        getDao(context).deleteById(id)
    }
}
