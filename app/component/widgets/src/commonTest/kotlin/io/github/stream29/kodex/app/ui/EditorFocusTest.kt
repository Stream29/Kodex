package io.github.stream29.kodex.app.ui

import de.infix.testBalloon.framework.core.testSuite
import kotlin.test.*

val editorFocusTest by testSuite {
    test("focus is bound to one mount and stale release cannot clear its replacement") {
        val handle = UiEditorFocusHandle()
        assertFalse(handle.requestFocus())
        var firstCalls = 0
        var secondCalls = 0
        val first = handle.bind { firstCalls++; true }
        assertTrue(handle.requestFocus())
        assertFailsWith<IllegalStateException> { handle.bind { error("A second editor must not bind") } }
        assertTrue(handle.requestFocus())
        assertEquals(2, firstCalls)
        first.close()
        assertFalse(handle.requestFocus())
        val second = handle.bind { secondCalls++; false }
        first.close()
        assertFalse(handle.requestFocus())
        assertEquals(1, secondCalls)
        second.close()
        second.close()
        assertFalse(handle.requestFocus())
        assertEquals(1, secondCalls)
    }
    test("two handles remain independent and never fall back to another editor") {
        val first = UiEditorFocusHandle()
        val second = UiEditorFocusHandle()
        var calls = 0
        val registration = second.bind { calls++; true }
        assertFalse(first.requestFocus())
        assertTrue(second.requestFocus())
        assertEquals(1, calls)
        registration.close()
        assertFalse(first.requestFocus())
        assertFalse(second.requestFocus())
        assertEquals(1, calls)
    }
}
