package com.groq.voicetyper.autolearn

import android.content.Context
import android.util.Log
import com.groq.voicetyper.autolearn.data.SuggestionDao
import com.groq.voicetyper.autolearn.data.SuggestionEntry
import com.groq.voicetyper.autolearn.domain.DecisionCore
import com.groq.voicetyper.dictionary.DictionaryRepository
import com.groq.voicetyper.history.FluenceDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

object SuggestionRepository {
    private const val TAG = "SuggestionRepo"
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

    /**
     * The account's current enabled dictionary reduced to canonical pair
     * keys (Windows monitor builds the same set per decision batch).
     *
     * Fail-open: a dictionary read error degrades to an empty known set,
     * which only disables the already-known skip - it can never demote a
     * valid suggestion or block capture.
     */
    suspend fun knownPairKeys(context: Context): Set<String> {
        return try {
            DictionaryRepository.getAllEnabledSync(context)
                .map { DecisionCore.canonicalPairKey(it.spokenText, it.replacementText) }
                .toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "known set unavailable, proceeding without known-skip", e)
            emptySet()
        }
    }

    suspend fun recordCorrectionCandidate(
        context: Context,
        spokenText: String,
        correctedText: String,
        knownPairKeys: Set<String>? = null
    ) {
        val spoken = spokenText.trim()
        val corrected = correctedText.trim()
        if (spoken.isEmpty() || corrected.isEmpty()) return

        val dao = getDao(context)
        val known = knownPairKeys ?: knownPairKeys(context)
        recordCandidateResolved(dao, known, spoken, corrected, System.currentTimeMillis())
    }

    /**
     * Scheduler-free core of [recordCorrectionCandidate] behind an
     * injectable DAO, mirroring the `saveEntryResolved` seam convention.
     * Returns false when the shared gates rejected the pair.
     */
    internal suspend fun recordCandidateResolved(
        dao: SuggestionDao,
        knownPairKeys: Set<String>,
        spoken: String,
        corrected: String,
        now: Long
    ): Boolean {
        if (DecisionCore.invalidPairReason(spoken, corrected, knownPairKeys) != null) {
            return false
        }

        // Canonical merge (Windows classify_candidate parity: bump ANY
        // existing row without resetting its lifecycle status - a dismissed
        // stays dismissed, a pending keeps rising): Android stays on the
        // exact-case unique index, so a canonical twin folds into that row
        // by id instead of spawning a variant twin.
        val exact = dao.findByPair(spoken, corrected)
        if (exact != null) {
            dao.bumpById(exact.id, now)
            return true
        }
        val canonicalKey = DecisionCore.canonicalPairKey(spoken, corrected)
        val twin = dao.getAllRows()
            .firstOrNull {
                DecisionCore.canonicalPairKey(it.spokenText, it.correctedText) == canonicalKey
            }
        if (twin != null) {
            dao.bumpById(twin.id, now)
            return true
        }

        dao.upsertCandidate(spoken, corrected, now)
        return true
    }

    /**
     * Stale-pending sweep (Windows `purge_invalid_suggestions`: re-judge
     * EVERY pending row, demote rows that no longer pass the shared
     * gates). Demote keeps the row for diagnosis; idempotent because
     * re-running finds only still-valid rows left.
     */
    internal suspend fun purgeInvalidPendingResolved(
        dao: SuggestionDao,
        knownPairKeys: Set<String>
    ): Int {
        val pending = dao.getPendingForPurge()
        var demoted = 0
        for (row in pending) {
            if (DecisionCore.invalidPairReason(row.spokenText, row.correctedText, knownPairKeys) == null) {
                continue
            }
            demoted += dao.demotePending(row.id)
        }
        return demoted
    }

    suspend fun purgeInvalidPending(context: Context): Int {
        val dao = getDao(context)
        return purgeInvalidPendingResolved(dao, knownPairKeys(context))
    }

    suspend fun acceptSuggestion(context: Context, suggestion: SuggestionEntry) {
        // Promote to Manual Custom Dictionary
        val result = DictionaryRepository.saveEntry(
            context = context,
            spokenText = suggestion.spokenText,
            replacementText = suggestion.correctedText,
            isEnabled = true
        )
        if (result == DictionaryRepository.SaveResult.PRESERVED) {
            // A manual entry already owns this phrase. Apply the accepted
            // correction to that entry so the suggestion isn't silently dropped.
            DictionaryRepository.applyCorrectionToExisting(
                context = context,
                spokenText = suggestion.spokenText,
                correctedText = suggestion.correctedText
            )
        }
        // Remove from pending suggestions table
        getDao(context).delete(suggestion)
    }

    suspend fun dismissSuggestion(context: Context, suggestion: SuggestionEntry) {
        dismissSuggestionResolved(getDao(context), suggestion)
    }

    /**
     * Scheduler-free core of [dismissSuggestion] (injectable-DAO seam).
     * Windows `dismiss_suggestion` parity (suggestion.rs:635-650): a
     * dismissal is "deny memory" — the row stays behind with DISMISSED
     * status (hidden from the pending list, sticky under future bumps) so
     * the pair can never resurface as pending later; deleting the row
     * would forget the denial entirely. [SuggestionDao.demotePending] is
     * status-guarded on PENDING, mirroring the Windows Pending-only
     * lookup: dismissing a non-pending row is a no-op both sides.
     */
    internal suspend fun dismissSuggestionResolved(
        dao: SuggestionDao,
        suggestion: SuggestionEntry
    ): Int {
        return dao.demotePending(suggestion.id)
    }

    suspend fun clearAll(context: Context) {
        getDao(context).deleteAll()
    }
}
