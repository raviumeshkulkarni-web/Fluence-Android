package com.groq.voicetyper

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Method

class FluenceAccessibilityServiceTest {

    private lateinit var service: FluenceAccessibilityService
    private lateinit var findFocusedEditableNode: Method

    @Before
    fun setUp() {
        service = FluenceAccessibilityService()
        findFocusedEditableNode = FluenceAccessibilityService::class.java.getDeclaredMethod(
            "findFocusedEditableNode",
            AccessibilityNodeInfo::class.java,
            Int::class.javaPrimitiveType,
            IntArray::class.java
        )
        findFocusedEditableNode.isAccessible = true
    }

    private fun invoke(node: AccessibilityNodeInfo, budget: Int): AccessibilityNodeInfo? {
        return findFocusedEditableNode.invoke(service, node, 0, intArrayOf(budget)) as? AccessibilityNodeInfo
    }

    private fun editableFocusedNode(): AccessibilityNodeInfo {
        val node = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { node.isFocused } returns true
        every { node.isEditable } returns true
        return node
    }

    @Test
    fun findFocusedEditableNode_returnsFoundNodeAndRecyclesSiblings() {
        val sibling = mockk<AccessibilityNodeInfo>(relaxed = true)
        val leaf = editableFocusedNode()
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.childCount } returns 2
        every { root.getChild(0) } returns sibling
        every { root.getChild(1) } returns leaf

        val result = invoke(root, 100)

        assertSame(leaf, result)
        verify(exactly = 1) { sibling.recycle() }
        verify(exactly = 0) { leaf.recycle() }
        verify(exactly = 0) { root.recycle() }
    }

    @Test
    fun findFocusedEditableNode_returnsNullWhenNothingFocusedAndRecyclesAllChildren() {
        val child = mockk<AccessibilityNodeInfo>(relaxed = true)
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.childCount } returns 1
        every { root.getChild(0) } returns child

        val result = invoke(root, 100)

        assertNull(result)
        verify(exactly = 1) { child.recycle() }
    }

    @Test
    fun findFocusedEditableNode_stopsAtBudgetExhaustion() {
        val child = mockk<AccessibilityNodeInfo>(relaxed = true)
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.childCount } returns 3
        every { root.getChild(0) } returns child

        val result = invoke(root, 2)

        assertNull(result)
        verify(exactly = 1) { child.recycle() }
        verify(exactly = 0) { root.getChild(2) }
    }

    @Test
    fun findFocusedEditableNode_returnsRootWhenRootIsFocusedEditable() {
        val root = editableFocusedNode()

        val result = invoke(root, 100)

        assertSame(root, result)
        verify(exactly = 0) { root.recycle() }
    }

    // ── Opt-in "show only when keyboard is visible" gate ────────────────────
    // shouldShowBubble: mode off reduces to the focus requirement exactly
    // (existing behavior); mode on additionally requires IME visibility.
    @Test
    fun shouldShowBubble_modeOff_followsFocusOnly() {
        assertTrue(FluenceAccessibilityService.shouldShowBubble(true, false, false))
        assertTrue(FluenceAccessibilityService.shouldShowBubble(true, false, true))
        assertFalse(FluenceAccessibilityService.shouldShowBubble(false, false, false))
        assertFalse(FluenceAccessibilityService.shouldShowBubble(false, false, true))
    }

    @Test
    fun shouldShowBubble_modeOn_requiresEditableFocusAndIme() {
        assertTrue(FluenceAccessibilityService.shouldShowBubble(true, true, true))
        assertFalse(FluenceAccessibilityService.shouldShowBubble(true, true, false))
        assertFalse(FluenceAccessibilityService.shouldShowBubble(false, true, true))
        assertFalse(FluenceAccessibilityService.shouldShowBubble(false, true, false))
    }

    // shouldApplyImeHide: the IME gate hides only while IDLE — RECORDING,
    // TRANSCRIBING, and ERROR are never interrupted by keyboard dismissal.
    @Test
    fun shouldApplyImeHide_onlyWhileIdle() {
        assertTrue(
            FluenceAccessibilityService.shouldApplyImeHide(true, false, RecordingState.IDLE)
        )
        assertFalse(
            FluenceAccessibilityService.shouldApplyImeHide(true, false, RecordingState.RECORDING)
        )
        assertFalse(
            FluenceAccessibilityService.shouldApplyImeHide(true, false, RecordingState.TRANSCRIBING)
        )
        assertFalse(
            FluenceAccessibilityService.shouldApplyImeHide(true, false, RecordingState.ERROR)
        )
    }

    @Test
    fun shouldApplyImeHide_neverWhenModeOffOrImeVisible() {
        assertFalse(
            FluenceAccessibilityService.shouldApplyImeHide(false, false, RecordingState.IDLE)
        )
        assertFalse(
            FluenceAccessibilityService.shouldApplyImeHide(true, true, RecordingState.IDLE)
        )
        assertFalse(
            FluenceAccessibilityService.shouldApplyImeHide(false, true, RecordingState.RECORDING)
        )
    }

    @Test
    fun imeOnlyPref_defaultsToOff() {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs

        assertFalse(FloatingBubblePreferences.isImeOnly(context))
    }

    @Test
    fun imeOnlyPref_reflectsStoredValue() {
        val prefs = mockk<android.content.SharedPreferences>(relaxed = true)
        every { prefs.getBoolean(FloatingBubblePreferences.KEY_BUBBLE_IME_ONLY, false) } returns true
        val context = mockk<android.content.Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs

        assertTrue(FloatingBubblePreferences.isImeOnly(context))
    }
}
