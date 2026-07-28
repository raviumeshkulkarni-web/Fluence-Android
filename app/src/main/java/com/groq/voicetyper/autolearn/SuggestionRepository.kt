package com.groq.voicetyper.autolearn

import android.content.Context
import com.groq.voicetyper.autolearn.data.SuggestionDao
import com.groq.voicetyper.autolearn.data.SuggestionEntry
import com.groq.voicetyper.dictionary.DictionaryRepository
import com.groq.voicetyper.history.FluenceDatabase
import kotlinx.coroutines.flow.Flow

object SuggestionRepository {
    private var dao: SuggestionDao? = null

    fun init(context: Context) {
        if (dao == null) {
            dao = FluenceDatabase.getInstance(context.applicationContext).suggestionDao()
        }
    }

    private fun getDao(context: Context): SuggestionDao {
        init(context)
        return dao!!
    }

    fun getPendingSuggestions(context: Context): Flow<List<SuggestionEntry>> {
        return getDao(context).getPendingSuggestions()
    }

    suspend fun recordCorrectionCandidate(context: Context, spokenText: String, correctedText: String) {
        val spoken = spokenText.trim()
        val corrected = correctedText.trim()
        if (spoken.isEmpty() || corrected.isEmpty() || spoken.equals(corrected, ignoreCase = true)) return

        getDao(context).upsertCandidate(spoken, corrected)
    }

    suspend fun acceptSuggestion(context: Context, suggestion: SuggestionEntry) {
        // Promote to Manual Custom Dictionary
        DictionaryRepository.saveEntry(
            context = context,
            spokenText = suggestion.spokenText,
            replacementText = suggestion.correctedText,
            isEnabled = true
        )
        // Remove from pending suggestions table
        getDao(context).delete(suggestion)
    }

    suspend fun dismissSuggestion(context: Context, suggestion: SuggestionEntry) {
        getDao(context).delete(suggestion)
    }

    suspend fun clearAll(context: Context) {
        getDao(context).deleteAll()
    }
}
