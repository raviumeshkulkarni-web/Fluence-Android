package com.groq.voicetyper.dictionary

import com.groq.voicetyper.dictionary.data.CustomDictionaryEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryRepositoryTest {

    private val existing = CustomDictionaryEntry(
        id = 7,
        spokenText = "groq",
        replacementText = "Groq",
        isEnabled = true
    )

    @Test
    fun `new entry without an existing phrase is inserted`() {
        assertEquals(
            DictionaryRepository.SaveAction.INSERT,
            DictionaryRepository.resolveSaveAction(null, 0L)
        )
    }

    @Test
    fun `editing an existing row updates in place`() {
        assertEquals(
            DictionaryRepository.SaveAction.UPDATE,
            DictionaryRepository.resolveSaveAction(existing, 7L)
        )
        assertEquals(
            DictionaryRepository.SaveAction.UPDATE,
            DictionaryRepository.resolveSaveAction(null, 7L)
        )
    }

    @Test
    fun `adding or renaming onto an existing phrase preserves that row`() {
        assertEquals(
            DictionaryRepository.SaveAction.PRESERVE,
            DictionaryRepository.resolveSaveAction(existing, 0L)
        )
        assertEquals(
            DictionaryRepository.SaveAction.PRESERVE,
            DictionaryRepository.resolveSaveAction(existing, 99L)
        )
    }
}
