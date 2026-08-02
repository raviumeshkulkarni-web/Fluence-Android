package com.groq.voicetyper.autolearn

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.groq.voicetyper.autolearn.domain.AutoLearnPrivacyHelper
import com.groq.voicetyper.autolearn.domain.WordLcsExtractor
import org.junit.Assert.*
import org.junit.Test

class WordLcsExtractorTest {

    @Test
    fun testSingleWordSubstitution() {
        val committed = "I love groq very much"
        val edited = "I love Groq very much"

        val corrections = WordLcsExtractor.extractCorrections(committed, edited)

        assertEquals(1, corrections.size)
        assertEquals("groq", corrections[0].spokenText)
        assertEquals("Groq", corrections[0].correctedText)
    }

    @Test
    fun testIdenticalTextNoCorrections() {
        val committed = "This is a clean test sentence"
        val edited = "This is a clean test sentence"

        val corrections = WordLcsExtractor.extractCorrections(committed, edited)

        assertTrue(corrections.isEmpty())
    }

    @Test
    fun testCompleteRewriteDiscarded() {
        val committed = "apple banana cherry date elderberry fig grape"
        val edited = "completely unrelated text written by user"

        val corrections = WordLcsExtractor.extractCorrections(committed, edited)

        assertTrue(corrections.isEmpty())
    }

    @Test
    fun testOversizedTextIsSkipped() {
        val longText = (1..2000).joinToString(" ") { "word$it" }
        val edited = "$longText replaced"

        val corrections = WordLcsExtractor.extractCorrections(longText, edited)

        assertTrue(corrections.isEmpty())
    }

    @Test
    fun testWordCountCapBindsLcsCost() {
        val manyWords = (1..1200).joinToString(" ") { "a$it" }
        val edited = "$manyWords z"

        val corrections = WordLcsExtractor.extractCorrections(manyWords, edited)

        assertTrue(corrections.isEmpty())
    }

    @Test
    fun testPrivacySignalPasswordBlocked() {
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val allowed = AutoLearnPrivacyHelper.isAutoLearnAllowed(editorInfo)

        assertFalse(allowed)
    }

    @Test
    fun testPrivacySignalNoPersonalizedLearningBlocked() {
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }

        val allowed = AutoLearnPrivacyHelper.isAutoLearnAllowed(editorInfo)

        assertFalse(allowed)
    }

    @Test
    fun testNormalTextFieldAllowed() {
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            imeOptions = 0
        }

        val allowed = AutoLearnPrivacyHelper.isAutoLearnAllowed(editorInfo)

        assertTrue(allowed)
    }
}
