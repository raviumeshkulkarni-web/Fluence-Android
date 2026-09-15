package com.groq.voicetyper.ime

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class ImeInputConnectionHelper(private val connectionProvider: () -> InputConnection?) {

    /**
     * Inserts text safely wrapped in a batch edit.
     */
    fun commitTextSafely(text: String, newCursorPosition: Int = 1) {
        val conn = connectionProvider() ?: return
        conn.beginBatchEdit()
        try {
            conn.commitText(text, newCursorPosition)
        } finally {
            conn.endBatchEdit()
        }
    }

    /**
     * Inserts punctuation using conservative smart spacing.
     */
    fun commitPunctuation(symbol: String) {
        val conn = connectionProvider() ?: return
        val hasSelection = !conn.getSelectedText(0).isNullOrEmpty()
        val textBefore = conn.getTextBeforeCursor(20, 0)?.toString() ?: ""
        val textAfter = conn.getTextAfterCursor(10, 0)?.toString() ?: ""

        val action = SmartPunctuation.resolve(
            symbol = symbol,
            textBefore = textBefore,
            textAfter = textAfter,
            hasSelection = hasSelection
        )

        conn.beginBatchEdit()
        try {
            if (action.deleteCount > 0) {
                conn.deleteSurroundingText(action.deleteCount, 0)
            }
            conn.commitText(action.insertText, 1)
        } finally {
            conn.endBatchEdit()
        }
    }

    /**
     * Executes the appropriate action according to the verified 3-tier fallback order:
     * 1. performEditorAction(actionId) if single-line with a valid action ID.
     * 2. Commit newline (\n) if multiline.
     * 3. Send KEYCODE_ENTER events as a last resort.
     *
     * Note: performEditorAction and sendKeyEvent are NOT wrapped in batch edit.
     */
    fun performAction(info: EditorInfo?) {
        val conn = connectionProvider() ?: return

        if (info == null) {
            conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            return
        }

        val isMultiline = EditorInfoHelper.isMultiline(info)
        val actionId = EditorInfoHelper.getActionId(info)

        if (!isMultiline && actionId != 0 &&
            actionId != EditorInfo.IME_ACTION_NONE &&
            actionId != EditorInfo.IME_ACTION_UNSPECIFIED
        ) {
            conn.performEditorAction(actionId)
        } else if (isMultiline) {
            commitTextSafely("\n", 1)
        } else {
            conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    /**
     * Moves the cursor relative to current position safely without sending DPAD key events
     * that trigger focus navigation or keyboard dismissal in host applications.
     */
    fun moveCursor(direction: Int) {
        val conn = connectionProvider() ?: return
        try {
            // 1. Try extracted text (standard across Android TextView/EditText)
            val extracted = conn.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            if (extracted != null && extracted.text != null) {
                val current = extracted.selectionStart
                val textLen = extracted.text.length
                val newPos = (current + direction).coerceIn(0, textLen)
                conn.setSelection(newPos, newPos)
                return
            }

            // 2. Fallback using textBeforeCursor length
            val before = conn.getTextBeforeCursor(10000, 0)
            if (before != null) {
                val current = before.length
                val after = conn.getTextAfterCursor(10000, 0)
                val maxPos = current + (after?.length ?: 0)
                val newPos = (current + direction).coerceIn(0, maxPos)
                conn.setSelection(newPos, newPos)
                return
            }
        } catch (e: Exception) {
            android.util.Log.e("ImeInputConnection", "Error moving cursor: ${e.message}")
        }
    }
}

