package com.groq.voicetyper.sync.engine

import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry

/**
 * Pure import policy for the Room dictionary store (spec §10, §30.4).
 *
 * `custom_dictionary.spokenText` is unique — user-adds only, per §30.4. A
 * synced row whose spokenText collides with a live user row cannot be stored
 * as-is, so the store decides:
 *
 * - [Absorb] — identical content (spokenText + replacementText; `kind` is
 *   always `correction` on Android): the user's row adopts the incoming wire
 *   identity (§10 duplicate-identical → one row, no duplicates, no writes).
 *   Only exact, live content matches absorb — any case/content difference
 *   would make the adopted row diverge from the Drive file on the next pass.
 * - [Latch] — same spokenText, different content (or a tombstone): the
 *   incoming row is latched with `collision` (quarantined placeholder) so the
 *   user's row never silently loses and the unique index never breaks.
 * - [Upsert] — no collision: insert or update by wire UUID.
 */
internal object DictionaryImportPolicy {

    internal enum class Decision { Absorb, Latch, Upsert }

    fun decide(
        collidingLiveRow: CustomDictionaryEntry?,
        incoming: CustomDictionaryEntry,
    ): Decision {
        val colliding = collidingLiveRow ?: return Decision.Upsert
        val identical = incoming.deletedAt == null &&
            colliding.spokenText == incoming.spokenText &&
            colliding.replacementText == incoming.replacementText
        return if (identical) Decision.Absorb else Decision.Latch
    }
}