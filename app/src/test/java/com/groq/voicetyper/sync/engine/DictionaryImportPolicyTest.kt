package com.groq.voicetyper.sync.engine

import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryImportPolicyTest {

    private fun liveEntry(spoken: String, corrected: String): CustomDictionaryEntry =
        CustomDictionaryEntry(
            spokenText = spoken,
            replacementText = corrected,
            isEnabled = true,
            syncId = null,
        )

    private fun incoming(spoken: String, corrected: String, deletedAt: Long? = null): CustomDictionaryEntry =
        CustomDictionaryEntry(
            spokenText = spoken,
            replacementText = corrected,
            isEnabled = true,
            syncId = "uuid-incoming",
            createdAt = 1_000L,
            deletedAt = deletedAt,
            syncState = "clean",
        )

    @Test
    fun noCollisionUpserts() {
        assertEquals(
            DictionaryImportPolicy.Decision.Upsert,
            DictionaryImportPolicy.decide(null, incoming("one", "1"))
        )
    }

    @Test
    fun identicalLiveContentAbsorbs() {
        assertEquals(
            DictionaryImportPolicy.Decision.Absorb,
            DictionaryImportPolicy.decide(liveEntry("brb", "be right back"), incoming("brb", "be right back"))
        )
    }

    @Test
    fun differentContentLatches() {
        assertEquals(
            DictionaryImportPolicy.Decision.Latch,
            DictionaryImportPolicy.decide(liveEntry("brb", "be right back"), incoming("brb", "be back"))
        )
    }

    @Test
    fun caseDifferenceLatches() {
        assertEquals(
            DictionaryImportPolicy.Decision.Latch,
            DictionaryImportPolicy.decide(liveEntry("BRB", "be right back"), incoming("brb", "be right back"))
        )
    }

    @Test
    fun tombstoneCollisionLatches() {
        assertEquals(
            DictionaryImportPolicy.Decision.Latch,
            DictionaryImportPolicy.decide(liveEntry("brb", "be right back"), incoming("brb", "be right back", deletedAt = 5_000L))
        )
    }

    @Test
    fun blankCollisionStillLatches() {
        // Even an empty user row owns the spokenText slot (unique index).
        assertEquals(
            DictionaryImportPolicy.Decision.Latch,
            DictionaryImportPolicy.decide(liveEntry("", ""), incoming("brb", "be right back"))
        )
    }
}