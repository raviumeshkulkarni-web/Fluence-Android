package com.groq.voicetyper.ime

import android.os.Bundle
import android.os.Handler
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeDoubleSpaceTest {

    private class FakeInputConnection(
        private var text: String = "",
        private var afterText: String = ""
    ) : InputConnection {
        var committedTexts = mutableListOf<String>()
        var deletedLengths = mutableListOf<Pair<Int, Int>>()

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            return if (text.length <= n) text else text.substring(text.length - n)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
            return if (afterText.length <= n) afterText else afterText.substring(0, n)
        }

        override fun getSelectedText(flags: Int): CharSequence? = null

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletedLengths.add(beforeLength to afterLength)
            if (beforeLength <= text.length) {
                text = text.dropLast(beforeLength)
            }
            return true
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val str = text?.toString() ?: ""
            committedTexts.add(str)
            this.text += str
            return true
        }

        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = true
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun finishComposingText(): Boolean = true
        override fun commitCompletion(text: CompletionInfo?): Boolean = true
        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true
        override fun setSelection(start: Int, end: Int): Boolean = true
        override fun performEditorAction(editorAction: Int): Boolean = true
        override fun performContextMenuAction(id: Int): Boolean = true
        override fun clearMetaKeyStates(states: Int): Boolean = true
        override fun reportFullscreenMode(enabled: Boolean): Boolean = true
        override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = true
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = true
        override fun getHandler(): Handler? = null
        override fun closeConnection() {}
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = true
        override fun sendKeyEvent(event: KeyEvent?): Boolean = true

        fun currentText(): String = text
    }

    @Test
    fun `single space commits space`() {
        val fakeConn = FakeInputConnection("Hello")
        val helper = ImeInputConnectionHelper { fakeConn }

        helper.commitSpace(currentTimeMs = 1000L)

        assertEquals("Hello ", fakeConn.currentText())
    }

    @Test
    fun `double tap space within 400ms turns into period and space`() {
        val fakeConn = FakeInputConnection("Hello")
        val helper = ImeInputConnectionHelper { fakeConn }

        helper.commitSpace(currentTimeMs = 1000L)
        assertEquals("Hello ", fakeConn.currentText())

        // Second tap 200ms later
        helper.commitSpace(currentTimeMs = 1200L)
        assertEquals("Hello. ", fakeConn.currentText())
        assertEquals(listOf(1 to 0), fakeConn.deletedLengths)
    }

    @Test
    fun `double tap space works after closing quote or bracket`() {
        val fakeConn = FakeInputConnection("\"Hello\"")
        val helper = ImeInputConnectionHelper { fakeConn }

        helper.commitSpace(currentTimeMs = 1000L)
        helper.commitSpace(currentTimeMs = 1200L)
        assertEquals("\"Hello\". ", fakeConn.currentText())
    }

    @Test
    fun `double tap space is suppressed in URI fields`() {
        val fakeConn = FakeInputConnection("https://example")
        val helper = ImeInputConnectionHelper { fakeConn }
        val uriEditorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        helper.commitSpace(editorInfo = uriEditorInfo, currentTimeMs = 1000L)
        helper.commitSpace(editorInfo = uriEditorInfo, currentTimeMs = 1200L)
        assertEquals("https://example  ", fakeConn.currentText())
    }

    @Test
    fun `double tap space is suppressed in Email fields`() {
        val fakeConn = FakeInputConnection("user@example")
        val helper = ImeInputConnectionHelper { fakeConn }
        val emailEditorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        helper.commitSpace(editorInfo = emailEditorInfo, currentTimeMs = 1000L)
        helper.commitSpace(editorInfo = emailEditorInfo, currentTimeMs = 1200L)
        assertEquals("user@example  ", fakeConn.currentText())
    }

    @Test
    fun `slow second space tap does not turn into period`() {
        val fakeConn = FakeInputConnection("Hello")
        val helper = ImeInputConnectionHelper { fakeConn }

        helper.commitSpace(currentTimeMs = 1000L)
        assertEquals("Hello ", fakeConn.currentText())

        // Second tap 600ms later (too slow)
        helper.commitSpace(currentTimeMs = 1600L)
        assertEquals("Hello  ", fakeConn.currentText())
        assertEquals(0, fakeConn.deletedLengths.size)
    }

    @Test
    fun `double tap space mid-paragraph converts when followed by whitespace`() {
        val fakeConn = FakeInputConnection(text = "Hello", afterText = " next")
        val helper = ImeInputConnectionHelper { fakeConn }

        helper.commitSpace(currentTimeMs = 1000L)
        helper.commitSpace(currentTimeMs = 1200L)

        assertEquals("Hello. ", fakeConn.currentText())
        assertEquals(listOf(1 to 0), fakeConn.deletedLengths)
    }

    @Test
    fun `double tap space stays double space when followed by letter`() {
        val fakeConn = FakeInputConnection(text = "Hello", afterText = "next")
        val helper = ImeInputConnectionHelper { fakeConn }

        helper.commitSpace(currentTimeMs = 1000L)
        helper.commitSpace(currentTimeMs = 1200L)

        assertEquals("Hello  ", fakeConn.currentText())
        assertEquals(0, fakeConn.deletedLengths.size)
    }
}
