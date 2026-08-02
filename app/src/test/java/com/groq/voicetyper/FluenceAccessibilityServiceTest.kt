package com.groq.voicetyper

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
}
