package com.groq.voicetyper.ime

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.os.Handler
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.SurroundingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FieldStallGuardTest {

    private class FakeInputConnection : InputConnection {
        var textBefore: CharSequence? = null
        var textAfter: CharSequence? = null
        var selectedText: CharSequence? = null
        var capsMode: Int = 0
        var extractedText: ExtractedText? = null
        var surroundingText: SurroundingText? = null

        var onGetTextBefore: (() -> Unit)? = null
        var onGetTextAfter: (() -> Unit)? = null
        var onGetSelectedText: (() -> Unit)? = null
        var onGetCapsMode: (() -> Unit)? = null

        var getTextBeforeCount = 0
        var getTextAfterCount = 0
        var getSelectedTextCount = 0
        var getCapsModeCount = 0

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
            getTextBeforeCount++
            onGetTextBefore?.invoke()
            return textBefore
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
            getTextAfterCount++
            onGetTextAfter?.invoke()
            return textAfter
        }

        override fun getSelectedText(flags: Int): CharSequence? {
            getSelectedTextCount++
            onGetSelectedText?.invoke()
            return selectedText
        }

        override fun getCursorCapsMode(reqModes: Int): Int {
            getCapsModeCount++
            onGetCapsMode?.invoke()
            return capsMode
        }

        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = extractedText
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = true
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = true
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun finishComposingText(): Boolean = true
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = true
        override fun commitCompletion(text: CompletionInfo?): Boolean = true
        override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true
        override fun setSelection(start: Int, end: Int): Boolean = true
        override fun performEditorAction(editorAction: Int): Boolean = true
        override fun performContextMenuAction(id: Int): Boolean = true
        override fun beginBatchEdit(): Boolean = true
        override fun endBatchEdit(): Boolean = true
        override fun sendKeyEvent(event: KeyEvent?): Boolean = true
        override fun clearMetaKeyStates(states: Int): Boolean = true
        override fun reportFullscreenMode(enabled: Boolean): Boolean = true
        override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = true
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = true
        override fun getHandler(): Handler? = null
        override fun closeConnection() {}
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = true
        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? = surroundingText
    }

    private lateinit var baseConnection: FakeInputConnection
    private var simulatedClock: Long = 10_000L
    private lateinit var stallGuard: FieldStallGuard

    @Before
    fun setUp() {
        baseConnection = FakeInputConnection()
        simulatedClock = 10_000L
        stallGuard = FieldStallGuard(baseConnection, clock = { simulatedClock })
    }

    @Test
    fun fastRead_doesNotStall() {
        baseConnection.textBefore = "hello"
        baseConnection.onGetTextBefore = {
            simulatedClock += 50 // 50ms read (fast)
        }

        val result = stallGuard.getTextBeforeCursor(10, 0)
        assertEquals("hello", result)
        assertEquals(1, baseConnection.getTextBeforeCount)
        assertFalse(stallGuard.isStalled)
    }

    @Test
    fun slowRead_triggersStallForHoldDuration() {
        baseConnection.textBefore = "slow text"
        baseConnection.onGetTextBefore = {
            simulatedClock += 1_000 // 1000ms read (slow!)
        }

        val result = stallGuard.getTextBeforeCursor(10, 0)
        assertEquals("slow text", result)
        assertTrue(stallGuard.isStalled)

        // Subsequent reads within HOLD_MS (3000ms) return null without calling base
        baseConnection.textAfter = "after"
        val afterResult = stallGuard.getTextAfterCursor(10, 0)
        assertNull(afterResult)
        assertEquals(0, baseConnection.getTextAfterCount)

        val capsResult = stallGuard.getCursorCapsMode(0)
        assertEquals(0, capsResult)
        assertEquals(0, baseConnection.getCapsModeCount)
    }

    @Test
    fun stall_clearedAfterHoldDurationPasses() {
        baseConnection.textBefore = "slow"
        baseConnection.onGetTextBefore = {
            simulatedClock += 1_200 // 1200ms read (slow)
        }
        stallGuard.getTextBeforeCursor(10, 0)
        assertTrue(stallGuard.isStalled)

        // Advance clock past HOLD_MS (3000ms)
        simulatedClock += 3_500L
        assertFalse(stallGuard.isStalled)

        baseConnection.selectedText = "selected"
        val selected = stallGuard.getSelectedText(0)
        assertEquals("selected", selected)
        assertEquals(1, baseConnection.getSelectedTextCount)
    }

    @Test
    fun noteResponsive_clearsStallEarly() {
        baseConnection.textBefore = "slow"
        baseConnection.onGetTextBefore = {
            simulatedClock += 1_500
        }
        stallGuard.getTextBeforeCursor(10, 0)
        assertTrue(stallGuard.isStalled)

        // Field signals responsiveness
        stallGuard.noteResponsive()
        assertFalse(stallGuard.isStalled)

        baseConnection.textBefore = "fast again"
        val result = stallGuard.getTextBeforeCursor(10, 0)
        assertEquals("fast again", result)
    }

    @Test
    fun readException_safelyReturnsDefaultAndStallsIfSlow() {
        baseConnection.onGetTextBefore = {
            simulatedClock += 1_200
            throw RuntimeException("IPC binder died")
        }

        val result = stallGuard.getTextBeforeCursor(10, 0)
        assertNull(result)
        assertTrue(stallGuard.isStalled)
    }
}
