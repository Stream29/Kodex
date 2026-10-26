package io.github.stream29.kodex.app.ui

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.*

val editorOverlayStackTest by testSuite {
    test("nested and sibling layers release exactly once") {
        val stack = EditorOverlayStack()
        assertFalse(stack.obscures(0))
        val parent = stack.enter(1)
        assertTrue(stack.obscures(0))
        assertFalse(stack.obscures(1))
        val child = stack.enter(2)
        val sibling = stack.enter(2)
        child.close()
        child.close()
        assertTrue(stack.obscures(1))
        sibling.close()
        assertFalse(stack.obscures(1))
        assertTrue(stack.obscures(0))
        parent.close()
        assertFalse(stack.obscures(0))
    }

    test("host disposal order and other hosts remain independent") {
        val first = EditorOverlayStack()
        val second = EditorOverlayStack()
        val parent = first.enter(1)
        val child = first.enter(2)
        parent.close()
        assertTrue(first.obscures(0))
        assertFalse(second.obscures(0))
        child.close()
        assertFalse(first.obscures(0))
    }
}
