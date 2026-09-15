package com.groq.voicetyper.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInfoHelperTest {

    @Test
    fun `null EditorInfo is treated as unsafe`() {
        assertFalse(EditorInfoHelper.isFieldSafeForVoice(null))
        assertFalse(EditorInfoHelper.isAutoLearnAllowed(null))
        assertFalse(EditorInfoHelper.isMultiline(null))
        assertEquals(EditorInfo.IME_ACTION_NONE, EditorInfoHelper.getActionId(null))
    }

    @Test
    fun `TYPE_NULL is fail-closed unsafe`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_NULL
        }
        assertFalse(EditorInfoHelper.isFieldSafeForVoice(info))
    }

    @Test
    fun `text password variations are unsafe`() {
        val variations = listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        )
        for (v in variations) {
            val info = EditorInfo().apply {
                inputType = InputType.TYPE_CLASS_TEXT or v
            }
            assertFalse("Variation $v should be unsafe", EditorInfoHelper.isFieldSafeForVoice(info))
        }
    }

    @Test
    fun `number password variation is unsafe`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        assertFalse(EditorInfoHelper.isFieldSafeForVoice(info))
    }

    @Test
    fun `standard text and multiline text are safe`() {
        val standardInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        assertTrue(EditorInfoHelper.isFieldSafeForVoice(standardInfo))
        assertFalse(EditorInfoHelper.isMultiline(standardInfo))

        val multilineInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        assertTrue(EditorInfoHelper.isFieldSafeForVoice(multilineInfo))
        assertTrue(EditorInfoHelper.isMultiline(multilineInfo))
    }

    @Test
    fun `numeric field is safe for voice but recognized as numeric`() {
        val numericInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL
        }
        assertTrue(EditorInfoHelper.isFieldSafeForVoice(numericInfo))
        assertTrue(EditorInfoHelper.isNumericField(numericInfo))
    }

    @Test
    fun `IME_FLAG_NO_PERSONALIZED_LEARNING allows voice but suspends AutoLearn`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
        assertTrue(EditorInfoHelper.isFieldSafeForVoice(info))
        assertFalse(EditorInfoHelper.isAutoLearnAllowed(info))
    }

    @Test
    fun `actionId extraction checks actionId and imeOptions`() {
        val infoWithExplicitAction = EditorInfo().apply {
            actionId = EditorInfo.IME_ACTION_SEARCH
        }
        assertEquals(EditorInfo.IME_ACTION_SEARCH, EditorInfoHelper.getActionId(infoWithExplicitAction))

        val infoWithImeOptions = EditorInfo().apply {
            actionId = 0
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        assertEquals(EditorInfo.IME_ACTION_SEND, EditorInfoHelper.getActionId(infoWithImeOptions))
    }
}
